package com.cizeylem.bir.attribution

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.cizeylem.bir.AttributionSink
import com.cizeylem.bir.Cizeylem
import com.cizeylem.bir.Consent
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CizeylemAttr"

/**
 * Attribution modülünün TEK genel giriş noktası (SPEC §7).
 *
 * `Cizeylem.initialize`'dan SONRA çağrılır; çekirdek kurulmamışsa hiçbir şey yapmaz (ne thread açar ne de
 * sinyal okur — gidecek yer yokken "okundu" işaretini yakmak sinyali kalıcı olarak kaybettirirdi).
 *
 * `@JvmStatic` + `@JvmOverloads`: Java'dan `Attribution.initialize(app)` ya da
 * `Attribution.initialize(app, "123456789")` biçiminde çağrılabilir.
 *
 * @param facebookAppId Meta install referrer için Facebook App ID. Verilmezse Meta kaynağı atlanır
 *   (otomatik tespit YOK: yanlış tespit sessizce boş sonuç üretirdi, bkz. [MetaReferrer]).
 */
object Attribution {

    @JvmStatic
    @JvmOverloads
    fun initialize(app: Application, facebookAppId: String? = null) {
        start(app, facebookAppId, Cizeylem)
    }

    internal fun start(context: Context, facebookAppId: String?, sink: AttributionSink) {
        val app = context.applicationContext ?: context
        // Rıza çekirdeğin thread'inde, bootstrap'tan SONRA gelir; çekirdek yoksa bu blok HİÇ çalışmaz.
        sink.withConsent { consent ->
            AttributionRun(
                context = app,
                sink = sink,
                noBackupDir = {
                    try {
                        app.noBackupFilesDir
                    } catch (e: RuntimeException) {
                        null
                    }
                },
                collectInstallSource = { installSource(app) },
                collectMeta = { MetaReferrer.read(app, facebookAppId) },
                collectPlay = { worker, timer, callback ->
                    PlayReferrerReader(app, { noBackupFilesDirOf(app) }, worker, timer).read(callback)
                },
                collectGaid = { c -> AdvertisingId.read(app, c) },
            ).start(consent)
        }
    }

    private fun noBackupFilesDirOf(context: Context): File? = try {
        context.noBackupFilesDir
    } catch (e: RuntimeException) {
        null
    }
}

/**
 * Mağaza kaynağı (SPEC §7): `install_source`. API 30+ `getInstallSourceInfo`, altında
 * `getInstallerPackageName`. İkisi de istisna atabilir (paket bulunamadı, görünürlük kısıtı); o zaman
 * alan hiç yazılmaz — "bilmiyorum" ile "yan yüklendi" aynı şey değildir.
 */
internal fun installSource(context: Context): String? = try {
    val pm = context.packageManager
    if (Build.VERSION.SDK_INT >= 30) {
        pm.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        pm.getInstallerPackageName(context.packageName)
    }
} catch (e: Exception) {
    Log.w(TAG, "install_source okunamadı: ${e.javaClass.simpleName}")
    null
}

/**
 * Tek bir attribution turu: topla → birleştir → rıza kademesine göre gönder → işaretle → kapat.
 *
 * EXECUTOR SAHİPLİĞİ (Görev 12'de askıda kalmıştı): iki executor'ın sahibi BURASIDIR ve tur bitince
 * `shutdown()` edilir. Daha önce `PlayReferrer` nesnesi süreç ömrü boyunca açık kalan iki statik thread
 * tutuyordu; attribution ömür boyu bir kez koşan bir iştir, iki thread'i sonsuza dek tutmasının gerekçesi
 * yoktu. İkisi de daemon: kapatma bir yoldan kaçsa bile JVM'in kapanmasını geciktirmezler.
 *  - `cizeylem-attr`: orkestrasyon + Play'in Binder işi + GAID bind'ı (bloklar).
 *  - `cizeylem-attr-cb`: Play'in zaman aşımı ve callback teslimi. Ayrı olmalı: işçi `transact`'ta
 *    takılırsa zaman aşımı yine de tam zamanında çalışmalı.
 * Meta okuması ÜÇÜNCÜ, tek kullanımlık bir thread'de yapılır: başka süreçteki provider `query` içinde
 * süresiz bloklanabilir ve bunu bekleyen thread kesilerek kurtarılamaz; bekleyen (işçi) zaman aşımıyla
 * vazgeçer, bloklanan thread ise daemon olarak bırakılır.
 *
 * TEK ATIŞ: her sinyalin kendi "okundu" işareti vardır. Yalnız bu turda YENİ okunan sinyaller gövdeye girer;
 * yeni sinyal yoksa hiç gönderim yapılmaz (her açılışta aynı `install_source`'u yollamak gürültü olurdu).
 * GAID bir "sinyal" değil, gönderilecek gövdeye eşlik eden bir alandır: gönderilecek bir şey yoksa GMS'e
 * bağlanılmaz bile.
 */
