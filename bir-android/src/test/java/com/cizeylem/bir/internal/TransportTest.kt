package com.cizeylem.bir.internal

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransportTest {
    // com.sun.net.httpserver AGP'nin -no-jdk derlemesinde görünmüyor (yalnız android.jar var); java.net ile minik sunucu.
    private lateinit var server: ServerSocket
    private var status = 200
    private var headers = mapOf<String, String>()
    @Volatile private var lastPath: String? = null
    @Volatile private var lastAuth: String? = null
    @Volatile private var lastEncoding: String? = null
    @Volatile private var lastContentType: String? = null
    @Volatile private var lastBody: String? = null

    @Before fun setUp() {
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                try { server.accept().use { handle(it) } } catch (e: Exception) { /* kapanış */ }
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun tearDown() {
        server.close()
    }

    private fun InputStream.line(): String {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun handle(sock: Socket) {
        val input = sock.getInputStream()
        lastPath = input.line().split(" ")[1]
        val h = mutableMapOf<String, String>()
        while (true) {
            val l = input.line()
            if (l.isEmpty()) break
            h[l.substringBefore(":").lowercase()] = l.substringAfter(":").trim()
        }
        lastAuth = h["authorization"]
        lastEncoding = h["content-encoding"]
        lastContentType = h["content-type"]
        val raw = ByteArray(h["content-length"]?.toInt() ?: 0)
        var n = 0
        while (n < raw.size) { val r = input.read(raw, n, raw.size - n); if (r < 0) break; n += r }
        lastBody = if (lastEncoding == "gzip") {
            GZIPInputStream(raw.inputStream()).readBytes().toString(Charsets.UTF_8)
        } else raw.toString(Charsets.UTF_8)
        val payload = """{"error":"x"}""".toByteArray()
        val head = StringBuilder("HTTP/1.1 $status X\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n")
        headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("\r\n")
        sock.getOutputStream().apply { write(head.toString().toByteArray()); write(payload); flush() }
    }

    private fun endpoint() = "http://127.0.0.1:${server.localPort}"
    private fun transport(path: String? = null) =
        if (path == null) Transport(endpoint(), "ck_w_test") else Transport(endpoint(), "ck_w_test", path)

    private fun outcomeFor(code: Int): Outcome {
        status = code
        return transport().send("{}").outcome
    }

    @Test fun `200 OK dondurur`() {
        assertEquals(Outcome.OK, outcomeFor(200))
        assertEquals(Outcome.OK, outcomeFor(204))
    }

    @Test fun `400 DROP dondurur`() {
        assertEquals(Outcome.DROP, outcomeFor(400))
    }

    @Test fun `401 ve 403 FATAL dondurur`() {
        assertEquals(Outcome.FATAL, outcomeFor(401))
        assertEquals(Outcome.FATAL, outcomeFor(403))
    }

    @Test fun `413 SPLIT dondurur`() {
        assertEquals(Outcome.SPLIT, outcomeFor(413))
    }

    @Test fun `429 RETRY ve Retry-After saniyesini dondurur`() {
        status = 429
        headers = mapOf("Retry-After" to "7")
        val r = transport().send("{}")
        assertEquals(Outcome.RETRY, r.outcome)
        assertEquals(7_000L, r.retryAfterMs)
    }

    @Test fun `503 ile gelen Retry-After de okunur`() {
        status = 503
        headers = mapOf("Retry-After" to "7")
        val r = transport().send("{}")
        assertEquals(Outcome.RETRY, r.outcome)
        assertEquals(7_000L, r.retryAfterMs)
    }

    @Test fun `RETRY olmayan yanitta Retry-After yok sayilir`() {
        status = 400
        headers = mapOf("Retry-After" to "7")
        assertNull(transport().send("{}").retryAfterMs)
    }

    @Test fun `429 Retry-After basligi yoksa 30000 ms varsayilani doner`() {
        status = 429
        assertEquals(30_000L, transport().send("{}").retryAfterMs)
        // Açık başlık varsayılanı ezer (429 + Retry-After: 7 testi yukarıda).
    }

    @Test fun `429 disinda Retry-After yoksa ya da cozulemezse null`() {
        status = 503
        assertNull(transport().send("{}").retryAfterMs)
        headers = mapOf("Retry-After" to "yarin")
        assertNull(transport().send("{}").retryAfterMs)
        headers = mapOf("Retry-After" to "-5")
        assertNull(transport().send("{}").retryAfterMs)
    }

    @Test fun `429 ve cozulemeyen Retry-After de 30000 ms varsayilanina duser`() {
        status = 429
        headers = mapOf("Retry-After" to "yarin")
        assertEquals(30_000L, transport().send("{}").retryAfterMs)
    }

    @Test fun `Retry-After HTTP-date bicimi ms'ye cevrilir`() {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
        status = 429
        headers = mapOf("Retry-After" to fmt.format(Date(System.currentTimeMillis() + 30_000)))
        val ms = transport().send("{}").retryAfterMs
        assertNotNull(ms)
        assertTrue("ms=$ms", ms!! in 20_000L..30_000L)

        headers = mapOf("Retry-After" to fmt.format(Date(System.currentTimeMillis() - 60_000)))
        assertEquals(0L, transport().send("{}").retryAfterMs)
    }

    @Test fun `5xx ve baglanti hatasi RETRY dondurur`() {
        assertEquals(Outcome.RETRY, outcomeFor(500))
        assertEquals(Outcome.RETRY, outcomeFor(503))
        server.close()
        val r = transport().send("{}")
        assertEquals(Outcome.RETRY, r.outcome)
        assertNull(r.retryAfterMs)
    }

    @Test fun `listede olmayan kodlar bir-js gibi RETRY eslenir`() {
        assertEquals(Outcome.RETRY, outcomeFor(408))
        assertEquals(Outcome.RETRY, outcomeFor(302))
        assertEquals(Outcome.RETRY, outcomeFor(404))
        assertEquals(Outcome.RETRY, outcomeFor(422))
    }

    @Test fun `govde gzip'li gider ve sunucuda acilinca ayni JSON cikar`() {
        val json = """{"sent_at":1,"events":[{"event":"ğüşiöç €"}]}"""
        transport().send(json)
        assertEquals("gzip", lastEncoding)
        assertEquals(json, lastBody)
        assertEquals("application/json", lastContentType)
    }

    @Test fun `Authorization basligi Bearer apiKey'dir`() {
        transport().send("{}")
        assertEquals("Bearer ck_w_test", lastAuth)
    }

    @Test fun `varsayilan yol v1 batch, sondaki egik cizgiler kirpilir, yol degistirilebilir`() {
        transport().send("{}")
        assertEquals("/v1/batch", lastPath)
        Transport(endpoint() + "//", "k").send("{}")
        assertEquals("/v1/batch", lastPath)
        transport("/v1/attribution/anon").send("{}")
        assertEquals("/v1/attribution/anon", lastPath)
    }

    @Test fun `gecersiz endpoint istisna sizdirmaz`() {
        assertEquals(Outcome.RETRY, Transport("not a url", "k").send("{}").outcome)
    }
}
