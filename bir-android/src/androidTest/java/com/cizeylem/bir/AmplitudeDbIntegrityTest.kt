package com.cizeylem.bir

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.cizeylem.bir.internal.AmplitudeTakeover
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Görev 3'ten taşındı. Gerçek Android'in DefaultDatabaseErrorHandler'ı bozuk veritabanını SİLER;
 * Robolectric bunu taklit etmediği için birim testte kilitlenemedi. SDK'nın boş DatabaseErrorHandler'ı
 * Amplitude'un (bozuk bile olsa) dosyasına dokunmamalı.
 */
class AmplitudeDbIntegrityTest {
    private lateinit var app: Application
    private lateinit var dbFile: File

    @Before fun hazirla() {
        app = ApplicationProvider.getApplicationContext()
        val dir = File(app.applicationInfo.dataDir, "databases").apply { mkdirs() }
        dbFile = File(dir, "com.amplitude.api")
        dbFile.delete()
    }

    @After fun temizle() { dbFile.delete() }

    @Test fun bozukAmplitudeDosyasiSilinmezVeDegismez() {
        // SQLite başlığı olmayan, ama yeterince uzun çöp: açılışta "file is not a database" verir.
        val bozuk = ByteArray(4096) { (it * 31 + 7).toByte() }
        dbFile.writeBytes(bozuk)

        val sonuc = AmplitudeTakeover.find(app, null)

        assertNull(sonuc)
        assertTrue("Bozuk Amplitude dosyası silinmemeli", dbFile.exists())
        assertArrayEquals("Baytlar değişmemeli", bozuk, dbFile.readBytes())
    }

    @Test fun basligiSaglamGovdesiBozukDosyaSilinmezVeDegismez() {
        // Başlığı geçerli, sayfaları çöp: SQLite SQLITE_CORRUPT (11) verir; Android'in varsayılan hata
        // işleyicisi bu durumda dosyayı siler. NOTADB (yukarıdaki test) ile farklı yol.
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("CREATE TABLE store (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("INSERT INTO store VALUES ('device_id', 'abc-123-ok')")
        db.close()
        val bytes = dbFile.readBytes()
        for (i in 100 until bytes.size) bytes[i] = 0x5A
        dbFile.writeBytes(bytes)

        val sonuc = AmplitudeTakeover.find(app, null)

        assertNull(sonuc)
        assertTrue("Bozuk Amplitude dosyası silinmemeli", dbFile.exists())
        assertArrayEquals("Baytlar değişmemeli", bytes, dbFile.readBytes())
    }

    @Test fun saglamAmplitudeDosyasiOkunurVeDegismez() {
        // Karşı kontrol: sağlam DB'de devralma çalışıyor, yani yukarıdaki test boşuna geçmiyor.
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("CREATE TABLE store (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("INSERT INTO store VALUES ('device_id', 'abc-123-ok')")
        db.close()
        // Journal modu DELETE'e çekildi; WAL değil (WAL kaynakları bilerek atlanır).
        val once = dbFile.readBytes()

        val sonuc = AmplitudeTakeover.find(app, null)

        assertTrue(sonuc?.deviceId == "abc-123-ok")
        assertArrayEquals(once, dbFile.readBytes())
    }
}
