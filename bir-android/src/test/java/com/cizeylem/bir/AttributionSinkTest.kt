package com.cizeylem.bir

import android.app.Application
import com.cizeylem.bir.internal.Core
import com.cizeylem.bir.internal.Outcome
import com.cizeylem.bir.internal.Response
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** `Cizeylem`'in `AttributionSink` uygulaması: rıza kademesine göre kimlikli kuyruk ya da kimliksiz uç. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttributionSinkTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var sch: TestScheduler
    private val batches = ArrayList<String>()
    private val anon = ArrayList<String>()
    private var clock = 1_700_000_000_000L
    private var ids = 0
    private var anonResponse = Response(Outcome.OK, null)

    private fun dir() = File(app.noBackupFilesDir, "cizeylem")

    @Before fun setUp() {
        dir().deleteRecursively()
        init()
    }

    @After fun tearDown() {
        Cizeylem.shutdownForTest()
        dir().deleteRecursively()
    }

    private fun init() {
        sch = TestScheduler()
        Cizeylem.startForTest(
            Core(
                app = app,
                config = Configuration("ck_w_test"),
                now = { clock },
                elapsed = { 10_000L },
                uuid = { "uuid-${++ids}" },
                scheduler = sch,
                mainPoster = { it() },
                sender = { body -> batches.add(body); Response(Outcome.OK, null) },
                anonSender = { body -> anon.add(body); anonResponse },
            ),
        )
    }

    private fun grant(consent: Consent) {
        Cizeylem.setConsent(consent)
        sch.run()
        Cizeylem.flush()
        sch.run()
        batches.clear()
        anon.clear()
    }

    private val fields = mapOf("play_install_referrer" to "utm_source=x", "install_source" to "com.android.vending")

    @Test fun `withConsent cekirdek thread'inde ve bootstrap sonrasi calisir`() {
        grant(Consent(analytics = true, attribution = true))
        val seen = ArrayList<Consent>()
        Cizeylem.withConsent { seen.add(it) }
        assertTrue("iş çağıran thread'de yapılmamalı", seen.isEmpty())
        sch.run()
        assertEquals(listOf(Consent(analytics = true, attribution = true)), seen)
    }

    @Test fun `kimlikli gonderim device_id ile batch ucuna gider`() {
        grant(Consent(analytics = true, attribution = true))
        val delivered = ArrayList<Boolean>()
        Cizeylem.sendIdentified(fields) { delivered.add(it) }
        sch.run()
        assertEquals("kuyruğa yazıldı = kalıcı devir tamam", listOf(true), delivered)
        Cizeylem.flush()
        sch.run()
        assertTrue("anon uç kullanılmamalı", anon.isEmpty())
        val event = JSONObject(batches.single()).getJSONArray("events").getJSONObject(0)
        assertEquals("cz_attribution", event.getString("event"))
        assertEquals(Cizeylem.getDistinctId(), event.getString("distinct_id"))
        assertEquals("utm_source=x", event.getJSONObject("props").getString("play_install_referrer"))
    }

    @Test fun `kimliksiz gonderim anon uca gider ve govdede kimlik bulunmaz`() {
        grant(Consent(attribution = true))
        val delivered = ArrayList<Boolean>()
        Cizeylem.sendAnonymous(
            fields + mapOf("device_id" to "sızıntı", "user_id" to "sızıntı", "gaid" to "sızıntı"),
        ) { delivered.add(it) }
        sch.run()
        assertEquals("2xx = kalıcı devir tamam", listOf(true), delivered)
        val body = anon.single()
        assertTrue(batches.isEmpty())
        val o = JSONObject(body)
        assertEquals("utm_source=x", o.getString("play_install_referrer"))
        assertFalse("gövdede device_id olamaz", o.has("device_id"))
        assertFalse("gövdede user_id olamaz", o.has("user_id"))
        assertFalse("gövdede distinct_id olamaz", o.has("distinct_id"))
        // GAID cihaz kapsamlı, kalıcı bir reklam kimliğidir: kimliksiz uçta yeniden kimliklendirme sağlar.
        assertFalse("gövdede gaid olamaz", o.has("gaid"))
        assertTrue(o.has("sent_at"))
    }

    @Test fun `analytics kapaliyken kimlikli istek anon uca duser`() {
        // Rıza yokken kuyruk ve device_id yoktur; kimlikli gönderim sessizce kaybolmak yerine anon'a düşer.
        val delivered = ArrayList<Boolean>()
        Cizeylem.sendIdentified(fields) { delivered.add(it) }
        sch.run()
        assertEquals(listOf(true), delivered)
        assertTrue(batches.isEmpty())
        assertEquals("utm_source=x", JSONObject(anon.single()).getString("play_install_referrer"))
    }

    @Test fun `kimliksiz gonderim 2xx disinda teslim edilmis sayilmaz`() {
        grant(Consent(attribution = true))
        anonResponse = Response(Outcome.RETRY, null)
        val delivered = ArrayList<Boolean>()
        Cizeylem.sendAnonymous(fields) { delivered.add(it) }
        sch.run()
        assertEquals(1, anon.size)
        assertEquals("2xx gelmediyse devir tamam değildir", listOf(false), delivered)
    }

    @Test fun `cekirdek yoksa teslim edilmedi denir`() {
        Cizeylem.shutdownForTest()
        val delivered = ArrayList<Boolean>()
        Cizeylem.sendIdentified(fields) { delivered.add(it) }
        Cizeylem.sendAnonymous(fields) { delivered.add(it) }
        assertEquals(listOf(false, false), delivered)
    }
}
