package com.cizeylem.bir.attribution

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import com.google.android.finsky.externalreferrer.IGetInstallReferrerService
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Play Install Referrer'ı `installreferrer` kütüphanesi OLMADAN, AIDL ile okur (SPEC §7).
 * Protokol sabitleri installreferrer-2.2 bytecode'undan çıkarılmıştır; uydurma sabit yoktur.
 *
 * Sözleşme: `read` callback'i TAM BİR KEZ çağırır. Değer ham alanlar haritasıdır (yorumlama yok, şifre çözme
 * sunucuda); referrer alınamadıysa `null` (Play yok/eski, bağlanma reddi, kopma, zaman aşımı, istisna).
 * Hiçbir yol istisna sızdırmaz.
 *
 * Thread: `bindService` ve Binder çağrısı yalnız [executor]'da yapılır. `onServiceConnected` ana thread'de gelir,
 * orada hemen işçi thread'e geçilir; Binder çağrısı bloklayabilir.
 *
 * İşaret ("okundu") yalnız referrer GERÇEKTEN alındığında yazılır. Geçici başarısızlıklar (kopma, bağlanma reddi)
 * sonraki açılışta yeniden denenir; Play'siz cihazda bu yalnız ucuz bir `queryIntentServices`'tir.
 */
internal class PlayReferrerReader(
    context: Context,
    private val state: AttributionState,
    private val executor: ScheduledExecutorService,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val context: Context = context.applicationContext ?: context

    fun read(callback: (Map<String, Any?>?) -> Unit) {
        try {
            executor.execute {
                try {
                    start(callback)
                } catch (e: RuntimeException) {
                            }
            }
        } catch (e: RejectedExecutionException) {
            callback(null)
        }
    }

    private fun start(callback: (Map<String, Any?>?) -> Unit) {
        if (state.isDone(KEY_DONE)) return callback(null)

        val intent = Intent(ACTION).setComponent(ComponentName(PLAY_PACKAGE, SERVICE_CLASS))
        val pm = context.packageManager
        val services = pm.queryIntentServices(intent, 0)
        // Boş: Play yok (ya da paket görünürlüğü yok) => SERVICE_UNAVAILABLE; sessiz geç.
        val info = services?.firstOrNull()?.serviceInfo ?: return callback(null)
        // Paket ya da sürüm uyumsuz => FEATURE_NOT_SUPPORTED (kütüphanenin kontrolüyle aynı).
        if (info.packageName != PLAY_PACKAGE || info.name == null || !playCompatible()) return callback(null)

        val session = Session(callback)
        val bound = try {
            context.bindService(Intent(intent), session, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) { // PERMISSION_ERROR
            false
        }
        if (!bound) {
            session.finish(null) // SERVICE_UNAVAILABLE: bağlanma engelli
            return
        }
        session.bound = true
        // onServiceConnected/Disconnected hiç gelmezse (ölü süreç, null binding) callback asılı kalmasın.
        try {
            executor.schedule({ session.finish(null) }, timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            session.finish(null)
        }
    }

    @Suppress("DEPRECATION") // versionCode: kütüphane de int sürüm kodunu karşılaştırır (80837300 = Play 8.3.73).
    private fun playCompatible(): Boolean = try {
        context.packageManager.getPackageInfo(PLAY_PACKAGE, 0).versionCode >= MIN_PLAY_VERSION_CODE
    } catch (e: Exception) { // NameNotFoundException dahil
        false
    }

    private inner class Session(private val callback: (Map<String, Any?>?) -> Unit) : ServiceConnection {
        private val finished = AtomicBoolean(false)
        @Volatile var bound = false

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // Ana thread'deyiz: Binder çağrısını işçiye devret.
            try {
                executor.execute {
                    try {
                        fetch(service)
                    } catch (e: RuntimeException) {
                        finish(null)
                    }
                }
            } catch (e: RejectedExecutionException) {
                finish(null)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = finish(null)
        override fun onBindingDied(name: ComponentName?) = finish(null)
        override fun onNullBinding(name: ComponentName?) = finish(null)

        private fun fetch(service: IBinder?) {
            if (finished.get() || service == null) return finish(null)
            val request = Bundle().apply { putString(KEY_PACKAGE_NAME, context.packageName) }
            val reply: Bundle? = try {
                IGetInstallReferrerService.Stub.asInterface(service).getInstallReferrer(request)
            } catch (e: RemoteException) {
                null
            }
            if (reply == null) return finish(null)

            val fields = LinkedHashMap<String, Any?>()
            for (key in FIELD_KEYS) {
                @Suppress("DEPRECATION") // tip belirsiz get(): değer olduğu gibi (ham) taşınır
                if (reply.containsKey(key)) fields[key] = reply.get(key)
            }
            // Yazılamayan işaret referrer'ı atmak için sebep değil: göndermek, bir kez daha okumaktan iyidir.
            try { state.markDone(KEY_DONE) } catch (e: IOException) { }
            finish(fields)
        }

        fun finish(result: Map<String, Any?>?) {
            if (!finished.compareAndSet(false, true)) return
            if (bound) {
                try { context.unbindService(this) } catch (e: RuntimeException) { }
            }
            try { callback(result) } catch (e: RuntimeException) { }
        }
    }

    internal companion object {
        const val KEY_DONE = "play_referrer"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val ACTION = "com.google.android.finsky.BIND_GET_INSTALL_REFERRER_SERVICE"
        const val PLAY_PACKAGE = "com.android.vending"
        const val SERVICE_CLASS = "com.google.android.finsky.externalreferrer.GetInstallReferrerService"
        const val MIN_PLAY_VERSION_CODE = 80837300
        const val KEY_PACKAGE_NAME = "package_name"
        val FIELD_KEYS = listOf(
            "install_referrer",
            "referrer_click_timestamp_seconds",
            "referrer_click_timestamp_server_seconds",
            "install_begin_timestamp_seconds",
            "install_begin_timestamp_server_seconds",
            "google_play_instant",
            "install_version",
        )
    }
}
