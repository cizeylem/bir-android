package com.cizeylem.bir.internal

import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * Deep link sorgusundan YALNIZ beyaz listedeki parametreleri alır (SPEC §3): `utm_` ile başlayan her anahtar,
 * `click_id` ve Meta'nın `campaign_ids`'i. Beyaz liste dışı hiçbir parametre OKUNMAZ: link'te e-posta, oturum
 * jetonu, sipariş numarası gibi kişisel veri taşınması olağandır ve bunların analitiğe sızması kaza olur.
 *
 * `android.net.Uri` yerine `java.net.URI`: saf JVM testinde (Robolectric'siz) de çalışsın ve şema tanımadan
 * gelen bozuk girdide sessizce boş dönsün diye.
 */
internal object DeepLink {
    private const val UTM_PREFIX = "utm_"
    private val ALLOWED = setOf("click_id", "campaign_ids")

    /** Bozuk URL'de boş harita; hiçbir durumda istisna fırlatmaz (çağrı host uygulamanın onCreate'inden gelir). */
    fun params(url: String): Map<String, String> {
        val query = query(url) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val key = decode(part.substring(0, i)) ?: continue
            if (!allowed(key)) continue
            val value = decode(part.substring(i + 1)) ?: continue
            // Değeri olmayan alan hiç yazılmaz (protokol genelindeki kural).
            if (value.isEmpty()) continue
            out[key] = value
        }
        return out
    }

    private fun allowed(key: String) = key.startsWith(UTM_PREFIX) || key in ALLOWED

    private fun query(url: String): String? = try {
        val uri = URI(url)
        // Opak URI (`myapp:open?…`, `//` yok) sorgu alanını doldurmaz; şema sonrası kısımdan okunur.
        if (uri.isOpaque) uri.rawSchemeSpecificPart.substringAfter('?', "").takeIf { it.isNotEmpty() } else uri.rawQuery
    } catch (_: URISyntaxException) {
        // URI ayrıştırması kodlanmamış boşluk/köşeli ayraç gibi kusurlarda tüm linki reddeder; gerçek
        // kampanya linklerinde bu sık görülür. Sorgu kısmını elle ayırıp beyaz listeyi yine uygularız:
        // '?' hiç yoksa (gerçekten bozuk girdi) boş kalır.
        url.substringAfter('?', "").substringBefore('#').takeIf { it.isNotEmpty() }
    }

    /** Çözülemeyen yüzde dizisi yalnız o parametreyi düşürür; linkin geri kalanı okunur. */
    private fun decode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: UnsupportedEncodingException) {
        null
    }
}
