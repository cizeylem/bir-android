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
            val before = physicalCount()
            RandomAccessFile(file, "rw").use { raf ->
                truncateTornTail(raf)
                raf.seek(raf.length())
                raf.write(bytes)
            }
            remember(before + 1)
            // Mantıksal görünüm son maxEvents satırdır; sınırın üstündeki her append bir event düşürür.
            if (before >= maxEvents) dropped++
            prune(logicalAfterAppend = minOf(before + 1, maxEvents))
        }
    }

    fun peek(n: Int): List<String> = withLock {
        if (n <= 0) emptyList() else logicalLines().take(n)
    }

    fun removeFirst(n: Int) {
        if (n <= 0) return
        withLock {
            val lines = logicalLines()
            if (lines.isNotEmpty()) rewrite(lines.drop(n))
        }
    }

    fun size(): Int = withLock { minOf(physicalCount(), maxEvents) }

    fun droppedCount(): Int = synchronized(monitor) { dropped }

    // Dolu kuyrukta her append'de dosyayı yeniden yazmamak için (TV'de pahalı) dosya fiziksel olarak
    // maxEvents + slack satıra kadar büyür; eşik aşılınca tek seferde maxEvents'e budanır. Slack %10:
    // yeniden yazım her ~100 append'de bir olur, dosya en fazla %10 şişer. Okuyucular yalnız son
    // maxEvents satırı gördüğü için bu dışarıdan fark edilmez.
    // Bayt sınırı ise sert tutulur (dosya hiçbir zaman maxBytes'ı aşmaz); normal event boyutlarında
    // 5 MB'a ulaşılmaz, bu yüzden bu yol seyrek çalışır.
    private fun prune(logicalAfterAppend: Int) {
        val slack = maxOf(1, maxEvents / 10)
        val overCount = cachedCount > maxEvents + slack
        val overBytes = file.length() > maxBytes
        if (!overCount && !overBytes) return
        val lines = logicalLines()
        var start = 0
        var bytes = lines.sumOf { it.toByteArray(UTF_8).size.toLong() + 1 }
        while (start < lines.size && bytes > maxBytes) {
            bytes -= lines[start].toByteArray(UTF_8).size + 1
            start++
        }
        // Slack satırları zaten mantıksal olarak sayıldı; yalnız mantıksal görünümden giden event'ler eklenir.
        dropped += logicalAfterAppend - (lines.size - start)
        rewrite(lines.drop(start))
    }

    private fun physicalCount(): Int =
        if (cacheValid()) cachedCount else rawLines().size.also { remember(it) }

    private fun rewrite(lines: List<String>) {
        tmp.outputStream().use { out ->
            // Düz döngü: lint `NewApi` yanlış pozitifi (Iterable.forEach API 24 sanıyordu; bytecode'da
            // API 24 çağrısı yok, Kotlin inline forEach'ti). Çözümleme belirsizliği baştan kalksın diye.
            for (line in lines) out.write((line + "\n").toByteArray(UTF_8))
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

    private fun logicalLines(): List<String> = rawLines().takeLast(maxEvents)

    /** Yalnız '\n' ile bitmiş satırları döner; sondaki yarım satır atılır. */
    private fun rawLines(): List<String> {
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
