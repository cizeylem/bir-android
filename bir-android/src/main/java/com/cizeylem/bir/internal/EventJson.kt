package com.cizeylem.bir.internal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** `$set`/`$set_once`/`$unset`/`$add` operasyonları; hepsi null ya da boşsa hiçbiri yazılmaz. */
internal data class PropertyOps(
    val set: Map<String, Any?>?,
    val setOnce: Map<String, Any?>?,
    val unset: List<String>?,
    val add: Map<String, Number>?,
)

internal data class Batch(val body: String, val skipped: Int)

/**
 * Protokol gövdesi (SPEC §2). Opsiyonel alanlar null ya da boşken HİÇ yazılmaz: sunucu `$` önekli
 * bir prop anahtarı görürse bütün event'i düşürür, boş operasyon map'i de riske girmesin.
 */
internal object EventJson {
    fun event(
        name: String,
        distinctId: String,
        time: Long,
        insertId: String,
        sessionId: String,
        props: Map<String, Any?>?,
        ops: PropertyOps?,
        userId: String?,
        context: Map<String, Any?>,
    ): String {
        val o = JSONObject()
        o.put("event", name)
        o.put("distinct_id", distinctId)
        o.put("time", time)
        o.put("insert_id", insertId)
        o.put("session_id", sessionId)
        o.put("context", obj(context))
        if (!props.isNullOrEmpty()) o.put("props", obj(props))
        if (ops != null) {
            if (!ops.set.isNullOrEmpty()) o.put("\$set", obj(ops.set))
            if (!ops.setOnce.isNullOrEmpty()) o.put("\$set_once", obj(ops.setOnce))
            if (!ops.unset.isNullOrEmpty()) o.put("\$unset", JSONArray(ops.unset))
            if (!ops.add.isNullOrEmpty()) o.put("\$add", obj(ops.add))
        }
        if (!userId.isNullOrEmpty()) o.put("user_id", userId)
        return o.toString()
    }

    /**
     * Satırlar zaten JSON metni (EventQueue yorumlamaz); yeniden kodlanır, string olarak gömülmez.
     * Çökmede yarım yazılmış satır olabilir; fırlatsaydık gönderim aynı satırda sonsuza dek tıkanırdı (poison
     * message). Bozuk satır atlanır, sayısı çağırana döner (çağıran gönderilen sayı kadar sildiği için kuyruktan düşer).
     */
    fun batch(sentAt: Long, lines: List<String>): Batch {
        val arr = JSONArray()
        var skipped = 0
        for (l in lines) {
            try { arr.put(JSONObject(l)) } catch (e: JSONException) { skipped++ }
        }
        return Batch(JSONObject().put("sent_at", sentAt).put("events", arr).toString(), skipped)
    }

    /**
     * Kimliksiz attribution gövdesi (SPEC §7): düz bir nesne, `sent_at` + ham alanlar.
     *
     * Kimlik alanları BURADA da süzülür: sunucu gövdede kimlik görürse 400 döner ve sinyal tamamen
     * kaybolur. Süzme çağıranın dikkatine bırakılamayacak kadar kritik, bu yüzden son kapı burasıdır.
     */
    fun attributionAnon(sentAt: Long, fields: Map<String, Any?>): String {
        val o = JSONObject()
        o.put("sent_at", sentAt)
        for ((k, v) in fields) {
            if (k in IDENTITY_KEYS) continue
            o.put(k, wrap(v))
        }
        return o.toString()
    }

    private val IDENTITY_KEYS = setOf("device_id", "user_id", "distinct_id")

    private fun obj(m: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        // put(k, null) anahtarı siler; açık null için JSONObject.NULL gerekir.
        for ((k, v) in m) o.put(k, wrap(v))
        return o
    }

    private fun wrap(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> for ((k, x) in v) o.put(k.toString(), wrap(x)) }
        is Iterable<*> -> JSONArray().also { a -> for (x in v) a.put(wrap(x)) }
        is Array<*> -> JSONArray().also { a -> for (x in v) a.put(wrap(x)) }
        // NaN/Infinity JSON'da yok, org.json fırlatır; event'i düşürmek yerine null yazılır.
        is Double -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v
        is Float -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v
        is Number, is Boolean, is String -> v
        else -> v.toString()
    }
}
