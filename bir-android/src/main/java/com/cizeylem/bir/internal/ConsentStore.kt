package com.cizeylem.bir.internal

import com.cizeylem.bir.Consent
import java.io.File
import java.io.IOException

/**
 * Rıza durumunun kalıcı kaydı. Dosya: `<noBackupDir>/cizeylem/consent` (kimlikle aynı yerde, Auto Backup dışı).
 *
 * Bu sınıf YALNIZ okur ve yazar. Rıza geri alınınca kuyruğu/kimliği silmek facade'in işidir; bir depo tipinin
 * başka depoların dosyalarını silmesi sorumluluk sınırını bozardı.
 *
 * Biçim: üç satır `anahtar=0|1`. Beklenenden herhangi bir sapma (eksik/fazla/yinelenen anahtar, bilinmeyen değer)
 * bozuk sayılır ve HEPSİ KAPALI okunur: bozuk dosyada "açık" varsaymak rızasız veri toplamak olurdu.
 */
internal class ConsentStore(private val file: File) {

    /** Dosya yok, okunamıyor ya da bozuksa hepsi kapalı. Diske hiçbir şey yazmaz, dizin yaratmaz. */
    fun read(): Consent {
        val text = try {
            if (!file.isFile) return Consent()
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return Consent()
        } catch (e: SecurityException) {
            return Consent()
        }
        return parse(text) ?: Consent()
    }

    /** Atomik yazar (bkz. atomicWrite). Başarısızlıkta IOException. */
    fun write(c: Consent) {
        val text = "analytics=${bit(c.analytics)}\nattribution=${bit(c.attribution)}\nadSharing=${bit(c.adSharing)}\n"
        atomicWrite(file, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Kalıcı iz (kuyruk, device_id) yazmanın ön koşulu: `analytics`. attribution/adSharing tek başına yetmez,
     * çünkü onlar analitik kimliğin üstüne eklenen kademelerdir (SPEC §5).
     */
    fun allowsWrite(): Boolean = read().analytics

    private fun bit(b: Boolean) = if (b) "1" else "0"

    private fun parse(text: String): Consent? {
        val values = HashMap<String, Boolean>()
        val lines = text.split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        for (line in lines) {
            val parts = line.split('=')
            if (parts.size != 2) return null
            val v = when (parts[1]) { "1" -> true; "0" -> false; else -> return null }
            if (values.put(parts[0], v) != null) return null
        }
        if (values.keys != KEYS) return null
        return Consent(values.getValue("analytics"), values.getValue("attribution"), values.getValue("adSharing"))
    }

    private companion object {
        val KEYS = setOf("analytics", "attribution", "adSharing")
    }
}
