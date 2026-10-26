package com.cizeylem.bir.attribution

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import com.cizeylem.bir.Consent
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Google reklam kimliği (GAID), `play-services-ads-identifier` KÜTÜPHANESİ OLMADAN (SPEC §1 "sıfır üçüncü
 * taraf bağımlılık", §7).
 *
 * ÜÇ KAPI birden açık olmalı, yoksa servise hiç bağlanılmaz:
 *   1. `consent.adSharing` — kullanıcı reklam paylaşımına açıkça izin verdi mi,
 *   2. `com.google.android.gms.permission.AD_ID` izni — ANA UYGULAMANIN manifest'inde (bkz. aşağıdaki not),
 *   3. `isLimitAdTrackingEnabled == false` — cihazda kişiselleştirilmiş reklam sınırı kapalı.
 *
 * Bu üç kapı "GAID OKUNABİLİR Mİ" sorusunu yanıtlar. "GAID GÖNDERİLİR Mİ" ayrı bir karardır ve çağıranda
 * durur: [AttributionRun.route] GAID'i yalnız kimlikli dalda toplar, çünkü kimliksiz uç (`/v1/attribution/anon`)
 * sözleşmesi gereği hiçbir kalıcı kimlik taşıyamaz. Yani `adSharing` tek başına açıkken bu okuyucuya hiç gelinmez.
 *
 * SIFIR KİMLİK (`00000000-0000-0000-0000-000000000000`) asla döndürülmez: GMS, LAT açıkken ya da izin
 * bildirilmemişken bunu döndürür ve "kullanılabilir kimlik yok" demektir; kimlik olarak saklanamaz.
 *
 * İZİN KÜTÜPHANEDE BİLDİRİLMEZ: `<uses-permission AD_ID>` bu modülün manifest'ine konsaydı, manifest
 * birleştirmeyle modülü alan HER uygulamaya geçerdi ve Play Console'da reklam kimliği beyanı zorunlu
 * olurdu — uygulama `adSharing` rızasını hiç açmasa bile. Bu yüzden izin ana uygulamanın işidir; burada
 * yalnız çalışma anında denetlenir ve yoksa GAID atlanır. (Çekirdekte `ACCESS_NETWORK_STATE` için de
 * aynı desen uygulandı.)
 *
 * Protokol sabitleri `play-services-ads-identifier:18.3.0` bytecode'undan çıkarılmıştır; uydurma sabit yoktur.
 * Transaction kodu 6 (`getIsAdIdFakeForDebugLogging`) bilinçli olarak KULLANILMAZ.
 */
internal object AdvertisingId {
    fun read(context: Context, consent: Consent): String? = AdvertisingIdReader(context).read(consent)
}

internal class AdvertisingIdReader(
    context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val context: Context = context.applicationContext ?: context

    /**
     * SENKRON ve bloklayıcıdır (bağlanma beklemesi dahil): çağıran bir işçi thread'inde olmalıdır.
     * Hiçbir yoldan istisna sızdırmaz; her başarısızlık `null`'dır (GMS yok, bağlanma reddi, zaman aşımı,
     * servis hatası, sıfır kimlik).
     */
    fun read(consent: Consent): String? {
        // 1. kapı: rıza. En ucuz kontrol en önde; rıza yoksa PackageManager'a bile gidilmez.
        if (!consent.adSharing) return null
        // GMS kütüphanesi de ana thread'i reddeder (checkNotMainThread): bind beklemesi ANR üretirdi.
        if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "GAID ana thread'den okunamaz; atlandı")
            return null
        }
        // 2. kapı: izin. Yoksa GMS zaten sıfır kimlik döndürürdü; bağlanmadan kesiyoruz.
        if (!hasAdIdPermission()) return null

        val conn = BlockingConnection()
        val intent = Intent(ACTION).setPackage(GMS_PACKAGE)
        try {
            // setComponent YOK: kütüphane de yalnız setPackage kullanıyor, sınıf adı protokolün parçası değil.
            if (!context.bindService(intent, conn, BIND_AUTO_CREATE)) return null
            val service = conn.await(timeoutMs) ?: return null
            // LAT ÖNCE sorulur (kütüphane tersini yapıyor): sınır açıkken kimliği hiç istemeyiz, böylece
            // kullanılamayacak bir kimlik bu sürece hiç girmez.
            if (limitAdTracking(service) != false) return null
            val id = getId(service)
            if (id.isNullOrEmpty() || id == ZERO_ID) return null
            return id
        } catch (e: RemoteException) {
            return null
        } catch (e: RuntimeException) {
            // readException()'ın fırlattığı SecurityException dahil: GAID yoksa yok, host çökmez.
            return null
        } finally {
            // bindService false dönse de unbind çağrılır (Android sözleşmesi); kayıtlı değilse istisna yutulur.
            try {
                context.unbindService(conn)
            } catch (e: RuntimeException) {
            }
        }
    }

    private fun hasAdIdPermission(): Boolean {
        val result = try {
            if (Build.VERSION.SDK_INT >= 23) {
                context.checkSelfPermission(AD_ID_PERMISSION)
            } else {
                // API 21/22'de Context.checkSelfPermission yok; AD_ID kurulum anında verilen normal izindir,
                // PackageManager.checkPermission aynı sonucu verir.
                context.packageManager.checkPermission(AD_ID_PERMISSION, context.packageName)
            }
        } catch (e: RuntimeException) {
            return false
        }
        if (result != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "$AD_ID_PERMISSION bildirilmemiş; GAID atlandı")
            return false
        }
        return true
    }

    /** Kod 2; isteğe `writeInt(1)` yazılır (kütüphanenin birebir aynısı). Okunamazsa `null`. */
    private fun limitAdTracking(service: IBinder): Boolean? = call(service, TRANSACTION_LIMIT_AD_TRACKING) { reply ->
        reply.readInt() != 0
    }

    /** Kod 1; parametresiz, String döner. */
    private fun getId(service: IBinder): String? = call(service, TRANSACTION_GET_ID) { reply ->
        reply.readString()
    }

    /**
     * AIDL'nin ürettiği kodun aynısı: `writeInterfaceToken` → (kod 2 ise `writeInt(1)`) → `transact(flags 0)`
     * → `readException()` → okuma. Parcel'lar finally'de geri verilir.
     */
    private fun <T> call(service: IBinder, code: Int, read: (Parcel) -> T): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (code == TRANSACTION_LIMIT_AD_TRACKING) data.writeInt(1)
            service.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Bağlanmayı bloklayarak bekler (GMS kütüphanesindeki `BlockingServiceConnection` ile aynı fikir).
     * `onServiceConnected` ana thread'de gelir; sıra tek elemanlıdır ve ikinci sinyal sessizce düşer, çünkü
     * bekleyen yalnız ilkini kullanır.
     */
    private class BlockingConnection : ServiceConnection {
        private val queue = ArrayBlockingQueue<Any>(1)

        fun await(timeoutMs: Long): IBinder? {
            val taken = try {
                queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
            return taken as? IBinder
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            queue.offer(service ?: NONE)
        }

        // Kopma/ölü bağlantı/null binding: bekleyen zaman aşımını beklemeden serbest bırakılır.
        override fun onServiceDisconnected(name: ComponentName?) { queue.offer(NONE) }
        override fun onBindingDied(name: ComponentName?) { queue.offer(NONE) }
        override fun onNullBinding(name: ComponentName?) { queue.offer(NONE) }

        private companion object {
            val NONE = Any()
        }
    }

    internal companion object {
        const val TAG = "CizeylemGaid"
        const val ZERO_ID = "00000000-0000-0000-0000-000000000000"
        const val AD_ID_PERMISSION = "com.google.android.gms.permission.AD_ID"
        const val ACTION = "com.google.android.gms.ads.identifier.service.START"
        const val GMS_PACKAGE = "com.google.android.gms"
        const val DESCRIPTOR = "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService"
        const val TRANSACTION_GET_ID = 1
        const val TRANSACTION_LIMIT_AD_TRACKING = 2
        // Context.BIND_AUTO_CREATE; kütüphane de düz `1` geçiyor, başka bayrak yok.
        const val BIND_AUTO_CREATE = 1
        // Kütüphanedeki bağlanma beklemesiyle aynı: 10 sn.
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
