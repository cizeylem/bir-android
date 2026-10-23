package com.cizeylem.bir.internal

import com.cizeylem.bir.Consent
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConsentStoreTest {
    private lateinit var dir: File
    private val fixed = "11111111-2222-4333-8444-555555555555"

    @Before fun setUp() {
        dir = File.createTempFile("consent", "").apply { delete(); mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun consentFile() = File(File(dir, "cizeylem"), "consent")
    private fun store() = ConsentStore(consentFile())

    @Test fun `varsayilan uc anahtar da kapalidir`() {
        val c = Consent()
        assertFalse(c.analytics)
        assertFalse(c.attribution)
        assertFalse(c.adSharing)
        // Dosya yokken okuma da aynı varsayılanı verir ve diske iz bırakmaz.
        assertEquals(Consent(), store().read())
        assertFalse(File(dir, "cizeylem").exists())
    }

    @Test fun `yazilan riza sonraki okumada korunur`() {
        for (c in listOf(
            Consent(analytics = true),
            Consent(attribution = true),
            Consent(adSharing = true),
            Consent(analytics = true, attribution = true, adSharing = true),
            Consent(),
        )) {
            store().write(c)
            // Yeni örnek = süreç yeniden başlatma.
            assertEquals(c, store().read())
        }
        assertFalse(File(consentFile().path + ".tmp").exists())
    }

    @Test fun `bozuk riza dosyasi hepsi kapali olarak okunur`() {
        consentFile().parentFile!!.mkdirs()
        for (bad in listOf("", "   \n", "garbage", "true", "analytics=1", "analytics=1\nattribution=1",
            "analytics=1\nattribution=1\nadSharing=yes", "analytics=1\nattribution=1\nadSharing=1\nextra=1",
            "analytics=1\nanalytics=1\nadSharing=1")) {
            consentFile().writeText(bad)
            assertEquals("içerik: '$bad'", Consent(), store().read())
        }
        // Okunamayan dosya (yerine dizin) de kapalıdır.
        consentFile().delete()
        consentFile().mkdirs()
        assertEquals(Consent(), store().read())
    }

    @Test fun `erisilemez dizinde read hicbir sey yaratmaz`() {
        val blocker = File(dir, "blocker").apply { writeText("x") }
        val file = File(File(blocker, "cizeylem"), "consent")
        assertEquals(Consent(), ConsentStore(file).read())
        assertFalse(ConsentStore(file).allowsWrite())
        assertTrue(blocker.isFile)
        assertEquals(listOf("blocker"), dir.list()!!.toList())
    }

    @Test fun `rizasiz durumda kapi yazmaya izin vermez`() {
        assertFalse(store().allowsWrite())
        // Yalnız attribution/adSharing analytics yerine geçmez.
        store().write(Consent(attribution = true, adSharing = true))
        assertFalse(store().allowsWrite())
        store().write(Consent(analytics = true))
        assertTrue(store().allowsWrite())
        // Bozuk dosyada kapı kapalı kalır.
        consentFile().writeText("garbage")
        assertFalse(store().allowsWrite())
    }

    @Test fun `riza verilince device_id o anda uretilir`() {
        val ids = DeviceIdStore(dir) { fixed }
        val s = store()
        // Rızasız: kapı kapalı, çağıran kimliğe dokunmaz; ne rıza ne kimlik dosyası var.
        assertFalse(s.allowsWrite())
        assertNull(ids.read())
        assertFalse(File(dir, "cizeylem/device_id").exists())

        s.write(Consent(analytics = true))
        assertTrue(s.allowsWrite())
        assertEquals(fixed, ids.getOrCreate())
        assertEquals(fixed, File(dir, "cizeylem/device_id").readText().trim())
    }

    @Test fun `riza geri alininca kapi kapanir ve depo baska dosyaya dokunmaz`() {
        // Kuyruk/kimlik silme facade'in (Görev 10) işi; ConsentStore yalnız kendi dosyasını yazar.
        val ids = DeviceIdStore(dir) { fixed }
        val s = store()
        s.write(Consent(analytics = true))
        ids.getOrCreate()
        val queue = File(dir, "cizeylem/queue.jsonl").apply { writeText("{}\n") }

        s.write(Consent())
        assertFalse(s.allowsWrite())
        assertEquals(fixed, ids.read())
        assertTrue(queue.exists())
    }
}
