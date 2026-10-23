package com.cizeylem.bir

import android.app.Application
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
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
        // SDK thread'inin işi bitsin (ana thread'i bloklamadan çağrılar anında dönmüş olmalı).
        Thread.sleep(1500)
        assertEquals(false, Cizeylem.stats().disabled)
    }
}
