package com.cizeylem.bir.attribution

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import java.io.File
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Play Install Referrer'ı `installreferrer` kütüphanesi OLMADAN okur (SPEC §7).
 *
 * AIDL dosyası YOK, wire protokolü elle kurulur: AIDL, Google'ın paket adında
 * (`com.google.android.finsky.externalreferrer.IGetInstallReferrerService`) bir sınıf üretirdi; tüketici
 * uygulama `installreferrer` AAR'ını da alırsa (Firebase, Adjust vb.) "Duplicate class" ile derleme kırılırdı.
 * Wire uyumluluğunu sınıf adı değil, Parcel'a yazılan descriptor STRING'i ve transaction kodu sağlar.
 * Protokol sabitleri installreferrer-2.2 bytecode'undan çıkarılmıştır; uydurma sabit yoktur.
 *
 * Sözleşme: `read` callback'i TAM BİR KEZ çağırır. Değer ham alanlar haritasıdır (yorumlama yok, şifre çözme
 * sunucuda); referrer alınamadıysa `null` (Play yok/eski, bağlanma reddi, kopma, zaman aşımı, istisna).
 * Hiçbir yol istisna sızdırmaz: tüm akış tek bir `finished` bayrağından geçer, hata `finish(null)` olur.
 *
 * Thread: disk, `queryIntentServices`, `bindService` ve Binder çağrısı yalnız [worker]'da. `onServiceConnected`
 * ana thread'de gelir, orada hemen işçiye geçilir. Zaman aşımı ve callback teslimi AYRI [timer] thread'indedir:
 * `transact` takılırsa işçi tıkanır; zaman aşımı ve callback aynı thread'e konsaydı tam gerektiği anda çalışamazdı.
 * Callback her yoldan (başarı, kopma, zaman aşımı, hata) hep [timer] thread'inde çağrılır; Görev 14 buna güvenebilir.
 *
 * İşaret ("okundu") yalnız referrer GERÇEKTEN alındığında yazılır. Geçici başarısızlıklar (kopma, bağlanma reddi)
 * sonraki açılışta yeniden denenir; Play'siz cihazda bu yalnız ucuz bir `queryIntentServices`'tir.
 */
