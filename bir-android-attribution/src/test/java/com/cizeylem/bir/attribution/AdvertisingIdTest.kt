package com.cizeylem.bir.attribution

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.cizeylem.bir.Consent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val DESCRIPTOR = "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdvertisingIdTest {
    private val real = "cafe1234-0000-4000-8000-000000000001"
    private val zero = "00000000-0000-0000-0000-000000000000"

    private lateinit var worker: java.util.concurrent.ExecutorService

    @Before fun setUp() {
        worker = Executors.newSingleThreadExecutor { r -> Thread(r, "test-adid").apply { isDaemon = true } }
    }

    @After fun tearDown() {
        worker.shutdownNow()
    }

    /** Wire protokolünü doğrulayan sahte GMS servisi: descriptor, kodlar ve kod 2'nin writeInt(1)'i denetlenir. */
    private class FakeGms(
        val id: String?,
        val limitAdTracking: Boolean,
        val throwOnTransact: Boolean = false,
    ) : Binder() {
        val codes = ArrayList<Int>()
        var latArg: Int? = null
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            codes.add(code)
            data.enforceInterface(DESCRIPTOR)
            if (throwOnTransact) throw RuntimeException("servis çöktü")
            when (code) {
                1 -> {
                    reply!!.writeNoException()
                    reply.writeString(id)
                }
                2 -> {
                    latArg = data.readInt()
                    reply!!.writeNoException()
                    reply.writeInt(if (limitAdTracking) 1 else 0)
                }
                else -> return false
            }
            return true
        }
    }

    private class FakeContext(
        base: Context,
        var binder: IBinder?,
        var permission: Int = PackageManager.PERMISSION_GRANTED,
        var bindResult: Boolean = true,
    ) : ContextWrapper(base) {
        var bindCalls = 0
        var boundIntent: Intent? = null
        var boundFlags = -1
        var unbound = 0
        var bindThrows: RuntimeException? = null
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(perm: String): Int =
            if (perm == "com.google.android.gms.permission.AD_ID") permission else super.checkSelfPermission(perm)
        override fun bindService(service: Intent, c: ServiceConnection, flags: Int): Boolean {
            bindCalls++
            bindThrows?.let { throw it }
            boundIntent = service
            boundFlags = flags
            if (!bindResult) return false
            val b = binder
            if (b != null) c.onServiceConnected(ComponentName("com.google.android.gms", "Svc"), b) else c.onNullBinding(
                ComponentName("com.google.android.gms", "Svc"),
            )
            return true
        }
        override fun unbindService(c: ServiceConnection) { unbound++ }
    }

    /** GAID okuması ana thread'de yasaktır; Robolectric testleri ana thread'de koştuğu için işçiye taşınır. */
    private fun readOnWorker(ctx: Context, consent: Consent, timeoutMs: Long = 10_000): String? =
        worker.submit<String?> { AdvertisingIdReader(ctx, timeoutMs).read(consent) }.get(10, TimeUnit.SECONDS)

    @Test fun `adSharing kapaliyken GAID okunmaz`() {
        val ctx = FakeContext(RuntimeEnvironment.getApplication(), FakeGms(real, false))
        assertNull(readOnWorker(ctx, Consent(analytics = true, attribution = true, adSharing = false)))
        assertEquals("bind denenmemeliydi", 0, ctx.bindCalls)
    }

    @Test fun `AD_ID izni yokken GAID okunmaz`() {
        val ctx = FakeContext(RuntimeEnvironment.getApplication(), FakeGms(real, false))
        ctx.permission = PackageManager.PERMISSION_DENIED
        assertNull(readOnWorker(ctx, Consent(true, true, true)))
        assertEquals("bind denenmemeliydi", 0, ctx.bindCalls)
    }

    @Test fun `limit ad tracking acikken GAID okunmaz`() {
        val gms = FakeGms(real, limitAdTracking = true)
        val ctx = FakeContext(RuntimeEnvironment.getApplication(), gms)
        assertNull(readOnWorker(ctx, Consent(true, true, true)))
        assertTrue("LAT sorulmalıydı", gms.codes.contains(2))
        assertEquals("LAT açıkken kimlik hiç istenmemeliydi", listOf(2), gms.codes)
        assertEquals(1, ctx.unbound)
    }

    @Test fun `sifir UUID gonderilmez`() {
        val ctx = FakeContext(RuntimeEnvironment.getApplication(), FakeGms(zero, false))
        assertNull(readOnWorker(ctx, Consent(true, true, true)))
    }

    @Test fun `uc kapi da aciksa GAID dondurulur`() {
        val gms = FakeGms(real, limitAdTracking = false)
        val ctx = FakeContext(RuntimeEnvironment.getApplication(), gms)
        assertEquals(real, readOnWorker(ctx, Consent(true, true, true)))
        val intent = ctx.boundIntent!!
        assertEquals("com.google.android.gms.ads.identifier.service.START", intent.action)
        assertEquals("com.google.android.gms", intent.`package`)
        assertNull("setComponent kullanılmamalı", intent.component)
        assertEquals("BIND_AUTO_CREATE", 1, ctx.boundFlags)
        assertEquals("kod 2'ye writeInt(1) yazılmalı", 1, gms.latArg)
        assertEquals(listOf(2, 1), gms.codes)
        assertEquals(1, ctx.unbound)
    }

    @Test fun `GMS yoksa null doner`() {
        val ctx = FakeContext(RuntimeEnvironment.getApplication(), null)
        ctx.bindResult = false
        assertNull(readOnWorker(ctx, Consent(true, true, true)))
    }

    @Test fun `null binding null doner`() {
        val ctx = FakeContext(RuntimeEnvironment.getApplication(), null)
        assertNull(readOnWorker(ctx, Consent(true, true, true)))
        assertEquals(1, ctx.unbound)
    }

    @Test fun `bindService SecurityException yutulur`() {
        val ctx = FakeContext(RuntimeEnvironment.getApplication(), FakeGms(real, false))
        ctx.bindThrows = SecurityException("izin yok")
        assertNull(readOnWorker(ctx, Consent(true, true, true)))
    }

    @Test fun `servis istisnasi null doner`() {
        val ctx = FakeContext(RuntimeEnvironment.getApplication(), FakeGms(real, false, throwOnTransact = true))
        assertNull(readOnWorker(ctx, Consent(true, true, true)))
        assertEquals(1, ctx.unbound)
    }

    @Test fun `ana thread'de okuma reddedilir`() {
        val ctx = FakeContext(RuntimeEnvironment.getApplication(), FakeGms(real, false))
        // Robolectric testi ana thread'de koşar: guard'ın gerçekten ana thread'i reddettiğinin kanıtı.
        assertNull(AdvertisingIdReader(ctx, 10_000).read(Consent(true, true, true)))
        assertEquals("bind denenmemeliydi", 0, ctx.bindCalls)
    }
}
