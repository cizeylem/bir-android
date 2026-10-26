package com.cizeylem.bir.attribution

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * "Bu sinyal okundu" işaretleri. Dosya: `<noBackupDir>/cizeylem/attr_state`, JSON `{"<sinyal>": true}`.
 *
 * Salt-okuma yolu dizin yaratmaz (çekirdekteki kural): `mkdirs` yalnız [markDone]'da.
 * Okunamayan/bozuk dosya "işaret yok" sayılır; sonuç en kötü ihtimalle bir kez daha okumaktır, sinyal kaybı değil.
 */
internal class AttributionState(private val noBackupDir: File?) {
    private val file: File? = noBackupDir?.let { File(File(it, "cizeylem"), "attr_state") }

    fun isDone(key: String): Boolean = synchronized(LOCK) { load().optBoolean(key, false) }

    /** Mevcut diğer işaretleri koruyarak ekler (Görev 13 aynı dosyayı kullanır). Başarısızlıkta IOException. */
    fun markDone(key: String): Unit = synchronized(LOCK) {
        val target = file ?: throw IOException("noBackupDir yok")
        val json = load()
        try { json.put(key, true) } catch (e: JSONException) { throw IOException(e) }
        writeAtomic(target, json.toString().toByteArray(Charsets.UTF_8))
    }

    private companion object {
        // Süreç geneli kilit: her okuma yeni örnek yaratır, örnek kilidi okuma-değiştirme-yazmayı korumaz
        // (Görev 13 aynı dosyaya yazar; paralel markDone bir işareti kaybedebilirdi).
        val LOCK = Any()
    }

    private fun load(): JSONObject {
        val f = file ?: return JSONObject()
        if (noBackupDir?.isDirectory != true || !f.isFile) return JSONObject()
        return try {
            JSONObject(f.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            JSONObject()
        } catch (e: JSONException) {
            JSONObject()
        }
    }

    /**
     * Çekirdekteki `internal atomicWrite` başka modülden görünmez; `public` yapmak genel API'yi büyütürdü.
     * Aynı yöntem: `.tmp` + fsync + rename (çökmede yarım dosya kalmasın).
     */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("üst dizin yok: $target")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("dizin oluşturulamadı: $parent")
        }
        val tmp = File(parent, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("${target.name} yerine konamadı")
        }
    }
}
