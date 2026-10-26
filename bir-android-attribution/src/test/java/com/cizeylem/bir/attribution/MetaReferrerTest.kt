package com.cizeylem.bir.attribution

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetaReferrerTest {
    private val fbAuth = "com.facebook.katana.provider.InstallReferrerProvider"
    private val igAuth = "com.instagram.contentprovider.InstallReferrerProvider"
    private val liteAuth = "com.facebook.lite.provider.InstallReferrerProvider"
    private val appId = "1234567890"
    private lateinit var context: Context

    /** Sorgulanan URI'leri ve cursor'ları kaydeder; cursor üretimini [make] belirler. */
    private class FakeProvider(val make: (Uri) -> Cursor?) : ContentProvider() {
        val queried = mutableListOf<Uri>()
        val cursors = mutableListOf<Cursor>()
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
            queried += uri
            return make(uri)?.also { cursors += it }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun install(pkg: String, authority: String, provider: FakeProvider) {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = pkg; versionName = "9.9" })
        pm.addOrUpdateProvider(ProviderInfo().apply {
            this.authority = authority; packageName = pkg; name = "p.$pkg"
            applicationInfo = context.packageManager.getApplicationInfo(pkg, 0)
        })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    private fun row(ref: Any?, ct: Any? = 1, ts: Any? = 1700000000L) =
        MatrixCursor(arrayOf("install_referrer", "is_ct", "actual_timestamp")).apply { addRow(arrayOf(ref, ct, ts)) }

    @Test fun `ham referrer degeri degistirilmeden dondurulur`() {
        val raw = "AbC+/=\u0000garip{blob}%41"
        val p = FakeProvider { row(raw, 1, 1700000123L) }
        install("com.facebook.katana", fbAuth, p)
        val r = MetaReferrer.read(context, appId)!!
        assertEquals(raw, r["install_referrer"])
        assertEquals(1, r["is_ct"])
        assertEquals(1700000123L, r["actual_timestamp"])
        assertEquals("content://$fbAuth/$appId", p.queried.single().toString())
    }

    @Test fun `uc uygulama sirasiyla denenir, ilk bos olmayan kazanir`() {
        val fb = FakeProvider { row("") }          // boş: sıradaki denenir
        val ig = FakeProvider { row("ig-blob") }
        val lite = FakeProvider { row("lite-blob") }
        install("com.facebook.katana", fbAuth, fb)
        install("com.instagram.android", igAuth, ig)
        install("com.facebook.lite", liteAuth, lite)
        assertEquals("ig-blob", MetaReferrer.read(context, appId)!!["install_referrer"])
        assertEquals(1, fb.queried.size)
        assertEquals(1, ig.queried.size)
        assertTrue("arama IG'de bitmeli", lite.queried.isEmpty())
        assertEquals("content://$igAuth/$appId", ig.queried.single().toString())
    }

    @Test fun `facebook cevap verirse digerlerine bakilmaz`() {
        val fb = FakeProvider { row("fb-blob") }
        val ig = FakeProvider { row("ig-blob") }
        install("com.facebook.katana", fbAuth, fb)
        install("com.instagram.android", igAuth, ig)
        assertEquals("fb-blob", MetaReferrer.read(context, appId)!!["install_referrer"])
        assertTrue(ig.queried.isEmpty())
    }

    @Test fun `lite yolu kendi authority'siyle sorgulanir`() {
        val lite = FakeProvider { row("lite-blob") }
        install("com.facebook.lite", liteAuth, lite)
        assertEquals("lite-blob", MetaReferrer.read(context, appId)!!["install_referrer"])
        assertEquals("content://$liteAuth/$appId", lite.queried.single().toString())
    }

    @Test fun `provider cozumlenemeyen uygulama sorgulanmaz`() {
        val p = FakeProvider { row("x") }
        ShadowContentResolver.registerProviderInternal(fbAuth, p) // paket/provider kaydı yok
        assertNull(MetaReferrer.read(context, appId))
        assertTrue(p.queried.isEmpty())
    }

    @Test fun `hicbir uygulama yoksa null doner`() {
        assertNull(MetaReferrer.read(context, appId))
    }

    @Test fun `bos cursor ve null cursor null doner`() {
        install("com.facebook.katana", fbAuth, FakeProvider { MatrixCursor(arrayOf("install_referrer")) })
        install("com.instagram.android", igAuth, FakeProvider { null })
        assertNull(MetaReferrer.read(context, appId))
    }

    @Test fun `provider istisna firlatirsa null doner ve siradaki denenir`() {
        install("com.facebook.katana", fbAuth, FakeProvider { throw SecurityException("izin yok") })
        val ig = FakeProvider { row("ig-blob") }
        install("com.instagram.android", igAuth, ig)
        assertEquals("ig-blob", MetaReferrer.read(context, appId)!!["install_referrer"])
        val only = FakeProvider { throw IllegalStateException("bozuk") }
        // tek başına istisna: null
        ShadowContentResolver.registerProviderInternal(igAuth, only)
        assertNull(MetaReferrer.read(context, appId))
    }

    @Test fun `facebook app id yoksa ya da bos ise kaynak atlanir`() {
        val p = FakeProvider { row("x") }
        install("com.facebook.katana", fbAuth, p)
        assertNull(MetaReferrer.read(context, null))
        assertNull(MetaReferrer.read(context, "  "))
        assertTrue(p.queried.isEmpty())
    }

    @Test fun `beklenmedik tipli alan yazilmaz, cokmez`() {
        install("com.facebook.katana", fbAuth, FakeProvider { row("blob", "bir", 1.5) })
        val r = MetaReferrer.read(context, appId)!!
        assertEquals("blob", r["install_referrer"])
        assertFalse(r.containsKey("is_ct"))
        assertFalse(r.containsKey("actual_timestamp"))
    }

    @Test fun `install_referrer string degilse sonuc yok`() {
        install("com.facebook.katana", fbAuth, FakeProvider { row(42) })
        assertNull(MetaReferrer.read(context, appId))
    }

    @Test fun `eksik sutunlar yalniz referrer ile doner`() {
        install("com.facebook.katana", fbAuth, FakeProvider {
            MatrixCursor(arrayOf("install_referrer")).apply { addRow(arrayOf<Any?>("blob")) }
        })
        assertEquals(mapOf<String, Any?>("install_referrer" to "blob"), MetaReferrer.read(context, appId))
    }

    @Test fun `cursor her yolda kapatilir`() {
        val ok = FakeProvider { row("blob") }
        install("com.facebook.katana", fbAuth, ok)
        MetaReferrer.read(context, appId)
        assertTrue(ok.cursors.all { it.isClosed })
        val empty = FakeProvider { row("") }
        install("com.instagram.android", igAuth, empty)
        ShadowContentResolver.registerProviderInternal(fbAuth, empty)
        MetaReferrer.read(context, appId)
        assertTrue(empty.cursors.isNotEmpty() && empty.cursors.all { it.isClosed })
    }
}
