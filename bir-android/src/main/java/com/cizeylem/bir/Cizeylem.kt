package com.cizeylem.bir

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cizeylem.bir.internal.Core
import com.cizeylem.bir.internal.ThreadScheduler
import java.util.UUID

/**
 * SDK'nın tek genel giriş noktası (SPEC §3 "Kurulum"). Durum tutmaz; her çağrıyı o anki çekirdeğe iletir.
 *
 * Tüm çağrılar çağıran thread'de ANINDA döner: disk, Binder ve ağ işinin tamamı SDK'nın tek thread'ine
 * post edilir (SPEC §3 "Eşzamanlılık"). `initialize` çağrılmadan yapılan çağrılar sessizce yok sayılır —
 * analitik, ana uygulamayı çökertmenin gerekçesi olamaz.
 *
 * `@JvmStatic` + `@JvmOverloads`: Java'dan `Cizeylem.track("ad")` biçiminde çağrılabilir (SPEC §1).
 */
object Cizeylem : AttributionSink {

    @Volatile private var core: Core? = null

    /**
     * `Application.onCreate` içinde, ilk Activity başlamadan ÖNCE çağrılmalıdır: geç çağrılırsa ilk
     * `cz_app_opened` kaçar. İkinci çağrı öncekini kapatır (yapılandırma değişikliği senaryosu).
     */
    @JvmStatic
    fun initialize(app: Application, config: Configuration) {
        val mainHandler = Handler(Looper.getMainLooper())
        val scheduler = ThreadScheduler { message -> config.logger?.log("[cizeylem] $message") }
        start(
            Core(
                app = app,
                config = config,
                // Duvar saati: event `time`, `sent_at` ve `session_id` sunucuyla ortak zaman ekseninde.
                now = { System.currentTimeMillis() },
                // Monotonik saat: backoff ve dönme penceresi kullanıcı saatini geri alsa da bozulmaz.
                elapsed = { SystemClock.elapsedRealtime() },
                uuid = { UUID.randomUUID().toString() },
                scheduler = scheduler,
                mainPoster = { task -> mainHandler.post(Runnable { task() }) },
                sender = null,
            ),
        )
    }

    @JvmStatic
    @JvmOverloads
    fun track(name: String, props: Map<String, Any?>? = null) {
        core?.track(name, props)
    }

    /** Anonim kimliği `userId`'ye bağlar; bu çağrıdan SONRAKİ event'ler `user_id` ile gider. */
    @JvmStatic
    @JvmOverloads
    fun identify(userId: String, props: Map<String, Any?>? = null) {
        core?.identify(userId, props)
    }

    /** Bir sonraki event'e iliştirilecek `$set` özelliklerini biriktirir. */
    @JvmStatic
    fun setUserProperties(props: Map<String, Any?>) {
        core?.setUserProperties(props)
    }

    /** Yalnız ilk kez yazılacak `$set_once` özelliklerini biriktirir. */
    @JvmStatic
    fun setUserPropertiesOnce(props: Map<String, Any?>) {
        core?.setUserPropertiesOnce(props)
    }

    /** Sayısal `$add` artışlarını biriktirir; aynı anahtar birden çok kez verilirse toplanır. */
    @JvmStatic
    fun addUserProperties(props: Map<String, Number>) {
        core?.addUserProperties(props)
    }

    /** Silinecek `$unset` anahtarlarını biriktirir. */
    @JvmStatic
    fun unsetUserProperties(keys: List<String>) {
        core?.unsetUserProperties(keys)
    }

    /** Biriken özellikleri bir sonraki event'i beklemeden `$identify` olarak gönderir. */
    @JvmStatic
    @JvmOverloads
    fun sendUserProperties(userId: String? = null) {
        core?.sendUserProperties(userId)
    }

    /**
     * Rızayı kalıcı olarak kaydeder (SPEC §5). `analytics` kapatılırsa kuyruk, `device_id`, oturum ve
     * yaşam döngüsü durumu SİLİNİR; o andan sonraki event'ler toplanmaz.
     */
    @JvmStatic
    fun setConsent(consent: Consent) {
        core?.setConsent(consent)
    }

