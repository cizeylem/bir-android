package com.cizeylem.bir.internal

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Küçük dosyayı atomik yazar: `<ad>.tmp` + fsync + `renameTo`. Başarısızlıkta IOException.
 * Rename'den önce diske indirilir; aksi halde çökmede boş dosya kalabilir.
 *
 * EventQueue bilerek kullanmaz: 5 MB'a varan kuyruğu tek ByteArray'e toplamak yerine satır satır akıtır,
 * kendi kilidi ve sayaç önbelleği altında yazar ve hata türü olarak IllegalStateException bekler.
 */
internal fun atomicWrite(file: File, bytes: ByteArray) {
    val parent = file.parentFile ?: throw IOException("üst dizin yok: $file")
    if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
        throw IOException("dizin oluşturulamadı: $parent")
    }
    val tmp = File(parent, file.name + ".tmp")
    FileOutputStream(tmp).use { out ->
        out.write(bytes)
        out.flush()
        out.fd.sync()
    }
    if (!tmp.renameTo(file)) {
        // Bazı dosya sistemleri hedef varken rename'i reddeder.
        file.delete()
        if (!tmp.renameTo(file)) throw IOException("${file.name} yerine konamadı")
    }
}
