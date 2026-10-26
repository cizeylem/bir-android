package com.cizeylem.bir.attribution

import android.content.Context
import com.cizeylem.bir.AttributionSink
import com.cizeylem.bir.Consent
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class AttributionTest {

    private lateinit var dir: File
    private lateinit var ctx: Context

    @Before fun setUp() {
        dir = File.createTempFile("attr", "").apply { delete(); mkdirs() }
        ctx = RuntimeEnvironment.getApplication()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private class FakeSink(private val consent: Consent) : AttributionSink {
        var identified: Map<String, Any?>? = null
        var anonymous: Map<String, Any?>? = null
        var consentAsked = 0
        override fun withConsent(task: (Consent) -> Unit) {
            consentAsked++
            task(consent)
        }
        override fun sendIdentified(fields: Map<String, Any?>) { identified = fields }
        override fun sendAnonymous(fields: Map<String, Any?>) { anonymous = fields }
    }

    private fun run(
        consent: Consent,
        play: Map<String, Any?>? = null,
        meta: Map<String, Any?>? = null,
        gaid: String? = null,
        installSource: String? = null,
        metaBlocks: CountDownLatch? = null,
        metaTimeoutMs: Long = 5_000,
    ): Pair<FakeSink, AttributionRun> {
        val sink = FakeSink(consent)
        var gaidConsent: Consent? = null
        val run = AttributionRun(
            context = ctx,
            sink = sink,
            noBackupDir = { dir },
            collectInstallSource = { installSource },
            collectMeta = {
                metaBlocks?.await(10, TimeUnit.SECONDS)
                meta
            },
            collectPlay = { _: ScheduledExecutorService, _: ScheduledExecutorService, cb: (Map<String, Any?>?) -> Unit ->
                cb(play)
            },
            collectGaid = { c -> gaidConsent = c; gaid },
            metaTimeoutMs = metaTimeoutMs,
        )
        val done = CountDownLatch(1)
        sink.withConsent { c -> run.start(c) { done.countDown() } }
        assertTrue("çalışma bitmedi", done.await(15, TimeUnit.SECONDS))
        assertTrue("executor'lar kapatılmalı", run.terminated())
        if (gaidConsent != null) assertEquals(consent, gaidConsent)
        return sink to run
    }

    @Test fun `attribution rizasi varsa alanlar device_id ile gider`() {
        val (sink, _) = run(
            Consent(analytics = true, attribution = true),
            play = mapOf("install_referrer" to "utm_source=x"),
        )
        assertNull("kimlikli yolda anon uç kullanılmamalı", sink.anonymous)
        assertEquals("utm_source=x", sink.identified!!["play_install_referrer"])
    }

    @Test fun `riza yoksa anon uca gider ve govdede kimlik bulunmaz`() {
        val (sink, _) = run(Consent(), play = mapOf("install_referrer" to "utm_source=x"))
        assertNull("rızasız kimlikli uç kullanılamaz", sink.identified)
        val fields = sink.anonymous!!
        assertEquals("utm_source=x", fields["play_install_referrer"])
        assertFalse(fields.containsKey("device_id"))
        assertFalse(fields.containsKey("user_id"))
        assertFalse(fields.containsKey("distinct_id"))
    }

    @Test fun `yalniz attribution rizasi anon uca gider`() {
        // Kalıcı kimliği açan tek anahtar analytics; attribution tek başına kimlikli yolu açmaz.
        val (sink, _) = run(Consent(analytics = false, attribution = true), play = mapOf("install_referrer" to "r"))
        assertNull(sink.identified)
        assertEquals("r", sink.anonymous!!["play_install_referrer"])
    }

    @Test fun `install_source getInstallSourceInfo'dan alinir`() {
        val pm = shadowOf(ctx.packageManager)
        pm.setInstallSourceInfo(ctx.packageName, "com.android.vending", "com.android.vending")
        assertEquals("com.android.vending", installSource(ctx))
    }

    @Test fun `Play ve Meta referrer alanlari tek govdede birlesir`() {
        val (sink, _) = run(
            Consent(analytics = true, attribution = true),
            play = mapOf("install_referrer" to "utm_source=play", "install_version" to "1.2.3"),
            meta = mapOf("install_referrer" to "SERIALIZED", "is_ct" to 1),
            gaid = "cafe-1234",
            installSource = "com.android.vending",
        )
        val f = sink.identified!!
        assertEquals("utm_source=play", f["play_install_referrer"])
        assertEquals("1.2.3", f["play_install_version"])
        assertEquals("SERIALIZED", f["meta_install_referrer"])
        assertEquals(1, f["meta_is_ct"])
        assertEquals("cafe-1234", f["gaid"])
        assertEquals("com.android.vending", f["install_source"])
    }

    @Test fun `hicbir sinyal yoksa gonderim yapilmaz`() {
        val (sink, _) = run(Consent(analytics = true, attribution = true))
        assertNull(sink.identified)
        assertNull(sink.anonymous)
    }

    @Test fun `isaretlenen sinyal ikinci kez gonderilmez`() {
        val first = run(
            Consent(analytics = true, attribution = true),
            meta = mapOf("install_referrer" to "M"),
            installSource = "com.android.vending",
        ).first
        assertEquals("M", first.identified!!["meta_install_referrer"])

        val second = run(
            Consent(analytics = true, attribution = true),
            meta = mapOf("install_referrer" to "M"),
            installSource = "com.android.vending",
        ).first
        assertNull("aynı sinyaller ikinci açılışta yeniden gönderilmemeli", second.identified)
    }

    @Test fun `GAID yalniz gonderilecek bir sinyal varken okunur`() {
        var asked = false
        val sink = FakeSink(Consent(true, true, true))
        val run = AttributionRun(
            context = ctx,
            sink = sink,
            noBackupDir = { dir },
            collectInstallSource = { null },
            collectMeta = { null },
            collectPlay = { _, _, cb -> cb(null) },
            collectGaid = { asked = true; "x" },
        )
        val done = CountDownLatch(1)
        sink.withConsent { c -> run.start(c) { done.countDown() } }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        assertFalse("sinyal yokken GAID için bind edilmemeli", asked)
    }

    @Test fun `Meta okumasi zaman asimina ugrarsa akis devam eder`() {
        val gate = CountDownLatch(1)
        try {
            val (sink, _) = run(
                Consent(analytics = true, attribution = true),
                play = mapOf("install_referrer" to "P"),
                meta = mapOf("install_referrer" to "M"),
                metaBlocks = gate,
                metaTimeoutMs = 150,
            )
            val f = sink.identified!!
            assertEquals("P", f["play_install_referrer"])
            assertNull("zaman aşımına uğrayan Meta alanı gövdeye girmemeli", f["meta_install_referrer"])
        } finally {
            gate.countDown()
        }
    }

    @Test fun `cekirdek kurulmamissa hicbir sey calismaz`() {
        // withConsent'i hiç çağırmayan sink: çekirdek yoksa executor bile yaratılmamalı.
        val silent = object : AttributionSink {
            override fun withConsent(task: (Consent) -> Unit) = Unit
            override fun sendIdentified(fields: Map<String, Any?>) = throw AssertionError("gönderilmemeliydi")
            override fun sendAnonymous(fields: Map<String, Any?>) = throw AssertionError("gönderilmemeliydi")
        }
        Attribution.start(ctx, null, silent)
    }
}
