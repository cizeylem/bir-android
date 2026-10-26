package com.cizeylem.bir.attribution

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayReferrerTest {
    private val action = "com.google.android.finsky.BIND_GET_INSTALL_REFERRER_SERVICE"
    private val descriptor = "com.google.android.finsky.externalreferrer.IGetInstallReferrerService"
    private val svcClass = "com.google.android.finsky.externalreferrer.GetInstallReferrerService"

    private lateinit var dir: File
    private lateinit var executor: ScheduledExecutorService
    private lateinit var timer: ScheduledExecutorService
    private lateinit var ctx: FakeContext

    /** Context: bindService'i yakalar, gerçek PackageManager'ı (Robolectric) kullanır. */
    private class FakeContext(base: Context) : ContextWrapper(base) {
        var bindResult = true
        var bindThrows: RuntimeException? = null
        var boundIntent: Intent? = null
        var boundFlags = -1
        var conn: ServiceConnection? = null
        var bindThread: Thread? = null
        var unbound = 0
        var pmThrows: RuntimeException? = null
        @Volatile var onBind: () -> Unit = {}
        /** Gerçek Context gibi: bind kaydı bindService'in DÖNÜŞÜNDE oluşur; öncesindeki unbind etkisizdir. */
        @Volatile var live = false
        override fun getPackageManager(): android.content.pm.PackageManager { pmThrows?.let { throw it }; return super.getPackageManager() }
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, c: ServiceConnection, flags: Int): Boolean {
            bindThread = Thread.currentThread()
            bindThrows?.let { throw it }
            onBind()
            boundIntent = service; boundFlags = flags; conn = c
            live = bindResult
            return bindResult
        }
        override fun unbindService(c: ServiceConnection) { unbound++; live = false }
    }

    @Before fun setUp() {
        dir = File.createTempFile("playref", "").apply { delete(); mkdirs() }
        executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-worker").apply { isDaemon = true } }
        timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-timer").apply { isDaemon = true } }
        ctx = FakeContext(RuntimeEnvironment.getApplication())
    }

    @After fun tearDown() {
        executor.shutdownNow()
        timer.shutdownNow()
        dir.deleteRecursively()
    }

    private fun installPlay(versionCode: Int = 80837300) {
        val pm = shadowOf(ctx.packageManager)
        val intent = Intent(action).setComponent(ComponentName("com.android.vending", svcClass))
        val ri = ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = "com.android.vending"; name = svcClass } }
        pm.addResolveInfoForIntent(intent, ri)
        pm.installPackage(PackageInfo().apply { packageName = "com.android.vending"; this.versionCode = versionCode })
    }

    private fun reader(timeoutMs: Long = 15_000, noBackup: () -> File? = { dir }) =
        PlayReferrerReader(ctx, noBackup, executor, timer, timeoutMs)

    private class Result {
        val latch = CountDownLatch(1)
        val value = AtomicReference<Map<String, Any?>?>()
        val cbThread = AtomicReference<Thread>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val cb: (Map<String, Any?>?) -> Unit = { v -> cbThread.set(Thread.currentThread()); value.set(v); calls.incrementAndGet(); latch.countDown() }
        fun await(): Map<String, Any?>? { assertTrue("callback gelmedi", latch.await(5, TimeUnit.SECONDS)); return value.get() }
    }

    /** Elle kurulan wire protokolünü (AIDL ile aynı) doğrulayan sahte Play servisi: descriptor, kod 1 ve istek Bundle'ı denetlenir. */
    private inner class FakeService(val reply: Bundle?, val throwRemote: Boolean = false, val block: CountDownLatch? = null) : Binder() {
        var code = -1
        var requestPackage: String? = null
        var transactThread: Thread? = null
        override fun onTransact(c: Int, data: Parcel, replyP: Parcel?, flags: Int): Boolean {
            code = c
            transactThread = Thread.currentThread()
            data.enforceInterface(descriptor)
            block?.await(10, TimeUnit.SECONDS)
            if (data.readInt() != 0) requestPackage = Bundle.CREATOR.createFromParcel(data).getString("package_name")
            if (throwRemote) throw RuntimeException("servis çöktü")
            replyP!!.writeNoException()
            if (reply == null) replyP.writeInt(0) else { replyP.writeInt(1); reply.writeToParcel(replyP, 0) }
            return true
        }
    }

    private fun fullBundle() = Bundle().apply {
        putString("install_referrer", "utm_source=google&utm_medium=cpc")
        putLong("referrer_click_timestamp_seconds", 1700000001L)
        putLong("referrer_click_timestamp_server_seconds", 1700000002L)
        putLong("install_begin_timestamp_seconds", 1700000003L)
        putLong("install_begin_timestamp_server_seconds", 1700000004L)
        putBoolean("google_play_instant", true)
        putString("install_version", "1.2.3")
    }

    private fun waitBound() {
        val end = System.currentTimeMillis() + 5000
        while (ctx.conn == null && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue("bindService çağrılmadı", ctx.conn != null)
    }

    @Test fun `tum referrer alanlari okunur ve server_seconds dahil edilir`() {
        installPlay()
        val svc = FakeService(fullBundle())
        val r = Result()
        reader().read(r.cb)
        waitBound()
        ctx.conn!!.onServiceConnected(ComponentName("com.android.vending", svcClass), svc)
        val m = r.await()!!
        assertEquals(
            mapOf<String, Any?>(
                "install_referrer" to "utm_source=google&utm_medium=cpc",
                "referrer_click_timestamp_seconds" to 1700000001L,
                "referrer_click_timestamp_server_seconds" to 1700000002L,
                "install_begin_timestamp_seconds" to 1700000003L,
                "install_begin_timestamp_server_seconds" to 1700000004L,
                "google_play_instant" to true,
                "install_version" to "1.2.3",
            ),
            m,
        )
        assertEquals(1, svc.code)
        assertEquals(ctx.packageName, svc.requestPackage)
        assertTrue(AttributionState(dir).isDone("play_referrer"))
        assertEquals(1, ctx.unbound)
        assertEquals(1, r.calls.get())
    }

    @Test fun `intent action ve component dogru setPackage yok ve BIND_AUTO_CREATE`() {
        installPlay()
        reader().read(Result().cb)
        waitBound()
        val i = ctx.boundIntent!!
        assertEquals(action, i.action)
        assertEquals(ComponentName("com.android.vending", svcClass), i.component)
        assertNull(i.`package`)
        assertEquals(Context.BIND_AUTO_CREATE, ctx.boundFlags)
    }

    @Test fun `bindService ve Binder cagrisi ana thread degil`() {
        installPlay()
        val svc = FakeService(fullBundle())
        val r = Result()
        reader().read(r.cb)
        waitBound()
        // onServiceConnected ana thread'de gelir; Binder çağrısı yine işçi thread'de olmalı.
        ctx.conn!!.onServiceConnected(ComponentName("com.android.vending", svcClass), svc)
        r.await()
        val main = Looper.getMainLooper().thread
        assertNotEquals(main, ctx.bindThread)
        assertNotEquals(main, svc.transactThread)
        assertEquals("test-worker", svc.transactThread!!.name)
    }

    @Test fun `partial bundle yalniz mevcut anahtarlari tasir`() {
        installPlay()
        val r = Result()
        reader().read(r.cb)
        waitBound()
        val b = Bundle().apply { putString("install_referrer", "x=1") }
        ctx.conn!!.onServiceConnected(ComponentName("a", "b"), FakeService(b))
        assertEquals(mapOf<String, Any?>("install_referrer" to "x=1"), r.await())
    }

    @Test fun `Play Store yoksa callback null ile cagrilir, istisna yok`() {
        val r = Result()
        reader().read(r.cb)
        assertNull(r.await())
        assertNull(ctx.conn)
        assertFalse(AttributionState(dir).isDone("play_referrer"))
        assertFalse(File(dir, "cizeylem").exists())
    }

    @Test fun `bindService false ise null`() {
        installPlay()
        ctx.bindResult = false
        val r = Result()
        reader().read(r.cb)
        assertNull(r.await())
        assertFalse(AttributionState(dir).isDone("play_referrer"))
    }

    @Test fun `bindService SecurityException sizdirmaz`() {
        installPlay()
        ctx.bindThrows = SecurityException("izin yok")
        val r = Result()
        reader().read(r.cb)
        assertNull(r.await())
    }

    @Test fun `Play eski surumse FEATURE_NOT_SUPPORTED bind yok`() {
        installPlay(versionCode = 80837299)
        val r = Result()
        reader().read(r.cb)
        assertNull(r.await())
        assertNull(ctx.conn)
    }

    @Test fun `servis baglanti kopmasinda callback null ile cagrilir`() {
        installPlay()
        val r = Result()
        reader().read(r.cb)
        waitBound()
        ctx.conn!!.onServiceDisconnected(ComponentName("a", "b"))
        assertNull(r.await())
        assertEquals(1, ctx.unbound)
        assertFalse(AttributionState(dir).isDone("play_referrer"))
    }

    @Test fun `baglanti hic gelmezse zaman asimi null verir`() {
        installPlay()
        val r = Result()
        reader(timeoutMs = 100).read(r.cb)
        assertNull(r.await())
        assertEquals(1, ctx.unbound)
    }

    @Test fun `servis istisna atarsa null ve isaret yok`() {
        installPlay()
        val r = Result()
        reader().read(r.cb)
        waitBound()
        ctx.conn!!.onServiceConnected(ComponentName("a", "b"), FakeService(null, throwRemote = true))
        assertNull(r.await())
        assertFalse(AttributionState(dir).isDone("play_referrer"))
        assertEquals(1, ctx.unbound)
    }

    @Test fun `servis null Bundle donerse null ve isaret yok`() {
        installPlay()
        val r = Result()
        reader().read(r.cb)
        waitBound()
        ctx.conn!!.onServiceConnected(ComponentName("a", "b"), FakeService(null))
        assertNull(r.await())
        assertFalse(AttributionState(dir).isDone("play_referrer"))
    }

    @Test fun `basarili okumanin ardindan ikinci okuma bind etmeden null verir`() {
        installPlay()
        val r1 = Result()
        reader().read(r1.cb)
        waitBound()
        ctx.conn!!.onServiceConnected(ComponentName("a", "b"), FakeService(fullBundle()))
        assertTrue(r1.await() != null)
        ctx.conn = null
        val r2 = Result()
        reader().read(r2.cb)
        assertNull(r2.await())
        assertNull(ctx.conn)
    }

    @Test fun `bir kez okunduktan sonra tekrar okunmaz`() {
        installPlay()
        AttributionState(dir).markDone("play_referrer")
        val r = Result()
        reader().read(r.cb)
        assertNull(r.await())
        assertNull(ctx.conn)
    }

    @Test fun `gec gelen ikinci olay callback'i ikinci kez cagirmaz`() {
        installPlay()
        val r = Result()
        reader().read(r.cb)
        waitBound()
        ctx.conn!!.onServiceDisconnected(ComponentName("a", "b"))
        r.await()
        ctx.conn!!.onServiceConnected(ComponentName("a", "b"), FakeService(fullBundle()))
        Thread.sleep(200)
        assertEquals(1, r.calls.get())
    }

    @Test fun `noBackupDir cozumlemesi cagiranin thread'inde degil iscide yapilir`() {
        val resolvedOn = AtomicReference<Thread>()
        val r = Result()
        reader(noBackup = { resolvedOn.set(Thread.currentThread()); dir }).read(r.cb)
        r.await()
        assertEquals("test-worker", resolvedOn.get().name)
    }

    @Test fun `noBackupDir cozumlemesi istisna atarsa callback yine null ile gelir`() {
        val r = Result()
        reader(noBackup = { throw IllegalStateException("depo yok") }).read(r.cb)
        assertNull(r.await())
        assertEquals(1, r.calls.get())
    }

    @Test fun `PackageManager istisna atarsa callback yine null ile gelir`() {
        ctx.pmThrows = IllegalStateException("binder öldü") // DeadObject/IllegalState benzeri
        val r = Result()
        reader().read(r.cb)
        assertNull(r.await())
        assertNull(ctx.conn)
    }

    @Test fun `bindService SecurityException disi istisna da null verir ve unbind denenir`() {
        installPlay()
        ctx.bindThrows = IllegalStateException("beklenmedik")
        val r = Result()
        reader().read(r.cb)
        assertNull(r.await())
        assertEquals(1, ctx.unbound)
    }

    @Test fun `onNullBinding null verir ve baglantiyi birakir`() {
        installPlay()
        val r = Result()
        reader().read(r.cb)
        waitBound()
        ctx.conn!!.onNullBinding(ComponentName("a", "b"))
        assertNull(r.await())
        assertEquals(1, ctx.unbound)
    }

    @Test fun `transact takilirsa zaman asimi yine de callback'i null ile getirir ve isaret yazilmaz`() {
        installPlay()
        val gate = CountDownLatch(1)
        val r = Result()
        reader(timeoutMs = 150).read(r.cb)
        waitBound()
        ctx.conn!!.onServiceConnected(ComponentName("a", "b"), FakeService(fullBundle(), block = gate))
        assertNull(r.await()) // işçi gate'te bloklu; teslim ayrı thread'den gelmeli
        assertEquals(1, ctx.unbound)
        gate.countDown()
        Thread.sleep(300) // geç dönen referrer artık ne callback'i tekrar çağırır ne işaret yazar
        assertEquals(1, r.calls.get())
        assertFalse(AttributionState(dir).isDone("play_referrer"))
    }

    @Test fun `callback her yolda ayni thread'de cagrilir ve bu ana thread degildir`() {
        installPlay()
        val ok = Result()
        reader().read(ok.cb)
        waitBound()
        ctx.conn!!.onServiceConnected(ComponentName("a", "b"), FakeService(fullBundle()))
        ok.await()
        ctx.conn = null
        val dc = Result()
        reader(noBackup = { File(dir, "yok") }).read(dc.cb)
        waitBound()
        ctx.conn!!.onServiceDisconnected(ComponentName("a", "b"))
        dc.await()
        assertEquals("test-timer", ok.cbThread.get().name)
        assertEquals("test-timer", dc.cbThread.get().name)
    }

    @Test fun `zaman asimi markDone'dan hemen once ates ederse isaret ve callback tutarli kalir`() {
        installPlay()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val r = Result()
        // noBackupDir ikinci kez markDone için çözülür (kontrol geçildikten sonra): orada zaman aşımını geçirt.
        reader(timeoutMs = 150, noBackup = { if (calls.incrementAndGet() >= 2) Thread.sleep(500); dir }).read(r.cb)
        waitBound()
        ctx.conn!!.onServiceConnected(ComponentName("a", "b"), FakeService(fullBundle()))
        val got = r.await()
        Thread.sleep(700) // markDone'ın bitmesini bekle
        // Değişmez: işaret yazıldıysa referrer teslim edilmiş olmalı (aksi halde atıf kalıcı kaybolur).
        assertEquals("işaret ile teslim tutarsız", AttributionState(dir).isDone("play_referrer"), got != null)
        assertEquals(1, r.calls.get())
    }

    @Test fun `bind sonrasi gec zaman asimi baglantiyi birakir`() {
        installPlay()
        val r = Result()
        // bindService dönmeden zaman aşımı ateşlenir: bağlantı yine de bırakılmalı.
        ctx.onBind = { Thread.sleep(400) } // kayıt (live) bu uyku BİTİNCE oluşur
        reader(timeoutMs = 100).read(r.cb)
        assertNull(r.await())
        waitBound() // bindService artık döndü (kayıt oluştu)
        Thread.sleep(300)
        assertFalse("bağlantı sızdı", ctx.live)
    }
}
