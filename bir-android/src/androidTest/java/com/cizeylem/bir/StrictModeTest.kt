package com.cizeylem.bir

import android.app.Application
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SPEC §8: StrictMode `penaltyDeath` altında initialize → setConsent → track → flush ihlalsiz çalışmalı.
 * İhlal olursa süreç ölür ve enstrümantasyon "Process crashed" ile düşer.
 *
 * SDK işi kendi thread'inde yaptığından, ana thread dışındaki ihlalleri ThreadPolicy yakalamaz;
 * bu yüzden bekleme sonunda arka plan işinin bittiği (stats) doğrulanır ve VmPolicy de açıktır.
 */
class StrictModeTest {
    private lateinit var oldThread: StrictMode.ThreadPolicy
    private lateinit var oldVm: StrictMode.VmPolicy

    @Before fun strictAc() {
        oldThread = StrictMode.getThreadPolicy()
        oldVm = StrictMode.getVmPolicy()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().penaltyDeath().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().penaltyDeath().build())
    }

    @After fun strictKapat() {
        Cizeylem.setConsent(Consent())
        StrictMode.setThreadPolicy(oldThread)
        StrictMode.setVmPolicy(oldVm)
    }

    @Test fun strictModePenaltyDeathAltindaInitTrackFlushIhlalsizCalisir() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Kapalı bir porta: ağ hatası beklenir, ihlal değil. Yerel adres, gerçek sunucuya veri sızdırmaz.
        Cizeylem.initialize(app, Configuration(writeKey = "ck_w_test", endpoint = "http://127.0.0.1:9"))
        Cizeylem.setConsent(Consent(analytics = true))
        Cizeylem.track("strictmode_denemesi", mapOf("n" to 1))
        Cizeylem.flush()
        // Deterministik kısım: track'in SDK thread'inde kuyruğa yazıldığı (disk işi) yoklanarak beklenir.
        val bitis = System.currentTimeMillis() + 10_000
        while (Cizeylem.stats().queued < 1 && System.currentTimeMillis() < bitis) Thread.sleep(20)
        assertTrue("track SDK thread'inde kuyruğa yazılmadı", Cizeylem.stats().queued >= 1)
        // flush'ın ağ denemesinin bitişi gözlenemez (kapalı porta giden istek başarısız olunca event kuyrukta
        // kalır, yani queued değişmez; genel API'de tamamlanma sinyali yok). Bu yüzden kısa, sabit bir pay
        // bırakılır; ihlal olursa süreç ölür ve enstrümantasyon düşer.
        Thread.sleep(1000)
        assertEquals(false, Cizeylem.stats().disabled)
    }
}
