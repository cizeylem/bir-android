package com.cizeylem.bir.internal

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * `platform` dağıtım yüzeyidir (hangi mağaza/uygulama), `device_type` form faktörüdür; ikisi karıştırılmaz.
 * Aynı APK her cihazda çalıştığı için değerler derleme anında değil çalışma anında tespit edilir.
 */
internal object DeviceForm {
    private const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"

    fun platform(c: Context): String = when {
        // Fire TV ayrı bir dağıtım kanalı (ayrı mağaza, GMS yok) ve leanback'i de bildirir; o yüzden önce bakılır.
        hasFeature(c, FIRE_TV_FEATURE) -> "fire_tv"
        isTelevision(c) -> "android_tv"
        else -> "android"
    }

    fun deviceType(c: Context): String = when {
        isTelevision(c) || platform(c) == "fire_tv" -> "tv"
        c.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
        else -> "mobile"
    }

    private fun isTelevision(c: Context): Boolean {
        if (hasFeature(c, PackageManager.FEATURE_LEANBACK)) return true
        // Yapılandırmadan okunur (UiModeManager değil): kaynak yapılandırması TV modunun tek doğru kaynağı.
        return (c.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun hasFeature(c: Context, name: String): Boolean =
        try {
            c.packageManager.hasSystemFeature(name)
        } catch (_: RuntimeException) {
            false
        }
}
