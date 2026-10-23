package com.cizeylem.bir.internal

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * Satır tabanlı (JSONL) dosya kuyruğu. Satırların içeriğini yorumlamaz.
 *
 * Ekleme her event'te yalnız dosya sonuna yazar (TV'de tüm dosyayı yeniden yazmak pahalı).
 * Silme ve budama `queue.jsonl.tmp`'ye yazıp `renameTo` ile atomik yerine koyar.
 */
internal class EventQueue(
    private val dir: File,
    private val maxEvents: Int = 1000,
    private val maxBytes: Long = 5L * 1024 * 1024,
) {
    private val file = File(dir, "queue.jsonl")
    private val tmp = File(dir, "queue.jsonl.tmp")

    // Kilit, veri dosyasında değil ayrı dosyada tutulur: veri dosyası rename ile değişince
    // eski inode üzerindeki kilit diğer süreci korumaz hale gelir.
    private val lockFile = File(dir, "queue.lock")

    // FileChannel.lock() aynı JVM içinde ikinci kanalda OverlappingFileLockException atar;
    // bu yüzden aynı dizini kullanan örnekler JVM düzeyinde ortak bir monitörle serileştirilir.
    private val monitor: Any = monitors.getOrPut(canonicalKey(dir)) { Any() }

    private var dropped = 0

    // Satır sayısını her append'de 5 MB'ı baştan okuyarak bulmamak için, dosya boyutu ve
    // değişim zamanı aynıysa önceki sayım yeniden kullanılır. Başka süreç yazarsa boyut değişir.
    private var cachedLength = -1L
    private var cachedModified = -1L
    private var cachedCount = 0

    fun append(line: String) {
        // Ham JSON'da satır sonu olmaz; olursa kuyruğun satır çerçevesini bozmasın.
        val clean = line.replace('\r', ' ').replace('\n', ' ')
        val bytes = (clean + "\n").toByteArray(UTF_8)
        withLock {
            val before = if (cacheValid()) cachedCount else -1
            RandomAccessFile(file, "rw").use { raf ->
                truncateTornTail(raf)
                raf.seek(raf.length())
                raf.write(bytes)
            }
            if (before >= 0) remember(before + 1) else cachedLength = -1L
            prune()
        }
    }

    fun peek(n: Int): List<String> = withLock {
        if (n <= 0) emptyList() else readLines().take(n)
    }

    fun removeFirst(n: Int) {
        if (n <= 0) return
        withLock {
            val lines = readLines()
            if (lines.isNotEmpty()) rewrite(lines.drop(n))
        }
    }

    fun size(): Int = withLock {
        if (cacheValid()) cachedCount else readLines().size.also { remember(it) }
    }

    fun droppedCount(): Int = synchronized(monitor) { dropped }

    private fun prune() {
        if (cacheValid() && cachedCount <= maxEvents && file.length() <= maxBytes) return
        val lines = readLines()
        var start = 0
        var bytes = lines.sumOf { it.toByteArray(UTF_8).size.toLong() + 1 }
        while (start < lines.size && (lines.size - start > maxEvents || bytes > maxBytes)) {
            bytes -= lines[start].toByteArray(UTF_8).size + 1
            start++
        }
        if (start == 0) {
            remember(lines.size)
            return
        }
        dropped += start
        rewrite(lines.drop(start))
    }

    private fun rewrite(lines: List<String>) {
        tmp.outputStream().use { out ->
            lines.forEach { out.write((it + "\n").toByteArray(UTF_8)) }
            // Rename'den önce diske indir; aksi halde çökmede boş dosya kalabilir.
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // Bazı dosya sistemleri hedef varken rename'i reddeder.
            file.delete()
            check(tmp.renameTo(file)) { "queue.jsonl.tmp yerine konamadı" }
        }
        remember(lines.size)
    }

    /** Yalnız '\n' ile bitmiş satırları döner; sondaki yarım satır atılır. */
    private fun readLines(): List<String> {
        if (!file.exists()) return emptyList()
        val data = file.readBytes()
        val last = data.lastIndexOf('\n'.code.toByte())
        if (last < 0) return emptyList()
        val text = String(data, 0, last, UTF_8)
        return text.split('\n').filter { it.isNotEmpty() }
    }

    // Yarım satır kalırsa sonraki append onunla birleşip iki satırı birden bozardı.
    private fun truncateTornTail(raf: RandomAccessFile) {
        val len = raf.length()
        if (len == 0L) return
        raf.seek(len - 1)
        if (raf.read() == '\n'.code) return
        var pos = len - 1
        while (pos > 0) {
            raf.seek(pos - 1)
            if (raf.read() == '\n'.code) break
            pos--
        }
        raf.setLength(pos)
    }

    private fun cacheValid() = file.exists() && file.length() == cachedLength && file.lastModified() == cachedModified

    private fun remember(count: Int) {
        cachedCount = count
        cachedLength = if (file.exists()) file.length() else 0L
        cachedModified = if (file.exists()) file.lastModified() else 0L
    }

    private fun <T> withLock(block: () -> T): T = synchronized(monitor) {
        dir.mkdirs()
        RandomAccessFile(lockFile, "rw").channel.use { ch ->
            ch.lock().use { block() }
        }
    }

    private companion object {
        val UTF_8: Charset = Charsets.UTF_8
        val monitors = ConcurrentHashMap<String, Any>()
        fun canonicalKey(dir: File): String = try { dir.canonicalPath } catch (e: java.io.IOException) { dir.absolutePath }
    }
}
