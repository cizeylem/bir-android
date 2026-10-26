package com.cizeylem.bir.attribution

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttributionStateTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File.createTempFile("attrstate", "").apply { delete(); mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun `dosya yokken isaret yok ve okuma dizin yaratmaz`() {
        assertFalse(AttributionState(dir).isDone("play_referrer"))
        assertFalse(File(dir, "cizeylem").exists())
    }

    @Test fun `noBackupDir null ise isaret yok ve yazma IOException`() {
        val s = AttributionState(null)
        assertFalse(s.isDone("play_referrer"))
        try { s.markDone("play_referrer"); throw AssertionError("beklenen IOException") } catch (_: java.io.IOException) {}
    }

    @Test fun `markDone kalici ve baska isaretleri korur`() {
        AttributionState(dir).markDone("play_referrer")
        AttributionState(dir).markDone("meta")
        val s = AttributionState(dir)
        assertTrue(s.isDone("play_referrer"))
        assertTrue(s.isDone("meta"))
        assertFalse(s.isDone("baska"))
        assertFalse(File(dir, "cizeylem/attr_state.tmp").exists())
    }

    @Test fun `bozuk dosya isaret yok sayilir ve yeniden yazilabilir`() {
        File(dir, "cizeylem").mkdirs()
        File(dir, "cizeylem/attr_state").writeText("{bozuk")
        val s = AttributionState(dir)
        assertFalse(s.isDone("play_referrer"))
        s.markDone("play_referrer")
        assertEquals(true, AttributionState(dir).isDone("play_referrer"))
    }
}
