package com.cizeylem.bir.internal

import java.io.File
import java.io.IOException

/**
 * 30 dk hareketsizlik penceresi. `session_id` = oturum başlangıcının epoch ms'i (protokolde string).
 *
 * Durum düz metin `<başlangıç>|<son etkinlik>` olarak saklanır (iki Long, ondalık). org.json yerine düz metin:
 * iki sayı için JSON gereksiz ve bu sınıf saf JVM testinde (stub org.json'suz) çalışabilsin diye.
 * Pencere son etkinlikten kayar (son etkinlik en çok 60 sn gecikmeyle yazılır); süreç ölüp açılsa da oturum kalıcı dosyadan sürer.
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
    private var lastPersisted = 0L

    @Synchronized
    fun current(): String {
        if (!loaded) { load(); loaded = true }
        val t = now()
        val s = start
        val started = s == null || t - last > timeoutMs
        if (started) start = t
        last = t
        // fsync TV eMMC'sinde onlarca ms sürer; `last` yalnız kayan pencere için tutulur. Bu yüzden yalnız
        // oturum başlayınca ya da son yazımdan ≥60 sn sonra yazılır; çökmede last en fazla 60 sn eski kalır.
        if (started || t - lastPersisted >= PERSIST_INTERVAL_MS) persist()
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
        lastPersisted = l
    }

    /** Yazılamazsa bellekteki oturum sürer: kalıcılık en-iyi-çaba. */
    private fun persist() {
        try {
            atomicWrite(file, "$start|$last".toByteArray(Charsets.UTF_8))
            lastPersisted = last
        } catch (e: IOException) {
            // Disk yoksa oturum yalnız süreç ömrü boyunca tutulur.
        }
    }

    private companion object {
        const val PERSIST_INTERVAL_MS = 60_000L
    }
}
