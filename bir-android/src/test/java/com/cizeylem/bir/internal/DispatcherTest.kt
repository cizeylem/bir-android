package com.cizeylem.bir.internal

import com.cizeylem.bir.Stats
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// EventJson org.json kullanır; birim testte stub olduğu için Robolectric (EventJsonTest ile aynı gerekçe).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DispatcherTest {
    @get:Rule val tmp = TemporaryFolder()

    // Dispatcher testlerinin HTTP'ye ihtiyacı yok: Transport (final) yerine, Dispatcher'ın gönderim
    // fonksiyonu alan kurucusuna sahte verilir. Transport sınıfı soyutlanmadı, kendi testleri ayrı.
    private val sent = mutableListOf<List<String>>() // her çağrıdaki insert_id'ler
    private val script = ArrayDeque<Response>()
    private var sendHook: (Int) -> Response? = { null }
    private var clock = 1_000_000L
    private var rnd = 0.5 // 0.5 -> jitter çarpanı tam 1.0
    private lateinit var queue: EventQueue

    @Before fun setUp() {
        queue = EventQueue(File(tmp.root, "q"), maxEvents = 5000, maxBytes = 100L * 1024 * 1024)
    }

    private fun dispatcher() = Dispatcher(
        queue,
        send = { body ->
            val events = JSONObject(body).getJSONArray("events")
            sent += (0 until events.length()).map { events.getJSONObject(it).getString("insert_id") }
            sendHook(sent.size) ?: script.removeFirstOrNull() ?: Response(Outcome.OK, null)
        },
        now = { clock },
        random = { rnd },
    )

    private fun fill(n: Int, pad: Int = 0) {
        for (i in 0 until n) queue.append("""{"event":"e","insert_id":"id$i","p":"${"x".repeat(pad)}"}""")
    }

    private fun r(o: Outcome, ra: Long? = null) = Response(o, ra)

    @Test fun `200 sonrasi gonderilen eventler kuyruktan silinir`() {
        fill(3)
        val d = dispatcher()
        d.drain()
        assertEquals(0, queue.size())
        assertEquals(listOf(listOf("id0", "id1", "id2")), sent)
        assertEquals(Stats(0, 0, 0, false), d.stats())
    }

    @Test fun `413'te batch ikiye bolunur`() {
        fill(4)
        script += r(Outcome.SPLIT)
        val d = dispatcher()
        d.drain()
        // 4 -> SPLIT -> 2'lik batch gider, sonra kalan 2.
        assertEquals(listOf(4, 2, 2), sent.map { it.size })
        assertEquals(listOf("id0", "id1"), sent[1])
        assertEquals(listOf("id2", "id3"), sent[2])
        assertEquals(0, queue.size())
        assertEquals(0, d.stats().rejected)
    }

    @Test fun `tek event 413 aliyorsa dusurulur ve dongu kilitlenmez`() {
        fill(2)
        script += r(Outcome.SPLIT) // [id0,id1] -> size 1
        script += r(Outcome.SPLIT) // [id0] tek event -> silinir
        val d = dispatcher()
        d.drain()
        assertEquals(listOf(listOf("id0", "id1"), listOf("id0"), listOf("id1")), sent)
        assertEquals(1, d.stats().rejected)
        assertEquals(0, queue.size())
    }

    @Test fun `401 sonrasi disabled olur ve bir daha gonderim denenmez`() {
        fill(3)
        script += r(Outcome.FATAL)
        val d = dispatcher()
        d.drain()
        assertTrue(d.isDisabled())
        assertEquals(3, queue.size())
        d.drain()
        assertEquals(1, sent.size)
        assertTrue(d.stats().disabled)
    }

    @Test fun `400 batch'i siler ama tekrar denemez`() {
        fill(3)
        script += r(Outcome.DROP)
        val d = dispatcher()
        d.drain()
        assertEquals(1, sent.size)
        assertEquals(0, queue.size())
        assertEquals(3, d.stats().rejected)
        assertFalse(d.isDisabled())
    }

    @Test fun `5xx'te kuyruk aynen kalir, ayni insert_id ile tekrar denenir`() {
        fill(3)
        script += r(Outcome.RETRY)
        val d = dispatcher()
        d.drain()
        assertEquals(3, queue.size())
        assertEquals(1, sent.size)
        clock += 2_000
        d.drain()
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1])
        assertEquals(0, queue.size())
    }

    @Test fun `backoff takvimi 1-8-32-128-300 sn ve ust sinir 300 sn`() {
        fill(1)
        rnd = 1.0 // çarpan 1.0: takvim değerinin kendisi
        val d = dispatcher()
        val expected = listOf(1, 8, 32, 128, 300, 300, 300)
        for ((i, sec) in expected.withIndex()) {
            script += r(Outcome.RETRY)
            d.drain()
            assertEquals(i + 1, sent.size)
            // Bekleme dolmadan (1 ms önce) gönderim yok, dolunca var.
            clock += sec * 1000L - 1
            d.drain()
            assertEquals("adım $i erken gönderdi", i + 1, sent.size)
            clock += 1
        }
    }

    @Test fun `backoff jitter takvim degerinin yuzde 50 ile yuzde 100 arasindadir ve tavani asmaz`() {
        val steps = listOf(1, 8, 32, 128, 300, 300)
        for ((rv, factor) in listOf(0.0 to 0.5, 1.0 to 1.0)) {
            tmp.root.deleteRecursively()
            queue = EventQueue(File(tmp.root, "q"), maxEvents = 5000)
            sent.clear()
            fill(1)
            rnd = rv
            val d = dispatcher()
            for ((i, sec) in steps.withIndex()) {
                val ms = (sec * 1000L * factor).toLong()
                script += r(Outcome.RETRY)
                d.drain()
                assertEquals(i + 1, sent.size)
                clock += ms - 1
                d.drain()
                assertEquals("random=$rv adım $i erken gönderdi", i + 1, sent.size)
                clock += 1
                if (i < steps.size - 1) continue
                // Son adım: tam sınırda gönderim var; 300 sn'yi aşan bekleme yok.
                assertTrue("tavan aşıldı", ms <= 300_000L)
                script += r(Outcome.RETRY)
                d.drain()
                assertEquals("random=$rv sınırda göndermedi", steps.size + 1, sent.size)
            }
        }
    }

    @Test fun `Retry-After verilmisse backoff takvimi yerine o kullanilir`() {
        fill(1)
        script += r(Outcome.RETRY, 45_000)
        val d = dispatcher()
        d.drain()
        clock += 44_999
        d.drain()
        assertEquals(1, sent.size)
        clock += 1
        d.drain()
        assertEquals(2, sent.size)
    }

    @Test fun `basarili gonderim backoff'u sifirlar`() {
        fill(1)
        val d = dispatcher()
        // Backoff'u 3. adıma (32 sn) çıkar.
        repeat(3) {
            script += r(Outcome.RETRY)
            d.drain()
            clock += 300_000
        }
        d.drain() // OK
        assertEquals(0, queue.size())
        fill(1)
        script += r(Outcome.RETRY)
        d.drain()
        // Sıfırlandıysa bekleme 1 sn (4. adım olan 128 sn değil).
        clock += 1_000
        d.drain()
        assertEquals(0, queue.size())
    }

    @Test fun `500 event'ten buyuk kuyruk birden fazla batch olarak gider`() {
        fill(1200)
        val d = dispatcher()
        d.drain()
        assertEquals(listOf(500, 500, 200), sent.map { it.size })
        assertEquals(0, queue.size())
    }

    @Test fun `1 MB'a ulasan batch event sayisi dolmadan kesilir`() {
        fill(10, pad = 300_000) // her satır ~300 KB -> batch başına 3
        val d = dispatcher()
        d.drain()
        assertEquals(listOf(3, 3, 3, 1), sent.map { it.size })
        assertEquals(0, queue.size())
    }

    @Test fun `1 MB'tan buyuk tek event yine de gonderilmeye calisilir`() {
        fill(1, pad = 1_200_000)
        val d = dispatcher()
        d.drain()
        assertEquals(1, sent.size)
        assertEquals(0, queue.size())
    }

    @Test fun `bozuk satir atlanir ve kuyruktan duser`() {
        queue.append("""{"event":"e","insert_id":"a"}""")
        queue.append("{yarim")
        queue.append("""{"event":"e","insert_id":"b"}""")
        val d = dispatcher()
        d.drain()
        assertEquals(listOf(listOf("a", "b")), sent)
        assertEquals(0, queue.size())
        assertEquals(1, d.skippedCount())
    }

    @Test fun `dusurulen ve reddedilen ayri sayilir`() {
        val small = EventQueue(File(tmp.root, "q2"), maxEvents = 2)
        for (i in 0 until 4) small.append("""{"event":"e","insert_id":"id$i"}""")
        script += r(Outcome.DROP)
        val d = Dispatcher(small, { script.removeFirst() }, { clock }, { rnd })
        d.drain()
        assertEquals(Stats(0, 2, 2, false), d.stats())
    }
}
