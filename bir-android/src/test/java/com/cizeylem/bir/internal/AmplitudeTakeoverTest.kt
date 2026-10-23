package com.cizeylem.bir.internal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// targetSdk 36 Java 21 ister; ortamda JDK 17 var, bu yüzden sandbox SDK 34'e sabitlenir.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmplitudeTakeoverTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val dataDir = File(ctx.applicationInfo.dataDir)
    private val a = "11111111-2222-4333-8444-555555555555"
    private val b = "66666666-2222-4333-8444-555555555555"

    private fun identity(instance: String, body: String): File {
        val f = File(dataDir, "app_amplitude/${ctx.packageName}/$instance/analytics/identity.properties")
        f.parentFile!!.mkdirs()
        f.writeText(body)
        return f
    }

    private fun legacyDb(name: String = "com.amplitude.api", deviceId: String?, optOut: Int? = null): File {
        val f = File(dataDir, "databases/$name")
        f.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("CREATE TABLE store (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE long_store (key TEXT PRIMARY KEY, value INTEGER)")
        if (deviceId != null) db.execSQL("INSERT INTO store VALUES ('device_id', ?)", arrayOf(deviceId))
        if (optOut != null) db.execSQL("INSERT INTO long_store VALUES ('opt_out', ?)", arrayOf<Any>(optOut))
        db.close()
        return f
    }

    private fun prefs(deviceId: String, optOut: Boolean? = null) {
        val p = ctx.getSharedPreferences("com.amplitude.api.${ctx.packageName}", Context.MODE_PRIVATE)
        val e = p.edit().putString("com.amplitude.api.deviceId", deviceId)
        if (optOut != null) e.putBoolean("com.amplitude.api.optOut", optOut)
        e.commit()
    }

    private fun sha(f: File) =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun `configured deviceId her seyin onunde gelir`() {
        identity("\$default_instance", "device_id=$b")
        legacyDb(deviceId = b)
        prefs(b)
        assertEquals(Takeover(a, "configured", false), AmplitudeTakeover.find(ctx, a))
    }

    @Test fun `identity_properties'ten device_id okunur, source amplitude-kotlin`() {
        identity("\$default_instance", "user_id=u1\ndevice_id=$a\napi_key=k")
        assertEquals(Takeover(a, "amplitude-kotlin", false), AmplitudeTakeover.find(ctx, null))
    }

    @Test fun `default olmayan instance alfabetik sirayla denenir`() {
        identity("zeta", "device_id=$b")
        identity("alfa", "device_id=$a")
        assertEquals(a, AmplitudeTakeover.find(ctx, null)?.deviceId)
    }

    @Test fun `default instance digerlerinden once gelir`() {
        identity("alfa", "device_id=$b")
        identity("\$default_instance", "device_id=$a")
        assertEquals(a, AmplitudeTakeover.find(ctx, null)?.deviceId)
    }

    @Test fun `eski SQLite store tablosundan okunur`() {
        legacyDb(deviceId = a, optOut = 0)
        assertEquals(Takeover(a, "amplitude-sqlite", false), AmplitudeTakeover.find(ctx, null))
    }

    @Test fun `SharedPreferences son care olarak okunur`() {
        prefs(a)
        assertEquals(Takeover(a, "amplitude-prefs", false), AmplitudeTakeover.find(ctx, null))
    }

    @Test fun `gecersiz deger listesi elenir ve bir sonraki kaynaga gecilir`() {
        val bad = listOf(
            "", "9774d56d682e549c", "unknown", "000000000000000", "Android", "DEFACE",
            "00000000-0000-0000-0000-000000000000",
            "9774D56D682E549C", "UNKNOWN", "android", "deface", // büyük/küçük harf duyarsız
            "${a}S", // Amplitude-Android'in kendi ürettiği/terk ettiği "S" sonekli kimlik
        )
        for (v in bad) {
            File(dataDir, "app_amplitude").deleteRecursively()
            identity("\$default_instance", "device_id=$v")
            prefs(a)
            assertEquals("değer: '$v'", Takeover(a, "amplitude-prefs", false), AmplitudeTakeover.find(ctx, null))
        }
    }

    @Test fun `gecersiz configured atlanir`() {
        prefs(a)
        assertEquals("amplitude-prefs", AmplitudeTakeover.find(ctx, "unknown")?.source)
    }

    @Test fun `hicbir kaynak yoksa null`() {
        assertNull(AmplitudeTakeover.find(ctx, null))
    }

    @Test fun `bozuk SQLite dosyasi atlanir`() {
        val f = File(dataDir, "databases/com.amplitude.api")
        f.parentFile!!.mkdirs()
        f.writeText("bu bir sqlite dosyasi degil")
        prefs(a)
        assertEquals("amplitude-prefs", AmplitudeTakeover.find(ctx, null)?.source)
    }

    @Test fun `okunan Amplitude kaydi degismeden kalir`() {
        val p = identity("\$default_instance", "device_id=$a")
        val d = legacyDb(deviceId = b, optOut = 1)
        prefs(b)
        val px = File(dataDir, "shared_prefs/com.amplitude.api.${ctx.packageName}.xml")
        val files = listOf(p, d, px)
        val before = files.map { sha(it) to it.lastModified() }
        val treeBefore = dataDir.walkTopDown().map { it.relativeTo(dataDir).path }.toSet()

        // Kotlin kaynağı kazanır; sonra onu kaldırıp SQLite ve prefs yollarını da gezdiriyoruz.
        AmplitudeTakeover.find(ctx, null)
        assertEquals(before[0], sha(p) to p.lastModified())
        p.delete()
        AmplitudeTakeover.find(ctx, null)
        d.delete()
        AmplitudeTakeover.find(ctx, null)
        assertEquals(before[2], sha(px) to px.lastModified())
        assertEquals(treeBefore - setOf(p.relativeTo(dataDir).path, d.relativeTo(dataDir).path),
            dataDir.walkTopDown().map { it.relativeTo(dataDir).path }.toSet())
    }

    @Test fun `SQLite yolu salt okunur ve dosya baytlari ayni kalir`() {
        val d = legacyDb(deviceId = b, optOut = 1)
        val before = sha(d)
        val t = d.lastModified()
        assertEquals(Takeover(b, "amplitude-sqlite", true), AmplitudeTakeover.find(ctx, null))
        assertEquals(before, sha(d))
        assertEquals(t, d.lastModified())
        assertFalse(File(d.parentFile, "com.amplitude.api-journal").exists())
    }

    @Test fun `arama dizin yaratmaz`() {
        AmplitudeTakeover.find(ctx, null)
        assertFalse(File(dataDir, "app_amplitude").exists())
        assertFalse(File(dataDir, "databases/com.amplitude.api").exists())
    }

    @Test fun `optOut true ise korunur`() {
        legacyDb(deviceId = a, optOut = 1)
        assertEquals(Takeover(a, "amplitude-sqlite", true), AmplitudeTakeover.find(ctx, null))
        File(dataDir, "databases").deleteRecursively()
        prefs(b, optOut = true)
        assertEquals(Takeover(b, "amplitude-prefs", true), AmplitudeTakeover.find(ctx, null))
    }

    @Test fun `kotlin kaynagi optOut tasimaz ve false varsayilir`() {
        identity("\$default_instance", "device_id=$a")
        assertEquals(false, AmplitudeTakeover.find(ctx, null)!!.optOut)
        assertNotNull(AmplitudeTakeover.find(ctx, null))
    }

}
