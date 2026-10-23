package com.cizeylem.bir

import android.app.Application
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

    private fun dir() = File(app.noBackupFilesDir, "cizeylem")

    @Before fun setUp() {
        dir().deleteRecursively()
        sch = TestScheduler()
    }

    @After fun tearDown() {
        Cizeylem.shutdownForTest()
        dir().deleteRecursively()
    }

    private fun init(config: Configuration = Configuration("ck_w_test")) {
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