    /**
     * Geriye uyumluluk: `true` üç rıza anahtarını da kapatır. `false` yalnız `analytics`'i açar;
     * `attribution`/`adSharing` olduğu gibi kalır — reklam kimliği paylaşımı yalnız açık `setConsent`
     * ile açılabilir.
     */
    @JvmStatic
    fun setOptOut(optOut: Boolean) {
        core?.setOptOut(optOut)
    }

    /** `cz_deep_link_opened` üretir; link'ten yalnız `utm_*`, `click_id` ve `campaign_ids` okunur. */
    @JvmStatic
    fun handleDeepLink(url: String) {
        core?.handleDeepLink(url)
    }

    @JvmStatic
    fun flush() {
        core?.flush()
    }

    /** Yeni anonim kimlik üretir (çıkış yapıldığında). Sunucuya çağrı yapmaz. */
    @JvmStatic
    fun reset() {
        core?.reset()
    }

    /** O anki `distinct_id`; rıza yokken ya da kimlik henüz kurulmadıysa `null`. */
    @JvmStatic
    fun getDistinctId(): String? = core?.getDistinctId()

    @JvmStatic
    fun stats(): Stats = core?.stats() ?: Stats(0, 0, 0, false)

    // --- AttributionSink (attribution modülünün kapısı) -------------------------------------------
    //
    // BİLİNÇLİ KARAR (0.1.0): `Cizeylem` bu arayüzü uyguladığı için aşağıdaki üç üye genel yüzeyde
    // görünür — Kotlin'de bir arayüz uygulaması `internal` olamaz. Alternatifler (ayrı bir köprü nesnesi,
    // yansımayla bağlama) ya genel yüzeyi başka yerden büyütüyor ya da ProGuard'da kırılıyordu; rıza
    // açısından bir delik yok, çünkü üçü de çekirdeğin rıza kapısından geçer. Bu yüzden olduğu gibi
    // bırakıldı ve BELGELENDİ: README'deki genel API listesinde yer almazlar.

    /**
     * SDK'NIN İÇ KULLANIMI İÇİNDİR, KARARLI API DEĞİLDİR. Yalnız `bir-android-attribution` modülü çağırır;
     * uygulama kodundan çağrılması desteklenmez ve ikinci bir uyarı olmadan değişebilir.
     *
     * Attribution modülü işini buradan başlatır: iş çekirdeğin thread'inde, rıza okunduktan sonra çalışır.
     * `initialize` çağrılmamışsa HİÇ çalışmaz — gidecek yer yokken sinyal toplamak, "okundu" işaretini
     * boşa yakardı.
     */
    override fun withConsent(task: (Consent) -> Unit) {
        core?.withConsent(task)
    }

    /**
     * SDK'NIN İÇ KULLANIMI İÇİNDİR, KARARLI API DEĞİLDİR (bkz. yukarıdaki not).
     * Alanlar `cz_attribution` event'i olarak normal kuyruğa girer (kimlikli, `/v1/batch`).
     */
    override fun sendIdentified(fields: Map<String, Any?>, onDelivered: (Boolean) -> Unit) {
        // Çekirdek yoksa devir OLMADI: çağıran işareti yazmamalı, sinyal sonraki açılışta yeniden okunmalı.
        val c = core ?: return onDelivered(false)
        c.sendAttributionIdentified(fields, onDelivered)
    }

    /**
     * SDK'NIN İÇ KULLANIMI İÇİNDİR, KARARLI API DEĞİLDİR (bkz. yukarıdaki not).
     * Alanlar `/v1/attribution/anon`'a gider; gövdede hiçbir kimlik alanı (`device_id`/`user_id`/
     * `distinct_id`/`gaid`) bulunmaz.
     */
    override fun sendAnonymous(fields: Map<String, Any?>, onDelivered: (Boolean) -> Unit) {
        val c = core ?: return onDelivered(false)
        c.sendAttributionAnonymous(fields, onDelivered)
    }

    // --- testler için ------------------------------------------------------------------------------

    internal fun startForTest(core: Core) = start(core)

    internal fun shutdownForTest() {
        core?.shutdown()
        core = null
    }

    private fun start(next: Core) {
        core?.shutdown()
        core = next
        next.start()
    }
}
