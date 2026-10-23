package com.cizeylem.bir.internal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

/** Sunucu yanıtının SDK için anlamı (SPEC §5): üst katman kuyruğu buna göre siler, böler, bekler ya da durur. */
internal enum class Outcome { OK, DROP, SPLIT, RETRY, FATAL }

internal data class Response(val outcome: Outcome, val retryAfterMs: Long?)

/**
 * Gövdeyi gzip'leyip POST eder. Senkron: çağıran arka plan thread'indedir.
 * `path` varsayılanlı: attribution modülü rızasız sinyali `/v1/attribution/anon`'a aynı gzip + yanıt eşlemesiyle yollar.
 */
internal class Transport(endpoint: String, private val apiKey: String, path: String = "/v1/batch") {
    // bir-js çekirdeğiyle aynı: sondaki '/' kırpılır, yoksa "//v1/batch" olur.
    private val url = endpoint.trimEnd('/') + path

    fun send(body: String): Response {
        var conn: HttpURLConnection? = null
        return try {
            val gz = ByteArrayOutputStream().also { bos ->
                GZIPOutputStream(bos).use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }.toByteArray()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                // Yönlendirme POST'u sessizce GET'e çevirip gövdeyi düşürebilir; 3xx'i açıkça RETRY sayıyoruz.
                instanceFollowRedirects = false
                setFixedLengthStreamingMode(gz.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Encoding", "gzip")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(gz) }
            val code = conn.responseCode
            val outcome = map(code)
            // bir-js: düşüş yolundaki (RETRY) her kodda Retry-After okunur (503 + Retry-After yaygın).
            var retryAfter = if (outcome == Outcome.RETRY) parseRetryAfter(conn.getHeaderField("Retry-After")) else null
            // SPEC §3 "429 30 sn": başlık yoksa (ya da çözülemezse) varsayılan bekleme. Durum kodunu yalnız
            // Transport görür; Dispatcher kod bilmez, sadece retryAfterMs'e uyar.
            if (code == 429 && retryAfter == null) retryAfter = DEFAULT_429_MS
            // Bağlantı havuzda yeniden kullanılabilsin diye gövde (4xx/5xx'te errorStream) sonuna dek okunur.
            runCatching { (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } }
            Response(outcome, retryAfter)
        } catch (e: IOException) {
            Response(Outcome.RETRY, null)
        } catch (e: RuntimeException) {
            // Geçersiz endpoint (MalformedURLException IOException'dır; IllegalArgumentException/ClassCast değildir).
            Response(Outcome.RETRY, null)
        } finally {
            conn?.disconnect()
        }
    }

    private fun map(code: Int): Outcome = when {
        code in 200..299 -> Outcome.OK
        code == 400 -> Outcome.DROP
        code == 401 || code == 403 -> Outcome.FATAL
        code == 413 -> Outcome.SPLIT
        // bir-js ile birebir: 2xx/400/401/403/413 dışındaki HER ŞEY (429, 5xx, 3xx, 404, 422, 408 ...) retry.
        // Geçici proxy/dağıtım hatalarında veri kaybolmaz; döngüyü üst katmandaki backoff sınırlar.
        else -> Outcome.RETRY
    }

    private fun parseRetryAfter(v: String?): Long? {
        val s = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.all { it in '0'..'9' }) return s.toLongOrNull()?.let { if (it > MAX_SECONDS) null else it * 1000 }
        return try {
            // SimpleDateFormat thread-safe değil: çağrı başına yeni örnek.
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT"); isLenient = false }
            val at = fmt.parse(s)?.time ?: return null
            (at - System.currentTimeMillis()).coerceAtLeast(0)
        } catch (e: java.text.ParseException) {
            null
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val DEFAULT_429_MS = 30_000L
        const val MAX_SECONDS = 31_536_000L // taşmayı ve saçma değerleri önler
    }
}
