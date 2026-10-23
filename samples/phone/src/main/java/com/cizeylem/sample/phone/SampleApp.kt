package com.cizeylem.sample.phone

import android.app.Application
import android.os.StrictMode
import com.cizeylem.bir.Cizeylem
import com.cizeylem.bir.Configuration

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // StrictMode ÖNCE açılır: initialize bunun altında çalışmazsa kanıt anlamsız olur.
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyDeath().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyDeath().build())
        Cizeylem.initialize(this, Configuration(writeKey = "ck_w_ornek_anahtar"))
    }
}
