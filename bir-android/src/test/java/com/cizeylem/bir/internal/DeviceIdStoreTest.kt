package com.cizeylem.bir.internal

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceIdStoreTest {
    private lateinit var dir: File
    private val fixed = "11111111-2222-4333-8444-555555555555"

    @Before fun setUp() {
        dir = File.createTempFile("deviceid", "").apply { delete(); mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun idFile() = File(File(dir, "cizeylem"), "device_id")

    @Test fun `ilk cagri UUID uretir ve dosyaya yazar`() {
        val id = DeviceIdStore(dir) { fixed }.getOrCreate()
        assertEquals(fixed, id)
        assertEquals(fixed, idFile().readText().trim())
        assertFalse(File(idFile().path + ".tmp").exists())
    }

    @Test fun `ikinci cagri ayni id'yi dondurur`() {
        var n = 0
        val uuids = listOf(fixed, "99999999-2222-4333-8444-555555555555")
        val first = DeviceIdStore(dir) { uuids[n++] }.getOrCreate()
        // Yeni örnek (süreç yeniden başlatma) aynı kimliği okumalı, üretici çağrılmamalı.
        val second = DeviceIdStore(dir) { uuids[n++] }.getOrCreate()
        assertEquals(first, second)
        assertEquals(1, n)
    }

    @Test fun `bozuk dosya icerigi yok sayilir, yeni id uretilir`() {
        for (bad in listOf("", "   \n", "not-a-uuid", "0000000000000000", "00000000-0000-0000-0000-000000000000")) {
            idFile().parentFile!!.mkdirs()
            idFile().writeText(bad)
            val id = DeviceIdStore(dir) { fixed }.getOrCreate()
            assertEquals("içerik: '$bad'", fixed, id)
            assertEquals(fixed, idFile().readText().trim())
        }
    }

    @Test fun `dizin erisilemezse null doner ve dosya yazilmaz`() {
        assertNull(DeviceIdStore(null) { fixed }.getOrCreate())

        // Dizin yolu düz bir dosyanın altında: mkdirs başarısız olur (veri koruması benzeri erişilemezlik).
        val blocker = File(dir, "blocker").apply { writeText("x") }
        val store = DeviceIdStore(File(blocker, "sub")) { fixed }
        assertNull(store.getOrCreate())
        assertNull(store.read())
        assertTrue(blocker.isFile)
        assertFalse(File(blocker, "sub").exists())
    }

    @Test fun `okunamayan dosya bozuk sayilmaz, null doner ve ustune yazilmaz`() {
        // Dosya yerine dizin var: okuma IOException verir; bu "bozuk içerik" değil "erişilemez" durumudur.
        idFile().mkdirs()
        assertNull(DeviceIdStore(dir) { fixed }.getOrCreate())
        assertTrue(idFile().isDirectory)
    }

    @Test fun `write sonra read ayni id'yi verir`() {
        val store = DeviceIdStore(dir) { fixed }
        assertNull(store.read())
        store.write("99999999-2222-4333-8444-555555555555")
        assertEquals("99999999-2222-4333-8444-555555555555", store.read())
        assertNotEquals(fixed, store.read())
    }
}
