package com.cizeylem.bir.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json birim testlerde android.jar stub'ıdır ("Stub!" fırlatır); Robolectric gerçek uygulamayı getirir.
// SDK 34: ortamdaki JDK 17, targetSdk 36'nın istediği Java 21'i karşılamıyor.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventJsonTest {
    private val ctx = mapOf<String, Any?>("platform" to "android", "screen_width" to 1080)

    private fun ev(
        props: Map<String, Any?>? = null,
        ops: PropertyOps? = null,
        userId: String? = null,
        name: String = "cz_app_opened",
    ) = JSONObject(EventJson.event(name, "dev-1", 1_700_000_000_000L, "ins-1", "1699999999000", props, ops, userId, ctx))

    @Test fun `zorunlu alanlar event, distinct_id, time, insert_id yazilir`() {
        val o = ev(props = mapOf("a" to 1, "b" to "x"))
        assertEquals("cz_app_opened", o.getString("event"))
        assertEquals("dev-1", o.getString("distinct_id"))
        assertEquals(1_700_000_000_000L, o.getLong("time"))
        assertEquals("ins-1", o.getString("insert_id"))
        // session_id protokolde string.
        assertEquals("1699999999000", o.get("session_id"))
        assertEquals(1080, o.getJSONObject("context").getInt("screen_width"))
        assertEquals(1, o.getJSONObject("props").getInt("a"))
        assertFalse(o.has("user_id"))
    }

    @Test fun `bos props ve bos operasyonlar goveye yazilmaz`() {
        val empty = PropertyOps(emptyMap(), emptyMap(), emptyList(), emptyMap())
        for (o in listOf(ev(), ev(props = emptyMap(), ops = empty), ev(ops = PropertyOps(null, null, null, null)))) {
            for (k in listOf("props", "\$set", "\$set_once", "\$unset", "\$add", "user_id")) assertFalse(k, o.has(k))
        }
    }

    @Test fun `dolu operasyonlar yalniz dolu alanlar yazilir`() {
        val o = ev(ops = PropertyOps(mapOf("plan" to "pro", "gone" to null), null, listOf("old"), mapOf("n" to 2)))
        assertEquals("pro", o.getJSONObject("\$set").getString("plan"))
        assertTrue(o.getJSONObject("\$set").isNull("gone"))
        assertEquals("old", o.getJSONArray("\$unset").getString(0))
        assertEquals(2, o.getJSONObject("\$add").getInt("n"))
        assertFalse(o.has("\$set_once"))
    }

    @Test fun `identify govdesinde user_id bulunur`() {
        val o = ev(name = "\$identify", userId = "u-42")
        assertEquals("u-42", o.getString("user_id"))
    }

    @Test fun `batch govdesi sent_at ve events dizisi icerir`() {
        val a = EventJson.event("a", "d", 1L, "i1", "1", null, null, null, ctx)
        val b = EventJson.event("b", "d", 2L, "i2", "1", null, null, null, ctx)
        val body = JSONObject(EventJson.batch(5L, listOf(a, b)))
        assertEquals(5L, body.getLong("sent_at"))
        val arr: JSONArray = body.getJSONArray("events")
        assertEquals(2, arr.length())
        assertEquals("i2", arr.getJSONObject(1).getString("insert_id"))
    }
}
