package com.cizeylem.bir.internal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.util.Properties

internal data class Takeover(val deviceId: String, val source: String, val optOut: Boolean)

/**
 * Host uygulamanın mevcut Amplitude kurulumundan cihaz kimliğini devralır (SPEC §6). SALT OKUMA:
 * hiçbir Amplitude kaydı yazılmaz, silinmez, taşınmaz; hiçbir dizin yaratılmaz.
 *
 * Amplitude-Kotlin `api_key` değişince kayıtlı device_id'yi siliyor; burada bilerek yapılmaz.
 *
 * Her kaynak kendi içinde dayanıklıdır: okunamayan/bozuk bir kaynak "boş" sayılıp sıradakine geçilir,
 * ama kaydına dokunulmaz (DeviceIdStore'daki "okunamayan kayıt bozuk değildir" ilkesiyle aynı).
 */
internal object AmplitudeTakeover {
    private const val DEFAULT_INSTANCE = "\$default_instance"

    // Karşılaştırma küçük harfe çevrilerek yapılır: eski Android sürümleri/üreticiler bu sabitleri
    // farklı yazımlarla ("DEFACE", "deface", "ANDROID") üretebiliyor; hepsi aynı çöp değerdir.
    private val INVALID = setOf(
        "", "9774d56d682e549c", "unknown", "000000000000000", "android", "deface",
        "00000000-0000-0000-0000-000000000000",
    )

    private fun valid(raw: String?): String? {
        val v = raw?.trim() ?: return null
        if (v.lowercase() in INVALID) return null
        // Sonu "S" ile biten kimlikleri eski Amplitude-Android kendisi kullanmayıp yeniden üretir;
        // Amplitude'un terk ettiği kimliği diriltmek cihaz düzeyindeki parity eşleşmesini bozar.
        if (v.endsWith("S")) return null
        return v
    }

    fun find(context: Context, configured: String?): Takeover? {
        valid(configured)?.let { return Takeover(it, "configured", false) }
        // applicationInfo.dataDir: getDir()/filesDir gibi dizin yaratan çağrılardan kaçınmak için.
        val dataDir = File(context.applicationInfo.dataDir)
        return fromKotlin(dataDir, context.packageName)
            ?: fromSqlite(dataDir)
            ?: fromPrefs(context, dataDir)
    }

    private fun fromKotlin(dataDir: File, pkg: String): Takeover? = try {
        val base = File(dataDir, "app_amplitude/$pkg")
        // Birden çok instance'ta SDK öncelik tanımlamaz; dizin sırası cihazdan cihaza değişebileceğinden
        // önce varsayılan, sonra alfabetik: deterministik olmayan seçim farklı cihazda farklı kimlik devralırdı.
        val names = listOf(DEFAULT_INSTANCE) +
            (base.list()?.filter { it != DEFAULT_INSTANCE }?.sorted() ?: emptyList())
        names.firstNotNullOfOrNull { name ->
            val f = File(base, "$name/analytics/identity.properties")
            if (!f.isFile) null else try {
                val props = Properties()
                FileInputStream(f).use { props.load(it) }
                // Kotlin SDK optOut'u cihaza yazmaz (yalnız bellekte); bu kaynakta false varsayılır.
                valid(props.getProperty("device_id"))?.let { Takeover(it, "amplitude-kotlin", false) }
            } catch (e: Exception) { null }
        }
    } catch (e: Exception) { null }

    private fun fromSqlite(dataDir: File): Takeover? = try {
        val dir = File(dataDir, "databases")
        // Adlandırılmış instance'ta dosya `com.amplitude.api_<instance>` (küçük harf); journal/wal yan dosyaları elenir.
        val names = listOf("com.amplitude.api") + (dir.list()?.filter {
            it.startsWith("com.amplitude.api_") && !it.endsWith("-journal") &&
                !it.endsWith("-wal") && !it.endsWith("-shm")
        }?.sorted() ?: emptyList())
        names.firstNotNullOfOrNull { readDb(File(dir, it)) }
    } catch (e: Exception) { null }

    private fun readDb(f: File): Takeover? {
        if (!f.isFile) return null
        var db: SQLiteDatabase? = null
        return try {
            // READONLY: Amplitude'un veritabanına yazma/şema değişikliği yolu kapalı kalır.
            db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
            val id = valid(db.queryString("store", "device_id")) ?: return null
            // opt_out store'da değil long_store'da (INTEGER 1/0); tablo/satır yoksa false.
            val opt = try { db.queryString("long_store", "opt_out") == "1" } catch (e: Exception) { false }
            Takeover(id, "amplitude-sqlite", opt)
        } catch (e: Exception) { null } finally {
            try { db?.close() } catch (e: Exception) { }
        }
    }

    private fun SQLiteDatabase.queryString(table: String, key: String): String? =
        rawQuery("SELECT value FROM $table WHERE key = ?", arrayOf(key)).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }

    private fun fromPrefs(context: Context, dataDir: File): Takeover? = try {
        val name = "com.amplitude.api.${context.packageName}"
        // Dosya yokken getSharedPreferences çağırmayız: yoksa boş bir XML/iz bırakabilir.
        if (!File(dataDir, "shared_prefs/$name.xml").isFile) null else {
            val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            valid(p.getString("com.amplitude.api.deviceId", null))?.let {
                Takeover(it, "amplitude-prefs", p.getBoolean("com.amplitude.api.optOut", false))
            }
        }
    } catch (e: Exception) { null }
}
