package com.cizeylem.bir

/**
 * SDK'nın uyarı kanalı. Varsayılan olarak yoktur: kütüphane kendi başına logcat'e yazmaz
 * (ana uygulamanın log politikasını gasp etmemek için). Verilirse sunucunun reddettiği batch,
 * kuyruk taşması ve yetkilendirme hatası gibi sessiz kalırsa teşhis edilemeyecek durumlar duyurulur.
 *
 * `fun interface`: Java tarafında lambda ile de verilebilir.
 */
fun interface Logger {
    fun log(message: String)
}

/**
 * SDK yapılandırması (SPEC §1/§3). Tüm alanlar değişmez; `Cizeylem.initialize` sırasında okunur.
 *
 * `@JvmOverloads`: Java çağıranlar `new Configuration("ck_w_…")` kısa biçimini kullanabilsin diye.
 *
 * @param writeKey `ck_w_…` yazma anahtarı; `Authorization: Bearer` başlığına konur.
 * @param endpoint Toplama ucu; sondaki `/` kırpılır.
 * @param deviceId Dışarıdan dayatılan cihaz kimliği (devralma sırasının 1. basamağı, SPEC §6).
 * @param flushAt Kuyrukta bu kadar event birikince gönderim tetiklenir.
 * @param flushIntervalMs Periyodik gönderim aralığı.
 * @param maxQueueEvents Kuyruğun mantıksal üst sınırı; dolunca en eski event düşer.
 * @param legacyAmplitude Amplitude kurulumundan kimlik devralma denensin mi (salt okuma).
 * @param logger Uyarı kanalı; yoksa SDK sessizdir.
 */
class Configuration @JvmOverloads constructor(
    val writeKey: String,
    val endpoint: String = "https://in.cizeylem.com",
    val deviceId: String? = null,
    val flushAt: Int = 30,
    val flushIntervalMs: Long = 30_000,
    val maxQueueEvents: Int = 1000,
    val legacyAmplitude: Boolean = true,
    val logger: Logger? = null,
)
