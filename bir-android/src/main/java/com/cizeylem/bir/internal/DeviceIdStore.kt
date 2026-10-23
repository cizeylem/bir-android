package com.cizeylem.bir.internal

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Kalıcı cihaz kimliği. Dosya: `<noBackupDir>/cizeylem/device_id`.
 *
 * SharedPreferences kullanılmaz: Auto Backup onu yeni cihaza kopyalar ve iki canlı cihaz aynı id'yi taşır.
 *
 * İki "başarısız" durum bilerek ayrıdır:
 *  - Erişilemez (dizin null, oluşturulamıyor, dosya okunamıyor): `null` döner, HİÇBİR ŞEY yazılmaz.
 *    Prewarm/Direct Boot'ta kullanıcı kilidi açmadan `noBackupFilesDir` okunamaz; orada yeni id üretirsek
 *    her prewarm yeni bir "kullanıcı" yaratır. Okunamayan mevcut dosyanın üstüne yazmak da gerçek id'yi yakardı.
 *  - Okunabilir ama bozuk içerik: yeni UUID üretilip yazılır.
 */
internal class DeviceIdStore(
    private val noBackupDir: File?,
    private val uuid: () -> String,
) {
    private val file: File? = noBackupDir?.let { File(File(it, "cizeylem"), "device_id") }

    /** Geçerli id'yi döner; dosya yok, bozuk ya da erişilemezse `null`. */
    fun read(): String? = when (val r = readState()) {
        is State.Valid -> r.id
        else -> null
    }

    /** Atomik yazar: `.tmp` + fsync + `renameTo` (EventQueue ile aynı desen). Başarısızlıkta IOException. */
    fun write(id: String) {
        val target = file ?: throw IOException("noBackupDir yok")
        val parent = target.parentFile!!
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("dizin oluşturulamadı: $parent")
        }
        val tmp = File(parent, "device_id.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(id.toByteArray(Charsets.UTF_8))
            // Rename'den önce diske indir; aksi halde çökmede boş dosya kalabilir (boş dosya = yeni id).
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            // Bazı dosya sistemleri hedef varken rename'i reddeder.
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("device_id yerine konamadı")
        }
    }

    fun getOrCreate(): String? {
        when (val r = readState()) {
            is State.Valid -> return r.id
            State.Unavailable -> return null
            State.Missing, State.Corrupt -> Unit
        }
        val id = uuid()
        // Yazılamayan id'yi döndürmek her çağrıda farklı kimlik demek olurdu; bu yüzden null.
        return try { write(id); id } catch (e: IOException) { null }
    }

    private sealed class State {
        class Valid(val id: String) : State()
        object Missing : State()
        object Corrupt : State()
        object Unavailable : State()
    }

    private fun readState(): State {
        val f = file ?: return State.Unavailable
        // Dizin yoksa ve oluşturulamıyorsa (kilitli depolama) erişilemez; varsa yalnız "dosya yok" olabilir.
        val parent = f.parentFile!!
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return State.Unavailable
        if (!f.exists()) return State.Missing
        val text = try { f.readText(Charsets.UTF_8) } catch (e: IOException) { return State.Unavailable }
        // Bozuk tanımı: kırpılmış içerik 8-4-4-4-12 onaltılık UUID biçiminde değil ya da sıfır-UUID.
        // Boş/yarım yazılmış dosya ve elle bozulmuş metin böylece yakalanır; SPEC §6 sıfır-UUID'yi geçersiz sayar.
        val id = text.trim()
        return if (UUID_RE.matches(id) && id != NIL_UUID) State.Valid(id) else State.Corrupt
    }

    private companion object {
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
    }
}