internal class AttributionRun(
    private val context: Context,
    private val sink: AttributionSink,
    private val noBackupDir: () -> File?,
    private val collectInstallSource: () -> String?,
    private val collectMeta: () -> Map<String, Any?>?,
    private val collectPlay: (ScheduledExecutorService, ScheduledExecutorService, (Map<String, Any?>?) -> Unit) -> Unit,
    private val collectGaid: (Consent) -> String?,
    private val metaTimeoutMs: Long = META_TIMEOUT_MS,
) {
    private val worker = daemon("cizeylem-attr")
    private val timer = daemon("cizeylem-attr-cb")
    private val finished = AtomicBoolean(false)

    // Yalnız [worker] thread'inden yazılır. Tek istisna, executor kapanmışken devreye giren
    // RejectedExecutionException yolu: orada en kötü ihtimalle bayat (boş) okunur ve gönderim atlanır.
    private val fields = LinkedHashMap<String, Any?>()
    private val marks = ArrayList<String>()

    /** Kapatmanın İSTENDİĞİ; testler turun executor bırakmadığını böyle doğrular. */
    internal fun terminated(): Boolean = worker.isShutdown && timer.isShutdown

    fun start(consent: Consent, onDone: (() -> Unit)? = null) {
        try {
            worker.execute { collect(consent, onDone) }
        } catch (e: RejectedExecutionException) {
            finish(consent, onDone)
        }
    }

    private fun collect(consent: Consent, onDone: (() -> Unit)?) {
        try {
            val state = state()
            if (!state.isDone(KEY_INSTALL_SOURCE)) {
                collectInstallSource()?.let {
                    fields["install_source"] = it
                    marks += KEY_INSTALL_SOURCE
                }
            }
            if (!state.isDone(KEY_META)) {
                readMetaWithTimeout()?.let {
                    for ((k, v) in it) fields["meta_$k"] = v
                    marks += KEY_META
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "sinyal toplama başarısız: ${e.javaClass.simpleName}")
        }
        // Play ASENKRON ve callback [timer] thread'inde gelir: orada ağır iş yapılmaz, hemen işçiye geçilir.
        try {
            collectPlay(worker, timer) { play ->
                try {
                    worker.execute { merge(play, consent, onDone) }
                } catch (e: RejectedExecutionException) {
                    finish(consent, onDone)
                }
            }
        } catch (e: RuntimeException) {
            merge(null, consent, onDone)
        }
    }

    private fun merge(play: Map<String, Any?>?, consent: Consent, onDone: (() -> Unit)?) {
        if (play != null) for ((k, v) in play) fields["play_$k"] = v
        // Play'in işaretini PlayReferrerReader kendi yazar (yalnız gerçekten okuduğunda); burada yazılmaz.
        finish(consent, onDone)
    }

    private fun finish(consent: Consent, onDone: (() -> Unit)?) {
        if (!finished.compareAndSet(false, true)) return
        // Sahiplik alındı: bundan sonra hangi yoldan çıkılırsa çıkılsın kapatma ve onDone GARANTİ (finally).
        try {
            if (fields.isNotEmpty()) {
                collectGaid(consent)?.let { fields["gaid"] = it }
                route(consent)
                markAll()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "attribution turu başarısız: ${e.javaClass.simpleName}")
        } finally {
            // shutdown() bloklamaz ve çalışmakta olan bu görevin bitmesine izin verir.
            timer.shutdown()
            worker.shutdown()
            try {
                onDone?.invoke()
            } catch (e: RuntimeException) {
            }
        }
    }

    /**
     * Rıza katmanlılığı (SPEC §5): kalıcı kimliği açan TEK anahtar `analytics`'tir. `attribution` onun
     * üstüne eklenen kademedir; tek başına kimlikli gönderimi açmaz. `adSharing` yalnız GAID kapısıdır,
     * gönderim yolunu hiç etkilemez.
     */
    private fun route(consent: Consent) {
        if (consent.analytics && consent.attribution) sink.sendIdentified(fields) else sink.sendAnonymous(fields)
    }

    private fun markAll() {
        val state = state()
        for (key in marks) {
            try {
                state.markDone(key)
            } catch (e: IOException) {
                // Yazılamayan işaret sinyali atmak için sebep değil: en kötü ihtimalle bir kez daha gönderilir.
                Log.w(TAG, "işaret yazılamadı: $key")
            } catch (e: RuntimeException) {
                Log.w(TAG, "işaret yazılamadı: $key")
            }
        }
    }

    private fun state() = AttributionState(noBackupDir())

    /**
     * Meta okuması SENKRON ve başka süreçte bloklanabilir; çağıran thread'in zaman aşımına güvenilemez
     * (Kontrolcü kararı 7). Okuma tek kullanımlık bir thread'e verilir, bu thread sınırlı süre bekler.
     * Zaman aşımında `cancel(true)` Binder'da bloklanmış bir çağrıyı GERÇEKTEN kesemez; o thread daemon
     * olarak bırakılır ve provider cevap verince kendiliğinden ölür.
     */
    private fun readMetaWithTimeout(): Map<String, Any?>? {
        val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "cizeylem-attr-meta").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
        return try {
            val future = exec.submit<Map<String, Any?>?> { collectMeta() }
            try {
                future.get(metaTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                Log.w(TAG, "Meta install referrer zaman aşımına uğradı; atlandı")
                null
            } catch (e: Exception) {
                null
            }
        } finally {
            // shutdownNow DEĞİL: bloklanmış görevi kesmek işe yaramaz, kesme bayrağı yalnız kafa karıştırır.
            exec.shutdown()
        }
    }

    private companion object {
        const val KEY_META = "meta_referrer"
        const val KEY_INSTALL_SOURCE = "install_source"
        // Seçimimizdir: provider sorgusu normalde milisaniyeler sürer; 10 sn yavaş cihaza pay bırakır ama
        // turun (ve dolayısıyla Play okumasının) asılı kalmasını sınırlar.
        const val META_TIMEOUT_MS = 10_000L

        fun daemon(name: String): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, name).apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            }
    }
}
