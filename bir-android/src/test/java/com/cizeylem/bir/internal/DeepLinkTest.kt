package com.cizeylem.bir.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saf JVM: DeepLink yalnız java.net kullanır, Android çerçevesine dokunmaz. */
class DeepLinkTest {

    @Test fun `utm parametreleri alinir`() {
        val p = DeepLink.params("https://example.com/bolum?utm_source=meta&utm_medium=cpc&utm_campaign=yaz")
        assertEquals("meta", p["utm_source"])
        assertEquals("cpc", p["utm_medium"])
        assertEquals("yaz", p["utm_campaign"])
        assertEquals(3, p.size)
    }

    @Test fun `click_id ve campaign_ids alinir`() {
        val p = DeepLink.params("myapp://open?click_id=abc123&campaign_ids=1,2,3")
        assertEquals("abc123", p["click_id"])
        assertEquals("1,2,3", p["campaign_ids"])
        assertEquals(2, p.size)
    }

    @Test fun `beyaz liste disi parametreler atilir`() {
        val p = DeepLink.params(
            "myapp://open?utm_source=meta&email=a%40b.com&token=gizli&fbclid=xyz&utmsource=hayir",
        )
        assertEquals(mapOf("utm_source" to "meta"), p)
    }

    @Test fun `bozuk URL bos harita dondurur, istisna firlatmaz`() {
        assertTrue(DeepLink.params("http://[bozuk").isEmpty())
        assertTrue(DeepLink.params("").isEmpty())
        assertTrue(DeepLink.params("sadece metin").isEmpty())
        assertTrue(DeepLink.params("myapp://open").isEmpty())
        // Çözülemeyen yüzde dizisi yalnız o parametreyi düşürür, çağrıyı patlatmaz.
        assertEquals(mapOf("utm_medium" to "cpc"), DeepLink.params("myapp://o?utm_source=%zz&utm_medium=cpc"))
    }

    @Test fun `yuzde kodlamasi ve arti cozulur`() {
        val p = DeepLink.params("myapp://open?utm_campaign=yaz+indirimi%20A")
        assertEquals("yaz indirimi A", p["utm_campaign"])
    }

    @Test fun `opak URI de okunur`() {
        assertEquals(mapOf("utm_source" to "x"), DeepLink.params("myapp:open?utm_source=x"))
    }

    @Test fun `degeri bos parametre yazilmaz`() {
        assertTrue(DeepLink.params("myapp://open?utm_source=&click_id").isEmpty())
    }
}
