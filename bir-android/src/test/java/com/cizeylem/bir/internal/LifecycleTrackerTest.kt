package com.cizeylem.bir.internal

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// targetSdk 36 Java 21 ister; ortamda JDK 17 var, bu yüzden sandbox SDK 34'e sabitlenir.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LifecycleTrackerTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var dir: File
    private val events = ArrayList<Pair<String, Map<String, Any?>>>()
    private var backgrounds = 0

    @Before fun setUp() {
        dir = File.createTempFile("lifecycle", "").apply { delete(); mkdirs() }
        setVersion("1.0.0", 10)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun stateFile() = File(File(dir, "cizeylem"), "state")

    private fun setVersion(name: String, code: Long) {
        val info = shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName)
        info.versionName = name
        @Suppress("DEPRECATION")
        info.versionCode = code.toInt()
        info.longVersionCode = code
    }

    private fun tracker(existing: Boolean = false, into: MutableList<Pair<String, Map<String, Any?>>> = events) =
        LifecycleTracker(app, stateFile(), existing, { n, p -> into.add(n to p) }, { backgrounds++ })
            .also { it.register() }

    private fun names() = events.map { it.first }
    private fun prop(name: String) = events.first { it.first == name }.second

    @Test fun `ilk acilista cz_app_installed gonderilir`() {
        tracker()
        Robolectric.buildActivity(Activity::class.java).create().start().resume()
        // Sıra: installed önce, opened sonra (kurulum açılışın nedenidir).
        assertEquals(listOf("cz_app_installed", "cz_app_opened"), names())
        assertEquals(false, prop("cz_app_installed")["existing_install"])
    }

    @Test fun `devralma varsa existing_install true olur`() {
        tracker(existing = true)
        assertEquals(true, prop("cz_app_installed")["existing_install"])
    }

    @Test fun `ikinci acilista cz_app_installed tekrar gonderilmez`() {
        tracker()
        val second = ArrayList<Pair<String, Map<String, Any?>>>()
        tracker(into = second)
        assertEquals(listOf("cz_app_installed"), names())
        assertTrue(second.isEmpty())
    }

    @Test fun `surum degisince cz_app_updated onceki surumle gonderilir`() {
        tracker()
        setVersion("1.1.0", 11)
        val second = ArrayList<Pair<String, Map<String, Any?>>>()
        tracker(into = second)
        assertEquals(listOf("cz_app_updated"), second.map { it.first })
        assertEquals("1.0.0", second[0].second["previous_version"])
        assertEquals(10L, second[0].second["previous_build"])
        // Güncelleme durumu kalıcıdır: aynı sürümle yeniden açılış tekrar göndermez.
        val third = ArrayList<Pair<String, Map<String, Any?>>>()
        tracker(into = third)
        assertTrue(third.isEmpty())
    }

    @Test fun `her on plana gecis cz_app_opened uretir`() {
        tracker()
        events.clear()
        val a = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        a.pause().stop()
        a.start().resume()
        assertEquals(listOf("cz_app_opened", "cz_app_backgrounded", "cz_app_opened"), names())
    }

    @Test fun `arka plana gecince cz_app_backgrounded uretilir ve flush tetiklenir`() {
        tracker()
        val a = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        assertEquals(0, backgrounds)
        a.pause().stop()
        assertTrue(names().contains("cz_app_backgrounded"))
        assertEquals(1, backgrounds)
    }

    @Test fun `iki Activity arasinda gecis arka plan sayilmaz`() {
        tracker()
        events.clear()
        val a = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        // Android sırası: B başlar, ancak sonra A durur.
        val b = Robolectric.buildActivity(Activity::class.java).create().start()
        a.pause()
        b.resume()
        a.stop()
        assertEquals(listOf("cz_app_opened"), names())
        assertEquals(0, backgrounds)
        b.pause().stop()
        assertEquals(1, backgrounds)
    }

    @Test fun `ekran donmesi arka plan ve yeni acilis sayilmaz`() {
        tracker()
        events.clear()
        val a = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        a.configurationChange(Configuration(app.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })
        assertEquals(listOf("cz_app_opened"), names())
        assertEquals(0, backgrounds)
    }

    @Test fun `bozuk durum dosyasi kurulum olayi uretmez ve yeniden yazilir`() {
        stateFile().parentFile!!.mkdirs()
        stateFile().writeText("cop")
        tracker()
        assertTrue(events.isEmpty())
        val second = ArrayList<Pair<String, Map<String, Any?>>>()
        setVersion("1.1.0", 11)
        tracker(into = second)
        assertEquals(listOf("cz_app_updated"), second.map { it.first })
    }

    @Test fun `register erisilemez dizinde durum dosyasi okumasiyla dizin yaratmaz`() {
        val t = LifecycleTracker(app, File(dir, "yok/cizeylem/state"), false, { _, _ -> }, {})
        assertNull(t.readState())
        assertFalse(File(dir, "yok").exists())
    }
}
