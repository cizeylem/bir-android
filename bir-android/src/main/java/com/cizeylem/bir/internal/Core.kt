package com.cizeylem.bir.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.cizeylem.bir.BuildConfig
import com.cizeylem.bir.Configuration
import com.cizeylem.bir.Consent
import com.cizeylem.bir.Stats
import java.io.File
import java.io.IOException

/**
 * Dokuz parçanın (kuyruk, kimlik, oturum, rıza, context, taşıma, gönderim, yaşam döngüsü) birleştiği yer.
 * `Cizeylem` yalnız bu sınıfa yönlendiren ince bir cephedir; bütün durum ve kural burada.
 *
 * İki iş parçacığı vardır:
 *  - Çağıran thread (çoğunlukla ana thread): yalnız birkaç alan okunur/yazılır, DİSK/BİNDER/AĞ YOK (SPEC §3).
 *    Yaşam döngüsü callback'leri de buraya düşer; onlar da yalnız sayaç tutup işi `scheduler`'a post eder.
 *  - `scheduler` thread'i: dosya, PackageManager ve HTTP işinin tamamı.
 * Yalnız `@Volatile` alanlar iki thread'den okunur.
 *
 * İki ayrı saat kullanılır: `now` DUVAR saatidir (event `time`, `sent_at`, `session_id` — sunucuyla ortak
 * zaman ekseni), `elapsed` MONOTONİK saattir (gönderim backoff'u ve yaşam döngüsünün dönme penceresi).
 * Kullanıcı saati geri alırsa backoff ileride kalır ve dönme penceresi gerçek bir açılışı yutar.
 */
