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

    /**
     * @param delivered gönderimin KALICI devir sonucu: kimlikli yolda "kuyruğa yazıldı", kimliksiz yolda "2xx".
     * @param silent teslim sonucunu hiç bildirmeyen sink (çekirdek kapanmış): tur zaman aşımıyla kapanmalı.
     */
    private class FakeSink(
        private val consent: Consent,
        private val delivered: Boolean = true,
        private val silent: Boolean = false,
    ) : AttributionSink {
        var identified: Map<String, Any?>? = null
        var anonymous: Map<String, Any?>? = null
        override fun withConsent(task: (Consent) -> Unit) {
            task(consent)
        }
        override fun sendIdentified(fields: Map<String, Any?>, onDelivered: (Boolean) -> Unit) {
            identified = fields
            if (!silent) onDelivered(delivered)
        }
        override fun sendAnonymous(fields: Map<String, Any?>, onDelivered: (Boolean) -> Unit) {
            anonymous = fields
            if (!silent) onDelivered(delivered)
        }
    }

    private fun run(
        consent: Consent,
        play: Map<String, Any?>? = null,
        meta: Map<String, Any?>? = null,
        gaid: String? = null,
        installSource: String? = null,
        metaBlocks: CountDownLatch? = null,
        metaTimeoutMs: Long = 5_000,
        delivered: Boolean = true,
        silent: Boolean = false,
        deliveryTimeoutMs: Long = 10_000,
    ): Pair<FakeSink, AttributionRun> {
        val sink = FakeSink(consent, delivered, silent)
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
            deliveryTimeoutMs = deliveryTimeoutMs,
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

    @Test fun `adSharing tek basina GAID'i kimliksiz uca sizdirmaz`() {
        // GAID cihaz kapsamlı ve kalıcı bir reklam kimliğidir: `/v1/attribution/anon` sözleşmesi "kimlik yok"
        // olduğundan, adSharing açık olsa bile kimliksiz gövdeye giremez (yoksa kayıtlar yeniden kimliklenir).
        val (sink, _) = run(
            Consent(adSharing = true),
            play = mapOf("install_referrer" to "P"),
            gaid = "38400000-8cf0-11bd-b23e-10b96e40000d",
        )
        assertNull("rızasız kimlikli uç kullanılamaz", sink.identified)
        assertEquals("P", sink.anonymous!!["play_install_referrer"])
        assertFalse("GAID kimliksiz gövdeye giremez", sink.anonymous!!.containsKey("gaid"))
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
            override fun sendIdentified(fields: Map<String, Any?>, onDelivered: (Boolean) -> Unit) =
                throw AssertionError("gönderilmemeliydi")
            override fun sendAnonymous(fields: Map<String, Any?>, onDelivered: (Boolean) -> Unit) =
                throw AssertionError("gönderilmemeliydi")
        }
        Attribution.start(ctx, null, silent)
    }

    @Test fun `teslim basarisizsa isaret yazilmaz ve sinyal yeniden gonderilir`() {
        val first = run(
            Consent(),
            meta = mapOf("install_referrer" to "M"),
            installSource = "com.android.vending",
            delivered = false,
        ).first
        assertEquals("M", first.anonymous!!["meta_install_referrer"])

        // İşaret yazılmadığı için aynı sinyaller yeniden okunup yeniden gönderilmeli:
        // tekrar gönderim, kaybolan atıftan iyidir.
        val second = run(
            Consent(),
            meta = mapOf("install_referrer" to "M"),
            installSource = "com.android.vending",
        ).first
        assertEquals("M", second.anonymous!!["meta_install_referrer"])
        assertEquals("com.android.vending", second.anonymous!!["install_source"])
    }

    @Test fun `Play isareti ancak teslimden sonra yazilir`() {
        run(Consent(), play = mapOf("install_referrer" to "P"), delivered = false)
        assertFalse(
            "teslim edilmemiş referrer işaretlenemez",
            AttributionState(dir).isDone("play_referrer"),
        )

        run(Consent(), play = mapOf("install_referrer" to "P"))
        assertTrue("teslimden sonra işaret yazılmalı", AttributionState(dir).isDone("play_referrer"))
    }

    @Test fun `teslim onayi hic gelmezse tur kapanir ve isaret yazilmaz`() {
        val (sink, run) = run(
            Consent(),
            play = mapOf("install_referrer" to "P"),
            silent = true,
            deliveryTimeoutMs = 200,
        )
        assertEquals("P", sink.anonymous!!["play_install_referrer"])
        assertTrue("tur kapanmalı", run.terminated())
        assertFalse(AttributionState(dir).isDone("play_referrer"))
    }
}
