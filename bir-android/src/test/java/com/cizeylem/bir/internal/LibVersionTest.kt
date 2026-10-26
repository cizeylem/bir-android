package com.cizeylem.bir.internal

import android.app.Application
import com.cizeylem.bir.BuildConfig
import com.cizeylem.bir.Configuration
import com.cizeylem.bir.Consent
import com.cizeylem.bir.Cizeylem
import com.cizeylem.bir.TestScheduler
import java.io.File
import java.util.Properties
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `context.lib_version` ile Maven'a giden sürüm tek kaynaktan (gradle.properties `VERSION_NAME`) gelir.
 *
 * Neyi yakalar / yakalamaz:
 * - İlk üç test aynı özellikten beslenir (VERSION_NAME -> proje sürümü -> BuildConfig); bu yüzden normal
 *   koşuda birbirinden ayrışamazlar. Değerleri ancak `-PVERSION_NAME=x` ile geçersiz kılma ya da build
 *   betiğindeki bağlantının kopması (ör. buildConfigField'ın başka değer alması) ayrıştırır; asıl işleri budur.
 * - Son test davranışsaldır: Core'un gönderdiği gerçek event gövdesindeki `lib_version`, BuildConfig ile eşit
 *   olmalı. Core'a elle sabit geri gelir ya da ContextProvider'a başka değer verilirse düşer; kaynak metnine bağlı değildir.
 * - Yakalamadıkları: yayınlanan POM'un sürümü ile AAR'ın içindeki değerin eşleşmesi (bunu publishToMavenLocal
 *   doğrulaması ve CI'daki tag/VERSION_NAME denetimi kapsar).
 */
// targetSdk 36 Java 21 ister; ortamda JDK 17 var, bu yüzden sandbox SDK 34'e sabitlenir.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibVersionTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    private val declared: String = Properties().also { p ->
        File(System.getProperty("bir.gradlePropertiesPath")!!).inputStream().use { p.load(it) }
    }.getProperty("VERSION_NAME")

    @After fun tearDown() {
        Cizeylem.shutdownForTest()
        File(app.noBackupFilesDir, "cizeylem").deleteRecursively()
    }

    @Test fun `lib_version gradle properties daki VERSION_NAME ile ayni`() {
        assertEquals(declared, BuildConfig.LIB_VERSION)
    }

    @Test fun `lib_version Maven yayin surumuyle ayni`() {
        assertEquals(System.getProperty("bir.publishVersion"), BuildConfig.LIB_VERSION)
    }

    @Test fun `surum semver biciminde`() {
        assertTrue(BuildConfig.LIB_VERSION, Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?""").matches(BuildConfig.LIB_VERSION))
    }

    @Test fun `Core in gonderdigi event gövdesindeki lib_version BuildConfig ile ayni`() {
        File(app.noBackupFilesDir, "cizeylem").deleteRecursively()
        val sch = TestScheduler()
        val sent = ArrayList<String>()
        Cizeylem.startForTest(
            Core(
                app = app,
                config = Configuration("ck_w_test"),
                now = { 1_700_000_000_000L },
                elapsed = { 10_000L },
                uuid = { "uuid-1" },
                scheduler = sch,
                mainPoster = { it() },
                sender = { body -> sent.add(body); Response(Outcome.OK, null) },
            ),
        )
        Cizeylem.setConsent(Consent(analytics = true))
        sch.run()
        Cizeylem.track("cz_surum_testi")
        sch.run()
        Cizeylem.flush()
        sch.run()
        val body = sent.joinToString("\n")
        assertTrue("event gönderilmedi: $body", body.contains("cz_surum_testi"))
        assertTrue(body, body.contains("\"lib_version\":\"${BuildConfig.LIB_VERSION}\""))
    }
}
