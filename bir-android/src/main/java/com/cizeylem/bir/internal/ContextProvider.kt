package com.cizeylem.bir.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.hardware.display.DisplayManager
import com.cizeylem.bir.Consent
import java.util.Locale
import java.util.TimeZone

/**
 * Her event'e iliştirilen ortam bilgisi (SPEC §4). Rıza ve uygulama durumunu parametre alır, kendi okumaz;
 * böylece saf kalır. Değeri öğrenilemeyen alan `"unknown"` ile doldurulmaz, HİÇ yazılır: panelde sahte bir
 * kırılım kovası oluşmasın diye.
 */
internal class ContextProvider(app: Context, private val libVersion: String) {
    private val app: Context = app.applicationContext ?: app

    fun get(consent: Consent, appState: String): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["lib"] = "bir-android"
        m["lib_version"] = libVersion
        m["platform"] = DeviceForm.platform(app)
        m["device_type"] = DeviceForm.deviceType(app)
        m["os_name"] = "Android"
        m["os_version"] = Build.VERSION.RELEASE
        m["device_model"] = Build.MODEL
        // Ham değer: normalize etmek panelde marka kırılımını bozar.
        m["device_manufacturer"] = Build.MANUFACTURER
        m["locale"] = Locale.getDefault().toLanguageTag()
        m["timezone"] = TimeZone.getDefault().id
        m["app_state"] = appState
        // Süreç ömründe sabit olan alanlar önbellekten gelir: her event'te PackageManager IPC'si yapılmaz.
        appVersion?.let { m["app_version"] = it }
        appBuild?.let { m["app_build"] = it }
        screen()?.let {
            m["screen_width"] = it.first
            m["screen_height"] = it.second
        }
        networkType()?.let { m["network_type"] = it }
        installSource?.let { m["install_source"] = it }
        consentScope(consent)?.let { m["consent_scope"] = it }
        return m
    }

    // app_version / app_build / install_source süreç ömründe değişmez (değişen sürüm = yeni süreç).
    // network_type, ekran ve app_state dinamik kalır; onlar her çağrıda yeniden okunur.
    private val packageInfo by lazy { readPackageInfo() }

    // versionName null olabilir; "değeri olmayan alan hiç yazılmaz" kuralı gereği o zaman alan hiç yazılmaz.
    private val appVersion: String? by lazy { packageInfo?.versionName }

    private val appBuild: Long? by lazy { packageInfo?.let { versionCode(it) } }

    private val installSource: String? by lazy { readInstallSource() }

    private fun readPackageInfo() = try {
        app.packageManager.getPackageInfo(app.packageName, 0)
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun versionCode(i: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else i.versionCode.toLong()

    /** dp cinsinden, cihazın DOĞAL yönüne göre: yatay tutulurken de aynı değerler çıkar. */
    @Suppress("DEPRECATION")
    private fun screen(): Pair<Int, Int>? = try {
        // WindowManager Application bağlamından alınınca API 30+'da StrictMode IncorrectContextUse ihlali verir
        // (görsel olmayan bağlam). DisplayManager görsel bağlam istemez; varsayılan ekran aynı ekrandır.
        val dmgr = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dmgr.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val dm = android.util.DisplayMetrics()
        d.getRealMetrics(dm)
        val swapped = d.rotation == android.view.Surface.ROTATION_90 || d.rotation == android.view.Surface.ROTATION_270
        val w = if (swapped) dm.heightPixels else dm.widthPixels
        val h = if (swapped) dm.widthPixels else dm.heightPixels
        val density = if (dm.density > 0f) dm.density else 1f
        Math.round(w / density) to Math.round(h / density)
    } catch (_: Exception) {
        null
    }

    /** İzin yoksa null: kütüphane izni manifest'inde zorlamaz, ana uygulama verirse okunur. */
    @Suppress("DEPRECATION")
    private fun networkType(): String? = try {
        if (app.checkPermission(Manifest.permission.ACCESS_NETWORK_STATE, Process.myPid(), Process.myUid()) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            null
        } else {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = if (Build.VERSION.SDK_INT >= 23) cm.getNetworkCapabilities(cm.activeNetwork) else null
            when {
                Build.VERSION.SDK_INT < 23 -> {
                    // API 21-22'de NetworkCapabilities üzerinden aktif ağ okunamaz; eski API ile sınıflandır.
                    val ni = cm.activeNetworkInfo
                    when {
                        ni == null || !ni.isConnected -> "offline"
                        ni.type == ConnectivityManager.TYPE_WIFI -> "wifi"
                        ni.type == ConnectivityManager.TYPE_MOBILE -> "cellular"
                        ni.type == ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                        else -> "other"
                    }
                }
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Paket bulunamazsa/istisna olursa null: bilinmeyeni "other" ile karıştırmamak için alan yazılmaz. */
    private fun readInstallSource(): String? = try {
        val pm = app.packageManager
        val pkg: String? = if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(app.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(app.packageName)
        }
        when (pkg) {
            null -> "sideload"
            "com.android.vending" -> "play"
            "com.amazon.venezia" -> "amazon"
            "com.sec.android.app.samsungapps" -> "galaxy_store"
            "com.huawei.appmarket" -> "appgallery"
            "com.xiaomi.mipicks" -> "xiaomi"
            else -> "other"
        }
    } catch (_: Exception) {
        null
    }

    private fun consentScope(c: Consent): String? {
        val keys = ArrayList<String>(3)
        if (c.analytics) keys.add("analytics")
        if (c.attribution) keys.add("attribution")
        if (c.adSharing) keys.add("adSharing")
        return if (keys.isEmpty()) null else keys.joinToString(",")
    }
}
