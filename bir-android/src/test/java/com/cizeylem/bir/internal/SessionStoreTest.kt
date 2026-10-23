package com.cizeylem.bir.internal

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class SessionStoreTest {
    private lateinit var dir: File
    private lateinit var file: File
    private var t = 1_000_000L

    @Before fun setUp() {
        dir = File.createTempFile("session", "").apply { delete(); mkdirs() }
        file = File(File(dir, "cizeylem"), "session")
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = SessionStore(file, { t })

    @Test fun `ilk cagri simdiki zamani oturum kimligi yapar`() {
        assertEquals("1000000", store().current())
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test fun `30 dk icindeki cagri ayni oturumu dondurur`() {
        val s = store()
        val first = s.current()
        t += 29 * 60_000L
        assertEquals(first, s.current())
        // Pencere son etkinlikten kayar: 29 dk daha geçse de oturum sürer.
        t += 29 * 60_000L
        assertEquals(first, s.current())
        // Süreç yeniden başlasa da kalıcı durumdan aynı oturum.
        t += 1_000L
        assertEquals(first, store().current())
    }

    @Test fun `30 dk gecince yeni oturum baslar`() {
        val s = store()
        val first = s.current()
        t += 30 * 60_000L + 1
        val second = s.current()
        assertEquals(t.toString(), second)
        assertEquals(false, first == second)
    }

    @Test fun `reset sonrasi yeni oturum uretilir`() {
        val s = store()
        val first = s.current()
        t += 5_000L
        s.reset()
        assertEquals(t.toString(), s.current())
        assertEquals(false, first == s.current())
    }

    @Test fun `bozuk dosya yeni oturum baslatir`() {
        file.parentFile!!.mkdirs()
        file.writeText("çöp")
        assertEquals("1000000", store().current())
    }

    @Test fun `last en cok 60 sn aralikla diske yazilir`() {
        val s = store()
        s.current()
        val stamp = file.readText()
        assertEquals("1000000|1000000", stamp)
        t += 59_000L
        s.current()
        assertEquals(stamp, file.readText())
        t += 1_000L
        s.current()
        assertEquals("1000000|1060000", file.readText())
    }

    @Test fun `yeni oturum 60 sn dolmadan da hemen yazilir`() {
        val s = store()
        s.current()
        t += 31 * 60_000L
        s.current()
        assertEquals("${t}|$t", file.readText())
    }
}
