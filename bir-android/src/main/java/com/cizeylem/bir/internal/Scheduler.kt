package com.cizeylem.bir.internal

import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * SDK'nın tek iş parçacığı. Genel API'nin TÜM disk/Binder/ağ işi buraya post edilir (SPEC §3
 * "Eşzamanlılık"); çağıran thread yalnız birkaç alan okur/yazar.
 *
 * Soyutlama testler için: gerçek zamanlayıcıyla zamana bağlı test yazmak yerine görevler elle çalıştırılır.
 * Coroutines yok, `java.util.concurrent` ile (SPEC §1 sıfır üçüncü taraf bağımlılık).
 */
internal interface Scheduler {
    fun post(task: () -> Unit)

    /** Periyodik gönderim (SPEC §3 "30 sn"). Dispatcher bilinçli olarak zamanlayıcı içermez. */
    fun schedulePeriodic(periodMs: Long, task: () -> Unit)

    fun shutdown()
}

/**
 * Tek thread'li gerçek zamanlayıcı. Thread daemon'dır: SDK, ana uygulamanın JVM'inin kapanmasını geciktirmez.
 * Öncelik düşük tutulur; analitik, kullanıcının gördüğü işin önüne geçmez.
 *
 * Görevler sarmalanır: bir görevdeki istisna ne SDK thread'ini öldürür ne de periyodik görevi iptal eder
 * (ScheduledExecutorService, fırlatan periyodik görevi sessizce bir daha çalıştırmaz).
 */
internal class ThreadScheduler(private val onError: (String) -> Unit = {}) : Scheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "cizeylem").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    init {
        // Kapanışta bekleyen görevler çalıştırılmaz; shutdown() çağrısı anında biter.
        executor.executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    override fun post(task: () -> Unit) {
        try {
            executor.execute { guard(task) }
        } catch (_: RejectedExecutionException) {
            // Kapatılmış SDK'ya gelen çağrı sessizce düşer.
        }
    }

    override fun schedulePeriodic(periodMs: Long, task: () -> Unit) {
        if (periodMs <= 0) return
        try {
            // WithFixedDelay (AtFixedRate değil): uzun süren bir gönderimden sonra birikmiş tetikler peş peşe koşmaz.
            executor.scheduleWithFixedDelay({ guard(task) }, periodMs, periodMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun shutdown() {
        try {
            executor.shutdownNow()
        } catch (_: SecurityException) {
        }
    }

    private fun guard(task: () -> Unit) {
        try {
            task()
        } catch (e: RuntimeException) {
            onError("görev başarısız: $e")
        } catch (e: IOException) {
            onError("görev başarısız: $e")
        }
    }
}
