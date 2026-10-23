package com.cizeylem.bir.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.cizeylem.bir.Consent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// targetSdk 36 Java 21 ister; ortamda JDK 17 var, bu yüzden sandbox SDK 34'e sabitlenir.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextProviderTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    private fun feature(name: String) {
        shadowOf(ctx.packageManager).setSystemFeature(name, true)
    }

    private fun get(consent: Consent = Consent(), state: String = "foreground") =
        ContextProvider(ctx, "1.2.3").get(consent, state)

    private fun installer(pkg: String?) {
        shadowOf(ctx.packageManager).setInstallSourceInfo(ctx.packageName, pkg, pkg)
    }

    @Test fun `leanback cihazda platform android_tv ve device_type tv`() {
        feature(PackageManager.FEATURE_LEANBACK)
        assertEquals("android_tv", DeviceForm.platform(ctx))
        assertEquals("tv", DeviceForm.deviceType(ctx))
        assertEquals("android_tv", get()["platform"])
        assertEquals("tv", get()["device_type"])
    }

    @Test @Config(sdk = [34], qualifiers = "television")
    fun `uiMode televizyon ise leanback ozelligi olmadan da android_tv`() {
        assertEquals("android_tv", DeviceForm.platform(ctx))
        assertEquals("tv", DeviceForm.deviceType(ctx))
    }

    @Test fun `fire_tv ozelligi leanback'in onune gecer`() {
        feature(PackageManager.FEATURE_LEANBACK)
        feature("amazon.hardware.fire_tv")
        assertEquals("fire_tv", DeviceForm.platform(ctx))
        assertEquals("tv", DeviceForm.deviceType(ctx))
    }

    @Test fun `telefonda platform android ve device_type mobile`() {
        assertEquals("android", DeviceForm.platform(ctx))
        assertEquals("mobile", DeviceForm.deviceType(ctx))
    }

    @Test @Config(sdk = [34], qualifiers = "sw600dp-w800dp-h1280dp")
    fun `600dp ustu ekranda device_type tablet`() {
        assertEquals("tablet", DeviceForm.deviceType(ctx))
        assertEquals("android", DeviceForm.platform(ctx))
    }

    @Test @Config(sdk = [34], qualifiers = "sw599dp-w599dp-h900dp")
    fun `599dp ekran tablet degil`() {
        assertEquals("mobile", DeviceForm.deviceType(ctx))
    }

    @Test fun `network izni yokken network_type alani hic yazilmaz`() {
        assertFalse(get().containsKey("network_type"))
    }

    @Test fun `network izni varken network_type yazilir`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        val v = get()["network_type"]
        assertTrue("beklenmeyen: $v", v in setOf("wifi", "cellular", "ethernet", "offline", "other"))
    }

    @Test fun `installer paket adi install_source degerine eslenir`() {
        val map = mapOf(
            "com.android.vending" to "play",
            "com.amazon.venezia" to "amazon",
            "com.sec.android.app.samsungapps" to "galaxy_store",
            "com.huawei.appmarket" to "appgallery",
            "com.xiaomi.mipicks" to "xiaomi",
        )
        for ((pkg, expected) in map) {
            installer(pkg)
            assertEquals(pkg, expected, get()["install_source"])
        }
    }

    @Test fun `bilinmeyen installer other olur, null sideload olur`() {
        installer("com.example.store")
        assertEquals("other", get()["install_source"])
        installer(null)
        assertEquals("sideload", get()["install_source"])
    }

    @Test @Config(sdk = [28])
    fun `API 30 alti getInstallerPackageName yolundan okunur`() {
        @Suppress("DEPRECATION")
        ctx.packageManager.setInstallerPackageName(ctx.packageName, "com.android.vending")
        assertEquals("play", get()["install_source"])
    }

    @Test fun `lib alani bir-android ve lib_version kurucudan gelir`() {
        val c = get()
        assertEquals("bir-android", c["lib"])
        assertEquals("1.2.3", c["lib_version"])
    }

    @Test fun `app_state ve sabit alanlar yazilir`() {
        val c = get(state = "background")
        assertEquals("background", c["app_state"])
        assertEquals("Android", c["os_name"])
        assertEquals(android.os.Build.MANUFACTURER, c["device_manufacturer"])
        assertEquals(android.os.Build.MODEL, c["device_model"])
        assertEquals(android.os.Build.VERSION.RELEASE, c["os_version"])
        assertTrue(c["timezone"] is String)
        assertTrue(c["locale"] is String)
    }

    @Test fun `consent_scope yalnizca verilen anahtarlari icerir`() {
        assertEquals("analytics", get(Consent(analytics = true))["consent_scope"])
        assertEquals("analytics,adSharing", get(Consent(analytics = true, adSharing = true))["consent_scope"])
        assertEquals("analytics,attribution,adSharing", get(Consent(true, true, true))["consent_scope"])
        assertEquals("attribution", get(Consent(attribution = true))["consent_scope"])
    }

    @Test fun `hic riza yokken consent_scope alani yazilmaz`() {
        assertFalse(get(Consent()).containsKey("consent_scope"))
    }

    @Test @Config(sdk = [34], qualifiers = "w360dp-h640dp-port-mdpi")
    fun `ekran olculeri dp ve dikeyde genislik kucuk`() {
        val c = get()
        assertEquals(360, c["screen_width"])
        assertEquals(640, c["screen_height"])
    }

    @Test @Config(sdk = [34], qualifiers = "w640dp-h360dp-land-mdpi")
    fun `yatay tutulurken de ayni dogal yon degerleri cikar`() {
        val c = get()
        assertEquals(360, c["screen_width"])
        assertEquals(640, c["screen_height"])
    }
}
