package com.cizeylem.bir.internal

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 30 dk hareketsizlik penceresi. `session_id` = oturum başlangıcının epoch ms'i (protokolde string).
 *
 * Durum düz metin `<başlangıç>|<son etkinlik>` olarak saklanır (iki Long, ondalık). org.json yerine düz metin:
 * iki sayı için JSON gereksiz ve bu sınıf saf JVM testinde (stub org.json'suz) çalışabilsin diye.
 * Pencere son etkinlikten kayar; süreç ölüp açılsa da oturum kalıcı dosyadan sürer.
 * Disk erişimi var: çağıran thread ana thread olmamalı (SPEC §3 Eşzamanlılık).
 */
internal class SessionStore(
    private val file: File,
    private val now: () -> Long,
    private val timeoutMs: Long = 1_800_000,
) {
    private var start: Long? = null
    private var last: Long = 0
    private var loaded = false

    @Synchronized
    fun current(): String {
        if (!loaded) { load(); loaded = true }
        val t = now()
        val s = start
        if (s == null || t - last > timeoutMs) start = t
        last = t
        persist()
        return start.toString()
    }

    @Synchronized
    fun reset() {
        loaded = true
        start = null
        last = 0
        file.delete()
    }

    private fun load() {
        // Okunamayan/bozuk dosya = oturum yok; yeni oturum başlar (kimlikten farklı, kaybı zararsız).
        val parts = try { file.readText(Charsets.UTF_8).trim().split('|') } catch (e: IOException) { return }
        if (parts.size != 2) return
        val s = parts[0].toLongOrNull() ?: return
        val l = parts[1].toLongOrNull() ?: return
        start = s
        last = l
    }

    /** Atomik yazar (`.tmp` + fsync + `renameTo`, DeviceIdStore ile aynı desen). Yazılamazsa bellekteki oturum sürer. */
    private fun persist() {
        try {
            val parent = file.parentFile ?: return
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return
            val tmp = File(parent, file.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write("$start|$last".toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: IOException) {
            // Kalıcılık en-iyi-çaba: disk yoksa oturum yalnız süreç ömrü boyunca tutulur.
        }
    }
}
