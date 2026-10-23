package com.cizeylem.bir.internal

/** `dropped`: kuyruğun taşmada düşürdüğü; `rejected`: sunucunun kabul etmediği (400/413). İkisi karıştırılmaz. */
internal data class Stats(val queued: Int, val dropped: Int, val rejected: Int, val disabled: Boolean)

/**
 * Gönderim döngüsü: kuyruktan batch alır, gönderir, sonuca göre siler / böler / bekler / durur
 * (SPEC §3/§5; bir-js `drain()` ile birebir, backoff takvimi hariç). Zamanlayıcı içermez; periyodik
 * çağrı facade'ın işidir. Tekrar sayısına üst sınır YOKTUR (bir-js de koymaz): backoff 300 sn'de tavan
 * yapar, kuyruk sınırı belleği zaten bağlar. Üst sınır üç platformda aynılığı bozardı.
 *
 * Gönderim fonksiyon olarak alınır: testler Transport'u (final) soyutlamadan, HTTP'siz sahte verebilsin diye.
 */
internal class Dispatcher(
    private val queue: EventQueue,
    private val send: (String) -> Response,
    private val now: () -> Long,
    private val random: () -> Double,
) {
    constructor(queue: EventQueue, transport: Transport, now: () -> Long, random: () -> Double) :
        this(queue, transport::send, now, random)

    private var disabled = false
    private var rejected = 0
    private var skipped = 0
    private var failures = 0 // ardışık RETRY sayısı; backoff basamağı
    private var backoffUntil = 0L

    fun isDisabled(): Boolean = disabled

    fun stats(): Stats = Stats(queue.size(), queue.droppedCount(), rejected, disabled)

    /** Bozuk olduğu için atlanıp kuyruktan düşen satır sayısı. */
    fun skippedCount(): Int = skipped

    // Aynı anda iki drain aynı satırları iki kez gönderip ikinci silmeyle başka event'leri silerdi.
    @Synchronized
    fun drain() {
        if (disabled) return
        if (queue.size() == 0) return
        if (now() < backoffUntil) return

        var size = MAX_EVENTS
        while (!disabled) {
            val lines = takeBatch(queue.peek(size))
            if (lines.isEmpty()) return
            val batch = EventJson.batch(now(), lines)
            skipped += batch.skipped
            // Hepsi bozuksa gönderecek event yok; boş batch göndermek anlamsız, satırlar doğrudan düşer.
            if (batch.skipped == lines.size) {
                queue.removeFirst(lines.size)
                continue
            }
            val res = send(batch.body)
            when (res.outcome) {
                Outcome.RETRY -> {
                    scheduleBackoff(res.retryAfterMs)
                    return
                }
                Outcome.FATAL -> {
                    disabled = true
                    return
                }
                Outcome.SPLIT -> {
                    if (lines.size == 1) {
                        // Tek başına sığmayan event: tutarsak döngü sonsuza dek kilitlenir.
                        rejected++
                        queue.removeFirst(1)
                    } else {
                        // Silme yok: aynı satırlar yarım boyutla yeniden denenir (insert_id değişmez).
                        size = (lines.size + 1) / 2
                    }
                    continue
                }
                Outcome.DROP -> rejected += lines.size
                Outcome.OK -> Unit
            }
            queue.removeFirst(lines.size)
            failures = 0
            backoffUntil = 0
            if (queue.size() == 0) return
        }
    }

    /** İlk `size` satırdan, sıkıştırılmamış toplamı 1 MB'ı aşmayacak kadarını alır; en az bir satır. */
    private fun takeBatch(lines: List<String>): List<String> {
        var bytes = 0L
        var n = 0
        for (l in lines) {
            bytes += l.toByteArray(Charsets.UTF_8).size
            if (n > 0 && bytes > MAX_BYTES) break
            n++
        }
        return lines.subList(0, n)
    }

    private fun scheduleBackoff(retryAfterMs: Long?) {
        val wait = retryAfterMs ?: run {
            val base = BACKOFF_SEC[minOf(failures, BACKOFF_SEC.size - 1)] * 1000L
            (base * (0.5 + random())).toLong() // ±%50 jitter
        }
        failures++
        backoffUntil = now() + wait
    }

    private companion object {
        const val MAX_EVENTS = 500
        const val MAX_BYTES = 1_000_000L // "1 MB"; ondalık, güvenli taraf
        val BACKOFF_SEC = longArrayOf(1, 8, 32, 128, 300)
    }
}