internal class PlayReferrerReader(
    context: Context,
    private val noBackupDir: () -> File?,
    private val worker: ScheduledExecutorService,
    private val timer: ScheduledExecutorService,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val context: Context = context.applicationContext ?: context

    fun read(callback: (Map<String, Any?>?) -> Unit) {
        val call = Call(callback)
        try {
            worker.execute {
                try {
                    call.start()
                } catch (e: RuntimeException) {
                    call.finish(null)
                }
            }
        } catch (e: RejectedExecutionException) {
            call.finish(null)
        }
    }

    /** Tek bir `read` çağrısının ömrü: bayrak, bağlantı ve zaman aşımı burada. */
    private inner class Call(private val callback: (Map<String, Any?>?) -> Unit) : ServiceConnection {
        private val finished = AtomicBoolean(false)
        @Volatile private var bindAttempted = false
        @Volatile private var timeout: ScheduledFuture<*>? = null

        fun start() {
            val state = AttributionState(noBackupDir())
            if (state.isDone(KEY_DONE)) return finish(null)

            val intent = Intent(ACTION).setComponent(ComponentName(PLAY_PACKAGE, SERVICE_CLASS))
            val services = context.packageManager.queryIntentServices(intent, 0)
            // Boş: Play yok (ya da paket görünürlüğü yok) => SERVICE_UNAVAILABLE; sessiz geç.
            val info = services?.firstOrNull()?.serviceInfo ?: return finish(null)
            // Paket ya da sürüm uyumsuz => FEATURE_NOT_SUPPORTED (kütüphanenin kontrolüyle aynı).
            if (info.packageName != PLAY_PACKAGE || info.name == null || !playCompatible()) return finish(null)

            // Zaman aşımı bind'dan ÖNCE kurulur: bağlanma hiç sonuçlanmazsa (ölü süreç, null binding) asılı kalınmaz.
            try {
                timeout = timer.schedule({ finish(null) }, timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: RejectedExecutionException) {
                return finish(null)
            }
            // Bayrak bind'dan önce: onServiceDisconnected/onNullBinding araya girerse finish unbind'ı atlamasın.
            bindAttempted = true
            val bound = try {
                context.bindService(Intent(intent), this, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) { // PERMISSION_ERROR
                false
            }
            if (!bound) {
                finish(null) // SERVICE_UNAVAILABLE: bağlanma engelli
            } else if (finished.get()) {
                // bindService dönene kadar zaman aşımı/kopma bitirdi: o finish'in unbind'ı henüz kayıtlı olmayan
                // bağlantıya denk geldi ve etkisizdi; bağlantı burada bırakılmazsa sızar.
                try { context.unbindService(this) } catch (e: RuntimeException) { }
            }
        }

        @Suppress("DEPRECATION") // versionCode: kütüphane de int sürüm kodunu karşılaştırır (80837300 = Play 8.3.73).
        private fun playCompatible(): Boolean = try {
            context.packageManager.getPackageInfo(PLAY_PACKAGE, 0).versionCode >= MIN_PLAY_VERSION_CODE
        } catch (e: Exception) { // NameNotFoundException dahil
            false
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // Ana thread'deyiz: Binder çağrısını işçiye devret.
            try {
                worker.execute {
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
                transact(service, request)
            } catch (e: RemoteException) {
                null
            }
            if (reply == null) return finish(null)

            val fields = LinkedHashMap<String, Any?>()
            for (key in FIELD_KEYS) {
                @Suppress("DEPRECATION") // tip belirsiz get(): değer olduğu gibi (ham) taşınır
                if (reply.containsKey(key)) fields[key] = reply.get(key)
            }
            // Sahipliği ÖNCE al, sonra işareti yaz: kontrol-sonra-yaz penceresinde zaman aşımı callback'e null verip
            // işareti "okundu" bırakırdı ve referrer bir daha hiç okunmazdı. Kaybeden hiçbir şey yazmaz.
            if (!finished.compareAndSet(false, true)) return
            // Sahiplik alındı: bundan sonra HİÇBİR yoldan teslimsiz çıkılmaz (finish artık no-op, callback hiç gelmezdi).
            // Yazılamayan işaret (IOException ya da SecurityException gibi RuntimeException) referrer'ı atmak için
            // sebep değil: göndermek, bir kez daha okumaktan iyidir.
            try {
                AttributionState(noBackupDir()).markDone(KEY_DONE)
            } catch (e: IOException) {
            } catch (e: RuntimeException) {
            } finally {
                complete(fields)
            }
        }

        /**
         * AIDL'nin `in Bundle` / `Bundle` dönüşlü ürettiği kodun aynısı: istek `token + writeInt(1) + Bundle`,
         * yanıt `readException + readInt + Bundle`. Senkron (flags 0). Parcel'lar finally'de geri verilir.
         */
        private fun transact(service: IBinder, request: Bundle): Bundle? {
            val data = Parcel.obtain()
            val replyParcel = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(1)
                request.writeToParcel(data, 0)
                service.transact(TRANSACTION_GET_INSTALL_REFERRER, data, replyParcel, 0)
                replyParcel.readException()
                return if (replyParcel.readInt() != 0) Bundle.CREATOR.createFromParcel(replyParcel) else null
            } finally {
                replyParcel.recycle()
                data.recycle()
            }
        }

        fun finish(result: Map<String, Any?>?) {
            if (!finished.compareAndSet(false, true)) return
            complete(result)
        }

        /** Sahiplenmiş (finished = true) çağıranlar için: temizlik ve teslim. */
        private fun complete(result: Map<String, Any?>?) {
            timeout?.cancel(false)
            // Her zaman unbind dene (bound bayrağına güvenme); bağlı değilse atılan istisna yutulur.
            if (bindAttempted) {
                try { context.unbindService(this) } catch (e: RuntimeException) { }
            }
            // Teslim [timer]'da: işçi takılı olsa da callback gelir, thread her yolda aynıdır.
            try {
                timer.execute { try { callback(result) } catch (e: RuntimeException) { } }
            } catch (e: RejectedExecutionException) {
                try { callback(result) } catch (e: RuntimeException) { }
            }
        }
    }

    internal companion object {
        const val KEY_DONE = "play_referrer"
        // Zaman aşımı seçimimizdir (Play'de karşılığı yok): bağlanma normalde saniyenin altındadır; 15 sn,
        // yavaş açılan Play'e pay bırakır ama callback'in ve açık kalan bağlantının asılı kalmasını sınırlar.
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val DESCRIPTOR = "com.google.android.finsky.externalreferrer.IGetInstallReferrerService"
        const val TRANSACTION_GET_INSTALL_REFERRER = 1 // FIRST_CALL_TRANSACTION + 0 (installreferrer-2.2)
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