internal class Core(
    private val app: Application,
    private val config: Configuration,
    private val now: () -> Long,
    private val elapsed: () -> Long,
    private val uuid: () -> String,
    private val scheduler: Scheduler,
    private val mainPoster: (() -> Unit) -> Unit,
    private val sender: ((String) -> Response)? = null,
    private val random: () -> Double = { Math.random() },
    // Kimliksiz attribution ucu ayrı bir gönderici: aynı Transport sınıfı, farklı `path`. Testler HTTP'siz
    // sahte verebilsin diye fonksiyon olarak alınır (batch göndericisiyle aynı gerekçe).
    private val anonSender: ((String) -> Response)? = null,
) : Application.ActivityLifecycleCallbacks {

    // --- iki thread'den okunanlar ---------------------------------------------------------------

    @Volatile private var distinctIdCache: String? = null

    @Volatile private var statsCache = EMPTY_STATS

    // Yaşam döngüsü callback'leri ana thread'de, okunması scheduler thread'inde.
    @Volatile private var foreground = false

    @Volatile private var tracker: LifecycleTracker? = null

    // --- yalnız ana thread ----------------------------------------------------------------------

    // Rıza sonradan verilince tracker'ın sayacını mevcut durumla doldurmak için: aksi halde o anda
    // ekranda duran Activity hiç "başlamamış" sayılır ve ilk cz_app_opened kaçar.
    private val startedActivities = LinkedHashSet<Activity>()

    // --- yalnız scheduler thread'i --------------------------------------------------------------

    private var booted = false
    private var analytics = false
    private var dir: File? = null
    private var noBackupDir: File? = null
    private var consentStore: ConsentStore? = null
    private var consentFile: File? = null
    private var deviceIdStore: DeviceIdStore? = null
    private var sessionStore: SessionStore? = null
    private var queue: EventQueue? = null
    private var dispatcher: Dispatcher? = null
    private var contextProvider: ContextProvider? = null
    private var consent = Consent()
    private var deviceId: String? = null
    private var userId: String? = null

    // Kimlik üretilemediği için (erişilemez depo) düşen event'ler: stats()'ta görünür olsunlar,
    // yoksa SDK "çalışıyor" görünürken hiçbir şey toplamadığını kimse fark etmez.
    private var droppedNoIdentity = 0

    // Oturum ya da ortam sağlayıcısı kurulmadığı için düşen event'ler. Aynı sınıf: sessiz düşüş, SDK
    // "çalışıyor" görünürken hiçbir şey toplamadığını gizler. `droppedNoIdentity` ile aynı kovaya yazılır.
    private var droppedNoContext = 0

    private var pendingSet: LinkedHashMap<String, Any?>? = null
    private var pendingSetOnce: LinkedHashMap<String, Any?>? = null
    private var pendingUnset: ArrayList<String>? = null
    private var pendingAdd: LinkedHashMap<String, Number>? = null

    // --- yaşam döngüsü --------------------------------------------------------------------------

    /**
     * SÖZLEŞME: `Application.onCreate` içinden, ilk Activity başlamadan ÖNCE çağrılır.
     * Callback kaydı burada senkron yapılır (disk/Binder değil, listeye ekleme): rıza okuması
     * scheduler'da olduğu için tracker'ı beklersek ilk `cz_app_opened` kaçardı.
     */
    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        scheduler.post { bootstrap() }
        // 30 sn'lik periyodik gönderim burada; Dispatcher bilinçli olarak zamanlayıcı içermez.
        scheduler.schedulePeriodic(config.flushIntervalMs) { drain() }
    }

    fun shutdown() {
        try {
            app.unregisterActivityLifecycleCallbacks(this)
        } catch (_: RuntimeException) {
        }
        tracker = null
        scheduler.shutdown()
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities.add(activity)
        foreground = true
        tracker?.onActivityStarted(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities.remove(activity)
        foreground = startedActivities.isNotEmpty()
        tracker?.onActivityStopped(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        // onStop'u hiç gelmeyen Activity referansını tutmamak için (sızıntı).
        startedActivities.remove(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    // --- genel API'nin karşılıkları -------------------------------------------------------------

    fun track(name: String, props: Map<String, Any?>?) =
        enqueueAsync(name, props, null, null)

    fun identify(userId: String, props: Map<String, Any?>?) {
        // bir-js ile birebir: identify'ın prop'ları $set'e gider, event'in `props` alanı boş kalır.
        enqueueAsync(IDENTIFY, null, PropertyOps(props, null, null, null), userId)
    }

    fun setUserProperties(props: Map<String, Any?>) {
        val copy = LinkedHashMap(props)
        scheduler.post { pendingSet = merge(pendingSet, copy) }
    }

    fun setUserPropertiesOnce(props: Map<String, Any?>) {
        val copy = LinkedHashMap(props)
        scheduler.post { pendingSetOnce = merge(pendingSetOnce, copy) }
    }

    fun addUserProperties(props: Map<String, Number>) {
        val copy = LinkedHashMap(props)
        scheduler.post {
            val acc = pendingAdd ?: LinkedHashMap()
            // Aynı anahtar iki kez artırılırsa toplanır (bir-js ile aynı): son yazan kazanmaz.
            for ((k, v) in copy) {
                val prev = acc[k]
                acc[k] = if (prev == null) v else sum(prev, v)
            }
            pendingAdd = acc
        }
    }

    fun unsetUserProperties(keys: List<String>) {
        val copy = ArrayList(keys)
        scheduler.post {
            val acc = pendingUnset ?: ArrayList()
            acc.addAll(copy)
            pendingUnset = acc
        }
    }

    fun sendUserProperties(userId: String?) {
        val time = now()
        scheduler.post {
            if (!ready()) return@post
            // Gönderecek bir şey yoksa boş $identify üretmeyiz. Bekleyenler burada TÜKETİLMEZ:
            // tüketme, event'in kuyruğa gerçekten yazılacağı yere (write) bırakılır.
            if (!hasPending() && userId == null) return@post
            write(IDENTIFY, time, uuid(), null, null, userId)
        }
    }

    fun setConsent(consent: Consent) {
        // Sıcak yol hemen görsün: geri almayı disk yazımının bitmesine bırakmak, aradaki track'lerin
        // rızasız veri toplaması demek olurdu.
        if (!consent.analytics) distinctIdCache = null
        scheduler.post { applyConsent(consent) }
    }

    /**
     * Geriye uyumlu opt-out. Kapatma üç anahtarı da düşürür; açma yalnız `analytics`'i açar ve diğer
     * anahtarları olduğu gibi bırakır: `adSharing`'i eski bir `setOptOut(false)` çağrısıyla açmak,
     * kullanıcının hiç vermediği reklam kimliği rızasını varsaymak olurdu.
     */
    fun setOptOut(optOut: Boolean) {
        if (optOut) distinctIdCache = null
        scheduler.post {
            if (!booted) bootstrap()
            applyConsent(
                if (optOut) Consent(false, false, false) else Consent(true, consent.attribution, consent.adSharing),
            )
        }
    }

    fun handleDeepLink(url: String) {
        // Saf metin işi; disk/Binder yok, çağıran thread'de yapılabilir.
        track(DEEP_LINK, DeepLink.params(url))
    }

    fun flush() = scheduler.post { drain() }

    fun reset() {
        distinctIdCache = null
        scheduler.post {
            if (!ready()) return@post
            // Eski kullanıcıya ait bekleyen özellikler yeni anonim kimliğe iliştirilmemeli.
            clearPending()
            setUserId(null)
            val fresh = uuid()
            deviceId = try {
                deviceIdStore?.write(fresh)
                fresh
            } catch (e: IOException) {
                warn("yeni anonim kimlik yazılamadı: $e")
                null
            }
            sessionStore?.reset()
            distinctIdCache = deviceId
        }
    }

    fun getDistinctId(): String? = distinctIdCache

    fun stats(): Stats = statsCache

    // --- attribution modülünün kapısı (AttributionSink) ------------------------------------------

    /**
     * Rızayı çekirdek thread'inde, bootstrap'tan SONRA verir. Attribution modülünün tek tetikleyicisi budur.
     */
    fun withConsent(task: (Consent) -> Unit) {
        scheduler.post {
            if (!booted) bootstrap()
            task(consent)
        }
    }

    /**
     * Kimlikli attribution: alanlar normal event boru hattına `cz_attribution` olarak girer; böylece
     * `device_id`/`user_id`, context, kuyruk kalıcılığı ve backoff'lu tekrar bedavaya gelir.
     *
     * `analytics` o an kapalıysa kuyruk ve kimlik yoktur: sinyali sessizce düşürmek yerine kimliksiz uca
     * düşülür (rıza kademesi zaten modülde denetlendi; burası yarış durumuna karşı son savunma).
     */
    fun sendAttributionIdentified(fields: Map<String, Any?>, onDelivered: (Boolean) -> Unit) {
        val time = now()
        val snapshot = LinkedHashMap(fields)
        scheduler.post {
            if (ready() && queue != null) {
                // KALICI DEVİR = kuyruğa yazıldı. Gönderimin ağda başarılı olmasını beklemeyiz: kuyruk
                // dayanıklıdır ve kendi backoff'lu tekrarı var, oraya girmek sinyalin kaybolmayacağı andır.
                val queued = write(ATTRIBUTION, time, uuid(), snapshot, null, null)
                deliver(onDelivered, queued)
            } else {
                postAnonymous(time, snapshot, onDelivered)
            }
        }
    }

    /** Kimliksiz attribution: `/v1/attribution/anon`, gövdede hiçbir kimlik alanı yok (SPEC §7). */
    fun sendAttributionAnonymous(fields: Map<String, Any?>, onDelivered: (Boolean) -> Unit) {
        val time = now()
        val snapshot = LinkedHashMap(fields)
        scheduler.post { postAnonymous(time, snapshot, onDelivered) }
    }

    /** Geri çağrıdaki istisna SDK thread'ini düşürmemeli. */
    private fun deliver(onDelivered: (Boolean) -> Unit, result: Boolean) {
        try {
            onDelivered(result)
        } catch (e: RuntimeException) {
            warn("attribution teslim geri çağrısı başarısız: $e")
        }
    }

    /**
     * Kuyruk YOK: çekirdek rızasız kalıcı iz bırakmaz (SPEC §5), bu yüzden tek deneme ve diskte bekleyen
     * kopya yok. (Tek istisna çekirdeğin dışındadır: attribution modülü `attr_state` içine sinyal başına tek
     * bir "okundu" booleanı yazar — kimlik değildir; bkz. [com.cizeylem.bir.AttributionSink.sendAnonymous].)
     *
     * KALICI DEVİR yalnız **2xx**'tir: başka her sonuçta (ağ hatası, 4xx, 5xx) `false` bildirilir, modül
     * işareti yazmaz ve sinyal bir sonraki açılışta yeniden okunup yeniden gönderilir. Tekrar gönderim,
     * kaybolan atıftan iyidir.
     */
    private fun postAnonymous(sentAt: Long, fields: Map<String, Any?>, onDelivered: (Boolean) -> Unit) {
        if (fields.isEmpty()) return deliver(onDelivered, false)
        if (!booted) bootstrap()
        val send = anonSender ?: Transport(config.endpoint, config.writeKey, ANON_PATH)::send
        val body = EventJson.attributionAnon(sentAt, fields)
        val res = try {
            send(body)
        } catch (e: RuntimeException) {
            warn("kimliksiz attribution gönderilemedi: $e")
            return deliver(onDelivered, false)
        }
        if (res.outcome != Outcome.OK) warn("kimliksiz attribution reddedildi: ${res.outcome}")
        deliver(onDelivered, res.outcome == Outcome.OK)
    }

    // --- kurulum ---------------------------------------------------------------------------------

    private fun bootstrap() {
        if (booted) return
        booted = true
        // noBackupFilesDir: Auto Backup kimliği yeni cihaza kopyalamasın diye (SPEC §6).
        val base = try {
            app.noBackupFilesDir
        } catch (e: RuntimeException) {
            null
        }
        if (base == null) {
            warn("noBackupFilesDir okunamadı; SDK atıl kalıyor")
            return
        }
        noBackupDir = base
        val d = File(base, DIR_NAME)
        dir = d
        // Yalnız okuma: dizin yaratılmaz, rıza yokken diskte iz bırakılmaz.
        val f = File(d, "consent")
        consentFile = f
        val store = ConsentStore(f)
        consentStore = store
        consent = store.read()
        if (consent.analytics) enableAnalytics()
    }

    /**
     * Kalıcı kimliği ve kuyruğu açan tek anahtar `analytics`'tir (SPEC §5). `attribution`/`adSharing`
     * onun üstüne eklenen kademelerdir; tek başlarına kalıcı iz yazdırmaz.
     */
    private fun enableAnalytics() {
        if (analytics) return
        val d = dir ?: return
        analytics = true

        val ids = DeviceIdStore(noBackupDir, uuid)
        deviceIdStore = ids
        val takeover = findTakeover()
        val own = ids.read()
        var migratedFrom: String? = null
        var id = when {
            // Devralma sırası (SPEC §6): 1 açık yapılandırma, 2 kendi depomuz, 3+ Amplitude.
            takeover != null && takeover.source == "configured" -> takeover.deviceId
            own != null -> own
            takeover != null -> takeover.deviceId.also { migratedFrom = takeover.source }
            else -> null
        }
        if (id != null && id != own) {
            try {
                ids.write(id)
            } catch (e: IOException) {
                warn("devralınan cihaz kimliği yazılamadı: $e")
                id = null
                migratedFrom = null
            }
        }
        deviceId = id ?: ids.getOrCreate()
        if (deviceId == null) {
            // Direct Boot / kilitli kullanıcıda noBackupFilesDir okunamaz. Kurulumu yarıda bırakmıyoruz:
            // depo erişilebilir hale gelince ilk event kimliği kendisi üretir (bkz. ensureDeviceId).
            warn("cihaz kimliği üretilemedi (depo erişilemez); kimlik sonraki event'te yeniden denenecek")
        }
        userId = readUserId()
        distinctIdCache = userId ?: deviceId

        sessionStore = SessionStore(File(d, "session"), now)
        val q = EventQueue(File(d, "queue"), config.maxQueueEvents)
        queue = q
        val send = sender ?: Transport(config.endpoint, config.writeKey)::send
        // Backoff monotonik saatle, `sent_at` duvar saatiyle: ikisi aynı kaynaktan gelemez.
        dispatcher = Dispatcher(q, send, elapsed, random, wallClock = now, warn = ::warn)
        contextProvider = ContextProvider(app, BuildConfig.LIB_VERSION)
        val once = LinkedHashMap<String, Any?>()
        // Devralma izi: panelde "Amplitude'dan gelen kullanıcı" kırılımı (SPEC §6).
        migratedFrom?.let { once["migrated_from"] = it }
        if (takeover != null && takeover.optOut) {
            // SPEC §6 "Amplitude'un optOut değeri korunur": koruma mekanizması rızanın VARSAYILAN KAPALI
            // olmasıdır — Amplitude'da çıkmış kullanıcı, uygulama açıkça setConsent(analytics = true)
            // demedikçe hiçbir şey göndermez. Buraya ancak o açık çağrıyla gelinir ve TAZE RIZA eski
            // üçüncü taraf bayrağını ezer (kullanıcı yeni rıza ekranında bilerek onay verdi). Bayrağın
            // kendisi kaybolmasın diye kullanıcı özelliğine yazılır ve duyurulur.
            warn("Amplitude kaydı opt-out; açık rıza üstün geldi, migrated_opt_out işaretlendi")
            once["migrated_opt_out"] = true
        }
        if (once.isNotEmpty()) pendingSetOnce = merge(pendingSetOnce, once)

        // Tracker YALNIZ rızayla kurulur: durum dosyası (son görülen sürüm) rızasız kalıcı bir iz olurdu.
        val existingInstall = migratedFrom != null || (takeover != null && own == null)
        val t = LifecycleTracker(app, File(d, "state"), existingInstall, ::onLifecycleEvent, ::flushFromBackground, elapsed)
        // Kurulum/güncelleme kontrolü diske ve PackageManager'a gider: scheduler thread'inde yapılır.
        t.checkInstallOrUpdate()
        // Callback'leri Core dağıtır (tracker kendi kendini kaydetmez): kayıt ana thread'de, ilk
        // Activity'den önce yapıldı; burada yalnız bayrak takılır ve o anki durum tracker'a aktarılır.
        mainPoster { attachTracker(t) }
        refreshStats()
    }

    private fun attachTracker(t: LifecycleTracker) {
        tracker = t
        for (a in ArrayList(startedActivities)) t.onActivityStarted(a)
    }

    private fun findTakeover(): Takeover? {
        // legacyAmplitude kapalıysa Amplitude kayıtlarına hiç bakılmaz; açık yapılandırma yine okunur.
        if (!config.legacyAmplitude && config.deviceId == null) return null
        return try {
            AmplitudeTakeover.find(app, config.deviceId)
        } catch (e: RuntimeException) {
            warn("devralma okunamadı: $e")
            null
        }
    }

    // --- rıza -------------------------------------------------------------------------------------

    private fun applyConsent(next: Consent) {
        if (!booted) bootstrap()
        val store = consentStore ?: return
        consent = next
        try {
            store.write(next)
        } catch (e: IOException) {
            warn("rıza kaydı yazılamadı: $e")
            // Yazma HANGİ YÖNDE düşerse düşsün kayıt silinir. Diskte kalan eski kayıt her zaman daha
            // GENİŞ bir rızayı diriltebilir: yalnız "analytics kapandı" değil, "adSharing kapandı" da
            // bir sonraki süreçte geri açılırdı. Silmek her zaman daha kısıtlayıcı yöndedir —
            // ConsentStore dosya yokken üçünü de kapalı okur. Rıza VERME yönünde düşse bile doğrusu
            // budur: o süreçte bellekteki rıza geçerli kalır, sonraki süreç rızasız başlar ve host
            // rızayı yeniden sorar; bayat ve daha geniş bir rızayı diskte bırakmaktan iyidir.
            revokeOnDisk()
        }
        if (next.analytics) enableAnalytics() else disableAnalytics()
    }

    private fun revokeOnDisk() {
        val f = consentFile ?: return
        val gone = try {
            f.delete() || !f.exists()
        } catch (e: SecurityException) {
            false
        }
        if (!gone) warn("rıza kaydı silinemedi: rıza değişikliği bir sonraki açılışta kalıcı olmayabilir")
    }

    /**
     * Rıza geri alınınca kalıcı izin tamamı silinir (SPEC §5). ConsentStore bilerek yalnız okuyup yazar;
     * başka depoların dosyalarını silmek facade'in işi.
     *
     * Bu süreçte analitik hiç açılmamış olsa bile silme yapılır: dosyalar önceki açılıştan kalmış olabilir.
     */
    private fun disableAnalytics() {
        analytics = false
        mainPoster { tracker = null }
        val d = dir
        queue = null
        dispatcher = null
        sessionStore = null
        contextProvider = null
        deviceIdStore = null
        deviceId = null
        userId = null
        distinctIdCache = null
        clearPending()
        droppedNoIdentity = 0
        droppedNoContext = 0
        statsCache = EMPTY_STATS
        if (d == null) return
        File(d, "queue").deleteRecursively()
        File(d, "device_id").delete()
        File(d, USER_ID_FILE).delete()
        File(d, "state").delete()
        File(d, "session").delete()
    }

    // --- event yazımı -------------------------------------------------------------------------------

    private fun enqueueAsync(
        name: String,
        props: Map<String, Any?>?,
        extraOps: PropertyOps?,
        userIdArg: String?,
    ) {
        // Zaman çağrı ANINDA alınır: kuyruk gecikmesi event'in zamanını kaydırmamalı.
        val time = now()
        // insert_id ise scheduler'da üretilir: UUID.randomUUID ilk çağrısında SecureRandom'ı tohumlamak
        // için /dev/urandom okur ve bu, çağıran thread'de StrictMode disk ihlali olurdu. Dedup anahtarı
        // olarak önemli olan, bir daha DEĞİŞMEMESİ; nerede üretildiği değil.
        // Savunma kopyası: çağıran map'i döndükten sonra değiştirebilir.
        val snapshot = props?.let { LinkedHashMap(it) }
        scheduler.post {
            if (!ready()) return@post
            write(name, time, uuid(), snapshot, extraOps, userIdArg)
        }
    }

    /** @return event kuyruğa gerçekten yazıldıysa `true` (attribution "kalıcı devir" ölçütü). */
    private fun write(
        name: String,
        time: Long,
        insertId: String,
        props: Map<String, Any?>?,
        extraOps: PropertyOps?,
        userIdArg: String?,
    ): Boolean {
        val q = queue ?: return false
        val distinct = userId ?: ensureDeviceId() ?: run {
            droppedNoIdentity++
            warn("cihaz kimliği yok: event düşürüldü")
            refreshStats()
            return false
        }
        val session = sessionStore?.current() ?: return dropNoContext("oturum deposu yok")
        val context = contextProvider?.get(consent, if (foreground) "foreground" else "background")
            ?: return dropNoContext("ortam sağlayıcısı yok")
        // Bekleyen kullanıcı özellikleri ANCAK event gerçekten kuyruğa girecekse tüketilir: yukarıdaki
        // erken dönüşlerde biriken $set/$set_once/$add/$unset olduğu gibi kalır.
        val ops = takePending(extraOps)
        val line = EventJson.event(
            name = name,
            distinctId = distinct,
            time = time,
            insertId = insertId,
            sessionId = session,
            props = filterProps(props),
            ops = ops,
            userId = userIdArg,
            context = context,
        )
        val droppedBefore = q.droppedCount()
        // EventQueue dosya hatasını IllegalStateException ya da IOException olarak verir; ikisi de
        // ana uygulamaya sızmamalı (analitik, host'u çökertmenin gerekçesi değil).
        val failure: Exception? = try {
            q.append(line)
            null
        } catch (e: IOException) {
            e
        } catch (e: RuntimeException) {
            e
        }
        if (failure != null) {
            warn("event kuyruğa yazılamadı: $failure")
            // Host'un setUserProperties çağrısı sessizce yutulmasın: bekleyenler geri konur.
            restorePending(ops)
            return false
        }
        val overflow = q.droppedCount() - droppedBefore
        if (overflow > 0) warn("kuyruk doldu: $overflow event düşürüldü")
        // $identify'dan SONRA distinct_id user_id olur; event'in kendisi eski anonim kimlikle gider.
        if (userIdArg != null) setUserId(userIdArg)
        refreshStats()
        if (q.size() >= config.flushAt) drain()
        return true
    }

    /**
     * `$` önekli prop anahtarı sunucuda BÜTÜN event'i düşürür (`props_invalid`); rezerve operasyon
     * alanları ($set, $set_once, $add, $unset) ayrı parametrelerle gittiği için `props` haritasında
     * `$` ile başlayan anahtarın geçerli bir kullanımı yok. Süzme yalnız buradadır.
     */
    private fun filterProps(props: Map<String, Any?>?): Map<String, Any?>? {
        if (props.isNullOrEmpty()) return props
        if (props.keys.none { it.startsWith("$") }) return props
        val out = LinkedHashMap<String, Any?>(props.size)
        for ((k, v) in props) {
            if (k.startsWith("$")) warn("prop anahtarı '$k' \$ ile başlıyor, atıldı") else out[k] = v
        }
        return out
    }

    private fun drain() {
        val d = dispatcher ?: return
        if (!analytics) return
        d.drain()
        refreshStats()
    }

    private fun flushFromBackground() {
        // Arka plana geçişte gönderim (SPEC §3). Tracker ana thread'de çağırır, iş scheduler'a gider.
        scheduler.post { drain() }
    }

    private fun onLifecycleEvent(name: String, props: Map<String, Any?>) {
        enqueueAsync(name, props, null, null)
    }

    private fun refreshStats() {
        val s = dispatcher?.stats() ?: EMPTY_STATS
        val extra = droppedNoIdentity + droppedNoContext
        statsCache = if (extra == 0) s else s.copy(dropped = s.dropped + extra)
    }

    /** Sayılmayan düşüş olmaz: `droppedNoIdentity` ile aynı desen, `stats().dropped` içinde görünür. */
    private fun dropNoContext(reason: String): Boolean {
        droppedNoContext++
        warn("$reason: event düşürüldü")
        refreshStats()
        return false
    }

    private fun ready(): Boolean {
        if (!booted) bootstrap()
        return analytics
    }

    // --- kullanıcı kimliği ve bekleyen özellikler ------------------------------------------------

    /** user_id device_id'nin yanında tutulur: süreç yeniden başlayınca distinct_id anonime dönmemeli. */
    private fun readUserId(): String? {
        val f = dir?.let { File(it, USER_ID_FILE) } ?: return null
        return try {
            if (!f.isFile) null else f.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private fun setUserId(id: String?) {
        userId = id
        val f = dir?.let { File(it, USER_ID_FILE) }
        try {
            if (id == null) f?.delete() else if (f != null) atomicWrite(f, id.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            warn("user_id yazılamadı: $e")
        }
        distinctIdCache = id ?: deviceId
    }

    /** Tamsayı artışları tamsayı kalır: gereksiz yere ondalığa çevirmek panelde 3.0 gibi değerler üretir. */
    private fun sum(a: Number, b: Number): Number =
        if (a is Double || a is Float || b is Double || b is Float) a.toDouble() + b.toDouble() else a.toLong() + b.toLong()

    /** Kimlik kurulumda üretilemediyse (kilitli depo) her event'te yeniden denenir. */
    private fun ensureDeviceId(): String? {
        deviceId?.let { return it }
        val id = deviceIdStore?.getOrCreate() ?: return null
        deviceId = id
        distinctIdCache = userId ?: id
        return id
    }

    /** Tüketilmiş ama kullanılamamış operasyonları geri yazar; arada biriken yeni değer üstte kalır. */
    private fun restorePending(ops: PropertyOps?) {
        if (ops == null) return
        ops.set?.let { old -> pendingSet = merge(LinkedHashMap(old), pendingSet ?: emptyMap()) }
        ops.setOnce?.let { old -> pendingSetOnce = merge(LinkedHashMap(old), pendingSetOnce ?: emptyMap()) }
        ops.unset?.let { old -> pendingUnset = ArrayList(old).apply { addAll(pendingUnset ?: emptyList()) } }
        ops.add?.let { old ->
            val acc = LinkedHashMap<String, Number>(old)
            for ((k, v) in pendingAdd ?: emptyMap()) acc[k] = acc[k]?.let { sum(it, v) } ?: v
            pendingAdd = acc
        }
    }

    private fun merge(target: LinkedHashMap<String, Any?>?, extra: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val acc = target ?: LinkedHashMap()
        acc.putAll(extra)
        return acc
    }

    private fun takePending(extra: PropertyOps?): PropertyOps? {
        val set = if (extra?.set.isNullOrEmpty()) pendingSet else merge(pendingSet, extra.set)
        val setOnce = if (extra?.setOnce.isNullOrEmpty()) pendingSetOnce else merge(pendingSetOnce, extra.setOnce)
        val unset = if (extra?.unset.isNullOrEmpty()) {
            pendingUnset
        } else {
            ArrayList(pendingUnset ?: emptyList()).apply { addAll(extra.unset) }
        }
        val add = if (extra?.add.isNullOrEmpty()) {
            pendingAdd
        } else {
            LinkedHashMap(pendingAdd ?: emptyMap()).apply { putAll(extra.add) }
        }
        clearPending()
        if (set.isNullOrEmpty() && setOnce.isNullOrEmpty() && unset.isNullOrEmpty() && add.isNullOrEmpty()) return null
        return PropertyOps(set, setOnce, unset, add)
    }

    private fun hasPending(): Boolean =
        !pendingSet.isNullOrEmpty() || !pendingSetOnce.isNullOrEmpty() ||
            !pendingUnset.isNullOrEmpty() || !pendingAdd.isNullOrEmpty()

    private fun clearPending() {
        pendingSet = null
        pendingSetOnce = null
        pendingUnset = null
        pendingAdd = null
    }

    private fun warn(message: String) {
        val logger = config.logger ?: return
        // Ana uygulamanın logger'ındaki hata SDK'yı düşürmemeli.
        try {
            logger.log("[cizeylem] $message")
        } catch (_: RuntimeException) {
        }
    }

    private companion object {
        const val DIR_NAME = "cizeylem"
        const val USER_ID_FILE = "user_id"
        const val IDENTIFY = "\$identify"
        const val DEEP_LINK = "cz_deep_link_opened"
        const val ATTRIBUTION = "cz_attribution"
        const val ANON_PATH = "/v1/attribution/anon"
        val EMPTY_STATS = Stats(0, 0, 0, false)
    }
}
