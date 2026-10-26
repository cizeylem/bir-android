package com.cizeylem.bir.attribution

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Meta (Facebook / Instagram / Facebook Lite) install referrer'ını content provider'dan okur (SPEC §7).
 *
 * SENKRON ve bloklayıcıdır: çağıran işçi thread'inde olmalı (ana thread'de çağrılmaz). Callback/teslim
 * sahipliği Görev 14'tedir; burada yalnız okunur ve döndürülür.
 *
 * URI: `content://<authority>/<FACEBOOK_APP_ID>`. Yol segmenti bizim paket adımız DEĞİL, Facebook App ID'dir;
 * paket adı verilirse provider hata vermeden boş cursor döner. App ID otomatik tespit EDİLMEZ (yanlış tespit
 * sessizce boş sonuç üretirdi): açık parametredir, yoksa Meta kaynağı atlanır.
 *
 * Authority desenleri tutarlı değil; üçü ayrı yazılmıştır. Sıra deterministik: Facebook, Instagram, Lite;
 * ilk BOŞ OLMAYAN `install_referrer` kazanır.
 *
 * Sürüm tabanları (ClickUp: FB >= 428, IG >= 296, Lite >= 411) KOD İLE DENETLENMEZ: bunların versionCode mu
 * versionName mi olduğu ve altındaki davranış doğrulanamadı, hiçbir SDK denetlemiyor. Kapı
 * `resolveContentProvider` + boş cursor'dır; kurulu sürüm yalnız tanılama için loglanır.
 *
 * Değer serileştirilmiş/şifreli bir blob'dur: ayrıştırılmaz, çözülmez, olduğu gibi döner. Sütun ADLARI kesin,
 * tipleri yalnız olası; beklenmedik tipli alan haritaya yazılmaz. İzin gerekmez; yine de her sorgu
 * try/catch içindedir ve hata yalnız o kaynağı atlatır.
 */
internal object MetaReferrer {
    private const val TAG = "CizeylemMeta"

    private const val COL_REFERRER = "install_referrer"
    private const val COL_IS_CT = "is_ct"
    private const val COL_TIMESTAMP = "actual_timestamp"

    private class Source(val name: String, val pkg: String, val authority: String)

    private val SOURCES = listOf(
        Source("Facebook", "com.facebook.katana", "com.facebook.katana.provider.InstallReferrerProvider"),
        Source("Instagram", "com.instagram.android", "com.instagram.contentprovider.InstallReferrerProvider"),
        Source("Lite", "com.facebook.lite", "com.facebook.lite.provider.InstallReferrerProvider"),
    )

    /** Ham alanlar (`install_referrer`, varsa `is_ct`, `actual_timestamp`) ya da sonuç yoksa null. */
    fun read(context: Context, facebookAppId: String?): Map<String, Any?>? {
        val appId = facebookAppId?.trim()
        if (appId.isNullOrEmpty()) {
            Log.w(TAG, "Facebook App ID verilmedi; Meta install referrer atlandı")
            return null
        }
        val app = context.applicationContext ?: context
        for (source in SOURCES) {
            val result = try {
                readSource(app, source, appId)
            } catch (e: Exception) {
                // SecurityException, IllegalArgumentException, DeadObject vb.: bu kaynağı atla.
                Log.w(TAG, "${source.name} provider sorgusu başarısız: ${e.javaClass.simpleName}")
                null
            }
            if (result != null) return result
        }
        return null
    }

    private fun readSource(context: Context, source: Source, appId: String): Map<String, Any?>? {
        val pm = context.packageManager
        if (pm.resolveContentProvider(source.authority, 0) == null) return null
        logVersion(context, source)
        val uri = Uri.parse("content://${source.authority}/$appId")
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use { c ->
            if (!c.moveToFirst()) return null
            val ref = readString(c, COL_REFERRER)
            if (ref.isNullOrEmpty()) return null
            val out = LinkedHashMap<String, Any?>()
            out[COL_REFERRER] = ref
            readInt(c, COL_IS_CT)?.let { out[COL_IS_CT] = it }
            readLong(c, COL_TIMESTAMP)?.let { out[COL_TIMESTAMP] = it }
            return out
        }
    }

    // Tip kontrolü: getString/getInt tip uyuşmazlığında sessizce dönüştürür; beklenmedik tipte alan yazılmamalı.
    private fun readString(c: Cursor, col: String): String? {
        val i = c.getColumnIndex(col)
        if (i < 0 || c.getType(i) != Cursor.FIELD_TYPE_STRING) return null
        return c.getString(i)
    }

    private fun readInt(c: Cursor, col: String): Int? {
        val i = c.getColumnIndex(col)
        if (i < 0 || c.getType(i) != Cursor.FIELD_TYPE_INTEGER) return null
        return c.getInt(i)
    }

    private fun readLong(c: Cursor, col: String): Long? {
        val i = c.getColumnIndex(col)
        if (i < 0 || c.getType(i) != Cursor.FIELD_TYPE_INTEGER) return null
        return c.getLong(i)
    }

    @Suppress("DEPRECATION")
    private fun logVersion(context: Context, source: Source) {
        try {
            val info = context.packageManager.getPackageInfo(source.pkg, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            Log.i(TAG, "${source.name} kurulu: versionName=${info.versionName} versionCode=$code")
        } catch (e: Exception) {
            // Tanılama amaçlı; başarısızlığı sonucu etkilemez.
        }
    }
}
