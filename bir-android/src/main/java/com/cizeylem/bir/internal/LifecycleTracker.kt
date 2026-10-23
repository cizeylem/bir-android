package com.cizeylem.bir.internal

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import java.io.File
import java.io.IOException

/**
 * Dört sistem event'ini üretir: `cz_app_installed`, `cz_app_updated`, `cz_app_opened`, `cz_app_backgrounded`.
 * `androidx.lifecycle` yerine kendi Activity sayacı: çekirdekte sıfır üçüncü taraf bağımlılık (SPEC §1).
 *
 * Durum dosyası: `<noBackupDir>/cizeylem/state`, iki satır `version=…` / `build=…`. Dosyanın varlığı "kurulum
 * görüldü" demektir; yeniden kurulumda noBackupFilesDir silindiği için kurulum yeniden sayılır (device_id ile tutarlı).
 *
 * `existingInstall` dışarıdan gelir: Amplitude devralma sonucunu bilen facade'dir, bu sınıf devralma zincirine girmez.
 * Rıza kapısı burada yok: facade, `analytics` rızası yokken tracker'ı hiç kurmaz; `onEvent`'te kapı bulunmaz.
 *
 * Tek iş parçacığı varsayımı: Activity callback'leri ana thread'de gelir, sayaç kilitsizdir.
 */
internal class LifecycleTracker(
    private val app: Application,
    private val stateFile: File,
    private val existingInstall: Boolean,
    private val onEvent: (String, Map<String, Any?>) -> Unit,
    private val onBackground: () -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) : Application.ActivityLifecycleCallbacks {

    private var started = 0
    // Ekran dönmesinde eski Activity yeni olan başlamadan durur: sayaç 1→0→1 olur. Sayaç yapılandırma değişikliği
    // yüzünden 0'a inerse zaman damgası tutulur; CONFIG_WINDOW_MS içinde gelen 0→1 dönme sayılır, "açılış" üretilmez.
    // Bayrak yerine damga: yeni Activity hiç gelmezse (kullanıcı o sırada Home'a bastı) damga eskir ve sonraki gerçek
    // ön plana geçiş yutulmaz. Bedeli: o durumda cz_app_backgrounded kaçar; flush 30 sn'lik zamanlayıcıyla da tetiklenir.
    private var configStopAt: Long? = null
    private var registered = false
    // Durum yazımı başarısızsa bu süreçte kurulum/güncelleme event'ini tekrarlamayız. Süreçler arası tekrarı
    // (yazma kalıcı olarak başarısızsa her açılışta) önleyemeyiz; kalıcılık yokken yapılacak bir şey yok.
    private var writeFailed = false

    /**
     * Süreç başına bir kez çağrılır. Kurulum/güncelleme kontrolünü burada yapar; ilk Activity'den ÖNCE çalıştığı için
     * sıralama kendiliğinden `installed` → `opened` olur: ilk açılış hem kurulum hem açılıştır, ve kurulum açılışın
     * nedeni olduğundan önce gelir (panelde "kurulumdan sonra ilk açılış" doğal sırayla okunur).
     *
     * SÖZLEŞME: `Application.onCreate` içinde, ilk Activity başlamadan ÖNCE çağrılmalıdır. Geç çağrılırsa ilk
     * `cz_app_opened` kaçar ve sayaç yanlış kalır (çalışan Activity'yi görmediği için 1→0 geçişi hiç oluşmaz).
     */
    fun register() {
        if (!registered) {
            registered = true
            app.registerActivityLifecycleCallbacks(this)
        }
        checkInstallOrUpdate()
    }

    // Facade'den gelen istisna uygulamanın Activity callback'inde çökmeye gitmemeli.
    private fun emit(name: String, props: Map<String, Any?>) {
        try { onEvent(name, props) } catch (_: RuntimeException) {}
    }

    internal data class State(val version: String, val build: Long)

    /** Dosya yok ya da bozuksa null. Diske yazmaz, dizin yaratmaz. */
    internal fun readState(): State? {
        val text = try {
            if (!stateFile.isFile) return null
            stateFile.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
        val m = HashMap<String, String>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val i = line.indexOf('=')
            if (i <= 0) return null
            if (m.put(line.substring(0, i), line.substring(i + 1)) != null) return null
        }
        val version = m["version"] ?: return null
        val build = m["build"]?.toLongOrNull() ?: return null
        return State(version, build)
    }

    private fun checkInstallOrUpdate() {
        if (writeFailed) return
        val current = currentVersion() ?: return // Sürüm okunamıyorsa yanlış event üretmektense sessiz kal.
        val existed = stateFile.isFile
        val previous = readState()
        when {
            !existed -> emit("cz_app_installed", mapOf("existing_install" to existingInstall))
            // Bozuk dosya: kurulum zaten görülmüş, sürümü bilmiyoruz. Ne installed ne updated; yalnız onar.
            previous == null -> Unit
            previous != current -> emit(
                "cz_app_updated",
                mapOf("previous_version" to previous.version, "previous_build" to previous.build),
            )
            else -> return
        }
        try {
            atomicWrite(stateFile, "version=${current.version}\nbuild=${current.build}\n".toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            writeFailed = true
        } catch (_: RuntimeException) { // SecurityException dahil
            writeFailed = true
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVersion(): State? = try {
        val i = app.packageManager.getPackageInfo(app.packageName, 0)
        val build = if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else i.versionCode.toLong()
        // Satır sonu biçimi bozardı.
        i.versionName?.let { State(it.replace('\n', ' '), build) }
    } catch (_: Exception) {
        null
    }

    override fun onActivityStarted(activity: Activity) {
        if (++started == 1) {
            val stop = configStopAt
            configStopAt = null // her durumda temizlenir, takılı kalmaz
            val rotation = stop != null && now() - stop <= CONFIG_WINDOW_MS
            if (!rotation) emit("cz_app_opened", emptyMap())
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (started > 0 && --started == 0) {
            if (activity.isChangingConfigurations) {
                configStopAt = now()
            } else {
                configStopAt = null
                emit("cz_app_backgrounded", emptyMap())
                try { onBackground() } catch (_: RuntimeException) {}
            }
        }
    }

    private companion object {
        const val CONFIG_WINDOW_MS = 1000L
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
