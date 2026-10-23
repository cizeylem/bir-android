package com.cizeylem.bir.internal

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventQueueTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File.createTempFile("eventqueue", "").apply { delete(); mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun `append ve peek sirayi korur`() {
        val q = EventQueue(dir)
        q.append("""{"a":1}""")
        q.append("""{"a":2}""")
        q.append("""{"a":3}""")
        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), q.peek(2))
        assertEquals(3, q.size())
        assertEquals(3, q.peek(10).size)
        assertEquals(0, q.droppedCount())
    }

    @Test fun `removeFirst gonderilenleri siler, kalan korunur`() {
        val q = EventQueue(dir)
        (1..5).forEach { q.append("""{"n":$it}""") }
        q.removeFirst(2)
        assertEquals(listOf("""{"n":3}""", """{"n":4}""", """{"n":5}"""), q.peek(10))
        assertEquals(3, q.size())
        // Yeni bir örnek de aynı durumu görür (kalıcılık).
        assertEquals(3, EventQueue(dir).size())
        assertTrue(!File(dir, "queue.jsonl.tmp").exists())
    }

    @Test fun `1000 event asilinca en eskiler dusurulur ve droppedCount artar`() {
        val q = EventQueue(dir)
        (1..1005).forEach { q.append("""{"n":$it}""") }
        assertEquals(1000, q.size())
        assertEquals(5, q.droppedCount())
        assertEquals("""{"n":6}""", q.peek(1).single())
        assertEquals("""{"n":1005}""", q.peek(1000).last())
    }

    @Test fun `sinirin ustunde fiziksel budama olmasa da mantiksal gorunum dogrudur`() {
        val q = EventQueue(dir)
        (1..1050).forEach { q.append("""{"n":$it}""") }
        // 1050 <= 1000 + %10 slack: dosya henüz budanmamış, 1050 satır fiziksel olarak duruyor.
        assertEquals(1050, File(dir, "queue.jsonl").readLines().size)
        assertEquals(1000, q.size())
        assertEquals(50, q.droppedCount())
        assertEquals((51..60).map { """{"n":$it}""" }, q.peek(10))
        assertEquals("""{"n":1050}""", q.peek(5000).last())
        assertEquals(1000, q.peek(5000).size)
        // Yeni örnek de aynı mantıksal görünümü görür.
        assertEquals(1000, EventQueue(dir).size())
        // removeFirst mantıksal görünüm üzerinde çalışır.
        q.removeFirst(2)
        assertEquals("""{"n":53}""", q.peek(1).single())
        assertEquals(998, q.size())
        // Eşik aşılınca tek seferde budanır, mantık değişmez.
        (1051..1200).forEach { q.append("""{"n":$it}""") }
        assertTrue(File(dir, "queue.jsonl").readLines().size <= 1100)
        assertEquals("""{"n":1200}""", q.peek(5000).last())
    }

    @Test fun `5 MB asilinca en eskiler dusurulur`() {
        val q = EventQueue(dir)
        val big = "x".repeat(1024 * 1024 - 20)
        (1..6).forEach { q.append("""{"n":$it,"p":"$big"}""") }
        assertTrue(File(dir, "queue.jsonl").length() <= 5L * 1024 * 1024)
        assertTrue(q.size() < 6)
        assertTrue(q.droppedCount() >= 1)
        assertTrue(q.peek(1).single().startsWith("""{"n":2,"""))
        assertTrue(q.peek(10).last().startsWith("""{"n":6,"""))
    }

    @Test fun `yarim yazilmis son satir atilir, onceki satirlar okunur`() {
        val q = EventQueue(dir)
        q.append("""{"n":1}""")
        q.append("""{"n":2}""")
        // Süreç yazarken öldü: sonda '\n' yok.
        File(dir, "queue.jsonl").appendText("""{"n":3,"yar""")
        assertEquals(listOf("""{"n":1}""", """{"n":2}"""), q.peek(10))
        assertEquals(2, q.size())
        // Sonraki append yarım satırı yapıştırıp bozmaz.
        q.append("""{"n":4}""")
        assertEquals(listOf("""{"n":1}""", """{"n":2}""", """{"n":4}"""), q.peek(10))
    }

    @Test fun `ayni dizine iki EventQueue ornegi yazarsa hicbir satir kaybolmaz`() {
        val a = EventQueue(dir)
        val b = EventQueue(dir)
        val per = 300
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        listOf(a to "a", b to "b").forEach { (q, tag) ->
            pool.execute {
                start.await()
                (1..per).forEach { q.append("""{"k":"$tag$it"}""") }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS))
        val all = EventQueue(dir).peek(1000)
        assertEquals(2 * per, all.size)
        assertEquals(2 * per, all.toSet().size)
        // Her yazarın kendi içindeki sırası korunur.
        val aOrder = all.filter { it.contains("\"a") }
        assertEquals((1..per).map { """{"k":"a$it"}""" }, aOrder)
    }
}
