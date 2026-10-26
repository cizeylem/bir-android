package com.cizeylem.bir

import android.app.Activity
import android.app.Application
import android.content.Context
import com.cizeylem.bir.internal.ConsentStore
import com.cizeylem.bir.internal.Core
import com.cizeylem.bir.internal.Outcome
import com.cizeylem.bir.internal.Response
import com.cizeylem.bir.internal.Scheduler
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Görevleri elle çalıştırılan zamanlayıcı: "çağıran thread'de disk yok" kuralı, görev kuyruğu boşaltılmadan
 * hiçbir dosyanın oluşmadığı gösterilerek sınanır.
 */
internal class TestScheduler : Scheduler {
    private val tasks = ArrayDeque<() -> Unit>()
    var periodMs = -1L
        private set
    private var periodic: (() -> Unit)? = null

    override fun post(task: () -> Unit) {
        tasks.addLast(task)
    }

    override fun schedulePeriodic(periodMs: Long, task: () -> Unit) {
        this.periodMs = periodMs
        periodic = task
    }

    override fun shutdown() {
        tasks.clear()
        periodic = null
    }

    fun run() {
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
    }

    fun tick() {
        periodic?.invoke()
    }
}

// targetSdk 36 Java 21 ister; ortamda JDK 17 var, bu yüzden sandbox SDK 34'e sabitlenir.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CizeylemTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var sch: TestScheduler
    private val sent = ArrayList<String>()
    private var response = Response(Outcome.OK, null)
    private var clock = 1_700_000_000_000L
    private var mono = 10_000L
    private var ids = 0
    private val logs = ArrayList<String>()

    private fun dir() = File(app.noBackupFilesDir, "cizeylem")

    @Before fun setUp() {
        dir().deleteRecursively()
        sch = TestScheduler()
    }

    @After fun tearDown() {
        Cizeylem.shutdownForTest()
        dir().deleteRecursively()
    }

    private fun init(config: Configuration = Configuration("ck_w_test", logger = Logger { logs.add(it) })) {
        // Her init yeni bir zamanlayıcı alır: "yeni süreç" senaryosu önceki görev kuyruğunu devralmamalı.
        sch = TestScheduler()
        Cizeylem.startForTest(
            Core(
                app = app,
                config = config,
                now = { clock },
                elapsed = { mono },
                uuid = { "uuid-${++ids}" },
                scheduler = sch,
                mainPoster = { it() },
                sender = { body -> sent.add(body); response },
            ),
        )
    }

    /** Rıza verip kuyruğu boşaltır: testler sonrasında yalnız kendi ürettikleri event'leri görür. */
    private fun grant(consent: Consent = Consent(analytics = true)) {
        Cizeylem.setConsent(consent)
        sch.run()
        Cizeylem.flush()
        sch.run()
        sent.clear()
    }

    private fun bodies() = sent.joinToString("\n")

    private fun count(event: String) = Regex(Regex.escape("\"event\":\"$event\"")).findAll(bodies()).count()

    @Test fun `initialize cagrilmadan track istisna firlatmaz`() {
        Cizeylem.track("a")
        Cizeylem.identify("u")
        Cizeylem.setUserProperties(mapOf("k" to 1))
        Cizeylem.sendUserProperties()
        Cizeylem.handleDeepLink("myapp://o?utm_source=x")
        Cizeylem.setOptOut(true)
        Cizeylem.flush()
        Cizeylem.reset()
        assertNull(Cizeylem.getDistinctId())
        assertEquals(Stats(0, 0, 0, false), Cizeylem.stats())
        assertFalse(dir().exists())
    }

    @Test fun `track cagiran thread'de dosya yazmaz`() {
        init()
        grant()
        val queueFile = File(File(dir(), "queue"), "queue.jsonl")
        queueFile.delete()
        Cizeylem.track("cz_test")
        assertFalse("track dönerken kuyruk dosyasına dokunulmamalı", queueFile.exists())
        sch.run()
        assertTrue(queueFile.exists())
    }

    @Test fun `identify sonrasi distinct_id user_id olur`() {
        init()
        grant()
        val anon = Cizeylem.getDistinctId()
        assertNotNull(anon)
        Cizeylem.identify("kullanici-7", mapOf("plan" to "pro"))
        sch.run()
        assertEquals("kullanici-7", Cizeylem.getDistinctId())
        Cizeylem.track("sonraki")
        sch.run()
        Cizeylem.flush()
        sch.run()
        val body = bodies()
        assertTrue(body.contains("\"\$identify\""))
        assertTrue(body.contains("\"user_id\":\"kullanici-7\""))
        // $identify'ın distinct_id'si eski anonim kimlik, sonraki event'inki user_id.
        assertTrue(body.contains("\"distinct_id\":\"$anon\""))
        assertTrue(body.contains("\"distinct_id\":\"kullanici-7\""))
        assertTrue(body.contains("\"plan\":\"pro\""))
    }

    @Test fun `reset yeni anonim kimlik uretir`() {
        init()
        grant()
        Cizeylem.identify("kullanici-7")
        sch.run()
        val before = Cizeylem.getDistinctId()
        Cizeylem.reset()
        sch.run()
        val after = Cizeylem.getDistinctId()
        assertNotNull(after)
        assertNotEquals(before, after)
        assertNotEquals("kullanici-7", after)
    }

    @Test fun `handleDeepLink cz_deep_link_opened uretir`() {
        init()
        grant()
        Cizeylem.handleDeepLink("myapp://open?utm_source=meta&click_id=abc&gizli=1")
        sch.run()
        Cizeylem.flush()
        sch.run()
        val body = bodies()
        assertTrue(body.contains("\"cz_deep_link_opened\""))
        assertTrue(body.contains("\"utm_source\":\"meta\""))
        assertTrue(body.contains("\"click_id\":\"abc\""))
        assertFalse(body.contains("gizli"))
    }

    @Test fun `setOptOut(true) uc riza anahtarini da kapatir`() {
        init()
        grant(Consent(analytics = true, attribution = true, adSharing = true))
        Cizeylem.setOptOut(true)
        sch.run()
        val stored = ConsentStore(File(dir(), "consent")).read()
        assertEquals(Consent(false, false, false), stored)
    }

    @Test fun `30 event birikince flush tetiklenir`() {
        init()
        grant()
        repeat(29) { Cizeylem.track("e$it") }
        sch.run()
        assertTrue("29 event'te gönderim olmamalı", sent.isEmpty())
        Cizeylem.track("e29")
        sch.run()
        assertEquals(1, sent.size)
        assertEquals(0, Cizeylem.stats().queued)
    }

    @Test fun `30 sn gecince zamanlayici flush tetikler`() {
        init()
        grant()
        assertEquals(30_000L, sch.periodMs)
        Cizeylem.track("tek")
        sch.run()
        assertTrue(sent.isEmpty())
        sch.tick()
        assertEquals(1, sent.size)
    }

    @Test fun `riza yokken init-track-flush hicbir dosya olusturmaz`() {
        init()
        Cizeylem.track("a")
        Cizeylem.identify("u")
        Cizeylem.handleDeepLink("myapp://o?utm_source=x")
        Cizeylem.flush()
        sch.run()
        sch.tick()
        assertFalse("rıza yokken cizeylem dizini hiç oluşmamalı", dir().exists())
        assertTrue(sent.isEmpty())
        assertNull(Cizeylem.getDistinctId())
    }

    @Test fun `oturum ya da ortam kurulmamissa dusen event sayilir`() {
        init()
        grant()
        // Üretimde oturum/ortam kuyrukla birlikte kurulur; yine de bu dal sessiz düşüş üretmemeli:
        // "sayılmayan düşüş" SDK çalışıyor görünürken hiçbir şey toplamadığını gizler.
        nullOutCoreField("sessionStore")
        val before = Cizeylem.stats().dropped
        Cizeylem.track("kayip")
        sch.run()
        assertTrue("event gönderilmemeli", sent.isEmpty())
        assertEquals("düşüş sayaca girmeli", before + 1, Cizeylem.stats().dropped)
    }

    /** Çekirdeğin özel alanını testten boşaltır: bu dalın başka bir tetikleyicisi yoktur. */
    private fun nullOutCoreField(name: String) {
        val coreField = Cizeylem.javaClass.getDeclaredField("core").apply { isAccessible = true }
        val core = coreField.get(Cizeylem)!!
        core.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(core, null)
    }

    @Test fun `riza geri alininca kuyruk ve device_id silinir`() {
        init()
        grant()
        Cizeylem.track("birikmis")
        sch.run()
        val deviceId = File(dir(), "device_id")
        assertTrue(deviceId.exists())
        assertTrue(File(File(dir(), "queue"), "queue.jsonl").exists())

        Cizeylem.setConsent(Consent(analytics = false))
        sch.run()
        assertFalse("rıza geri alınınca device_id silinmeli", deviceId.exists())
        assertFalse("rıza geri alınınca kuyruk silinmeli", File(dir(), "queue").exists())
        assertNull(Cizeylem.getDistinctId())
    }

    @Test fun `riza geri alindiktan sonra track kuyruga yazmaz`() {
        init()
        grant()
        Cizeylem.setConsent(Consent(analytics = false))
        // Asıl mekanizma: distinct_id çağıran thread'de, görev kuyruğu boşaltılmadan geçersizleşir.
        assertNull(Cizeylem.getDistinctId())
        // Geri alma ile aynı turda gelen track: bayat rıza önbelleği burada veri toplardı.
        Cizeylem.track("gecikmis")
        sch.run()
        Cizeylem.flush()
        sch.run()
        assertTrue(sent.isEmpty())
        assertFalse(File(dir(), "queue").exists())
    }

    @Test fun `dolar onekli prop anahtarlari suzulur`() {
        init()
        grant()
        Cizeylem.track("e", mapOf("\$set" to "kotu", "normal" to 1))
        sch.run()
        Cizeylem.flush()
        sch.run()
        val body = bodies()
        assertTrue(body.contains("\"normal\":1"))
        assertFalse("\$ önekli prop anahtarı sunucuda bütün event'i düşürür", body.contains("kotu"))
    }

    @Test fun `kullanici ozellikleri bir sonraki event'e ilisir`() {
        init()
        grant()
        Cizeylem.setUserProperties(mapOf("a" to 1))
        Cizeylem.setUserPropertiesOnce(mapOf("b" to 2))
        Cizeylem.addUserProperties(mapOf("sayac" to 3))
        Cizeylem.unsetUserProperties(listOf("eski"))
        Cizeylem.track("tasiyici")
        sch.run()
        Cizeylem.flush()
        sch.run()
        val body = bodies()
        assertTrue(body.contains("\"\$set\""))
        assertTrue(body.contains("\"\$set_once\""))
        assertTrue(body.contains("\"\$add\""))
        assertTrue(body.contains("\"\$unset\""))
        sent.clear()
        // Bir kez iliştirilir; ikinci event'te tekrarlanmaz.
        Cizeylem.track("ikinci")
        sch.run()
        Cizeylem.flush()
        sch.run()
        assertFalse(bodies().contains("\"\$set\""))
    }

    @Test fun `riza geri alma diske yazilamazsa kayit silinir ve sonraki surec rizasiz baslar`() {
        init()
        grant()
        val consentFile = File(dir(), "consent")
        assertTrue(consentFile.exists())
        // atomicWrite "<ad>.tmp" dosyasına yazar; o yol bir dizinse yazma IOException ile düşer.
        val tmp = File(dir(), "consent.tmp")
        assertTrue(tmp.mkdirs())

        Cizeylem.setConsent(Consent(analytics = false))
        sch.run()
        assertFalse(
            "kapatma yazılamadıysa kayıt silinmeli: aksi halde bir sonraki süreç rızayı diriltir",
            consentFile.exists(),
        )

        // "Yeni süreç": temiz bir çekirdek aynı dizinle başlar.
        tmp.deleteRecursively()
        init()
        sch.run()
        assertNull(Cizeylem.getDistinctId())
        assertFalse(File(dir(), "device_id").exists())
    }

    @Test fun `kismi riza daraltmasi yazilamazsa da kayit silinir`() {
        init()
        grant(Consent(analytics = true, adSharing = true))
        val consentFile = File(dir(), "consent")
        assertTrue(ConsentStore(consentFile).read().adSharing)
        val tmp = File(dir(), "consent.tmp")
        assertTrue(tmp.mkdirs())

        // Daraltma: analytics açık kalıyor, adSharing kapanıyor. Yazma düşerse eski (daha geniş)
        // kayıt diskte kalırsa bir sonraki süreç adSharing'i geri açardı.
        Cizeylem.setConsent(Consent(analytics = true, adSharing = false))
        sch.run()
        assertFalse(consentFile.exists())

        tmp.deleteRecursively()
        init()
        sch.run()
        assertEquals(Consent(false, false, false), ConsentStore(consentFile).read())
        assertNull(Cizeylem.getDistinctId())
    }

    @Test fun `kuyruga yazilamayan event bekleyen ozellikleri yakmaz`() {
        init()
        grant()
        Cizeylem.setUserProperties(mapOf("plan" to "pro"))
        // queue.jsonl bir dizinse append dosya hatasıyla düşer.
        val queueFile = File(File(dir(), "queue"), "queue.jsonl")
        queueFile.delete()
        assertTrue(queueFile.mkdirs())
        Cizeylem.track("kayip")
        sch.run()
        assertTrue(sent.isEmpty())

        queueFile.deleteRecursively()
        Cizeylem.track("sonraki")
        sch.run()
        Cizeylem.flush()
        sch.run()
        assertTrue("bekleyen \$set geri konmalı", bodies().contains("\"plan\":\"pro\""))
    }

    @Test fun `kimlik uretilemezse event dusurulur, sayilir ve sonraki event'te yeniden denenir`() {
        init()
        // device_id yolu bir dizinse DeviceIdStore depoyu "erişilemez" sayar ve hiçbir şey yazmaz.
        val idFile = File(dir(), "device_id")
        assertTrue(idFile.mkdirs())
        Cizeylem.setConsent(Consent(analytics = true))
        sch.run()
        assertNull(Cizeylem.getDistinctId())

        // Erken dönüşte de bekleyen özellikler yanmamalı: event düşer, birikim kalır.
        Cizeylem.setUserProperties(mapOf("plan" to "pro"))
        Cizeylem.track("kimliksiz")
        sch.run()
        assertEquals(1, Cizeylem.stats().dropped)
        assertEquals(0, Cizeylem.stats().queued)
        assertTrue(logs.any { it.contains("cihaz kimliği") })

        // Depo erişilebilir olunca kurulum yarıda kalmaz: kimlik sonraki event'te üretilir.
        idFile.deleteRecursively()
        Cizeylem.track("kimlikli")
        sch.run()
        assertNotNull(Cizeylem.getDistinctId())
        assertEquals(1, Cizeylem.stats().queued)
        Cizeylem.flush()
        sch.run()
        assertTrue("düşen event bekleyen \$set'i tüketmemeli", bodies().contains("\"plan\":\"pro\""))
    }

    @Test fun `Amplitude opt-out devralmada migrated_opt_out olarak isaretlenir`() {
        val p = app.getSharedPreferences("com.amplitude.api.${app.packageName}", Context.MODE_PRIVATE)
        p.edit()
            .putString("com.amplitude.api.deviceId", "a1b2c3d4")
            .putBoolean("com.amplitude.api.optOut", true)
            .commit()
        init()
        // Açık rıza taze kazanır; eski üçüncü taraf bayrağı yalnız bilgi olarak taşınır.
        grant()
        Cizeylem.track("ilk")
        sch.run()
        Cizeylem.flush()
        sch.run()
        val body = bodies()
        assertTrue(body.contains("\"migrated_from\":\"amplitude-prefs\""))
        assertTrue(body.contains("\"migrated_opt_out\":true"))
        assertTrue(logs.any { it.contains("migrated_opt_out") })
    }

    @Test fun `riza sonradan gelince ekrandaki Activity icin tek cz_app_opened uretilir`() {
        init()
        sch.run()
        // Rıza yokken yaşam döngüsü izlenmez; Core yalnız bellekte sayar.
        val a = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        sch.run()
        assertTrue(sent.isEmpty())

        Cizeylem.setConsent(Consent(analytics = true))
        sch.run()
        Cizeylem.flush()
        sch.run()
        assertEquals("ekranda duran Activity tam bir kez aktarılmalı", 1, count("cz_app_opened"))

        // Aynı Activity arka plana gidip dönerse ikinci açılış gelir; çift sayım yoktur.
        a.pause().stop()
        sch.run()
        a.start().resume()
        sch.run()
        Cizeylem.flush()
        sch.run()
        assertEquals(2, count("cz_app_opened"))
        assertEquals(1, count("cz_app_backgrounded"))
    }

    @Test fun `Activity gecisinde cz_app_opened ciftlenmez`() {
        init()
        sch.run()
        // A rıza ÖNCESİ başlar: tracker henüz yok, Core yalnız bellekte sayar.
        val first = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        sch.run()
        // Rıza gelince attachTracker A'yı aktarır (tek açılış).
        Cizeylem.setConsent(Consent(analytics = true))
        sch.run()
        Cizeylem.flush()
        sch.run()
        sent.clear()
        // Android sırası: yeni Activity başlar, sonra eskisi durur. Arada ön plan hiç boşalmaz,
        // aktarılmış A'nın durması ikinci bir açılış/arka plan üretmemeli.
        val second = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        first.pause().stop()
        sch.run()
        Cizeylem.flush()
        sch.run()
        assertEquals("geçişte ikinci bir açılış olmamalı", 0, count("cz_app_opened"))
        assertEquals(0, count("cz_app_backgrounded"))
        second.pause().stop()
        sch.run()
        Cizeylem.flush()
        sch.run()
        assertEquals(1, count("cz_app_backgrounded"))
    }

    @Test fun `sendUserProperties bekleyen ozellik yoksa event uretmez`() {
        init()
        grant()
        Cizeylem.sendUserProperties()
        sch.run()
        Cizeylem.flush()
        sch.run()
        assertTrue(sent.isEmpty())
    }
}
