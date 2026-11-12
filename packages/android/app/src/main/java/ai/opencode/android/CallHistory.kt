package ai.opencode.android

import android.content.Context
import android.os.CancellationSignal
import android.provider.CallLog.Calls
import org.json.JSONArray
import org.json.JSONObject

internal fun phonePageArgs(args: JSONObject): JSONObject {
    require(args.keys().asSequence().all { it == "limit" || it == "offset" }) { "Expected only limit and offset" }
    fun integer(key: String, default: Int, range: IntRange): Int {
        if (!args.has(key)) return default
        val n = args.get(key); require(n is Int && n in range) { "$key must be an integer in $range" }; return n
    }
    return JSONObject().put("limit", integer("limit", 10, 1..50)).put("offset", integer("offset", 0, 0..10000))
}

internal object CallHistory {
    fun page(context: Context, args: JSONObject, signal: CancellationSignal): JSONObject {
        val limit = args.getInt("limit")
        val uri = Calls.CONTENT_URI.buildUpon().appendQueryParameter(Calls.LIMIT_PARAM_KEY, limit.toString())
            .appendQueryParameter(Calls.OFFSET_PARAM_KEY, args.getInt("offset").toString()).build()
        val rows = mutableListOf<JSONObject>()
        val fields = arrayOf(Calls.NUMBER, Calls.TYPE, Calls.DATE, Calls.DURATION, Calls.NUMBER_PRESENTATION)
        val cursor = context.contentResolver.query(uri, fields, null, null, "${Calls.DATE} DESC, ${Calls._ID} DESC", signal)
            ?: error("Call log unavailable")
        cursor.use { c ->
            while (rows.size < limit && c.moveToNext()) {
                signal.throwIfCanceled()
                val type = when (c.getInt(1)) {
                    Calls.INCOMING_TYPE -> "incoming"
                    Calls.OUTGOING_TYPE -> "outgoing"
                    Calls.MISSED_TYPE -> "missed"
                    Calls.REJECTED_TYPE -> "rejected"
                    Calls.BLOCKED_TYPE -> "blocked"
                    Calls.VOICEMAIL_TYPE -> "voicemail"
                    Calls.ANSWERED_EXTERNALLY_TYPE -> "answered_externally"
                    else -> "unknown"
                }
                val number = if (c.getInt(4) == Calls.PRESENTATION_ALLOWED) c.getString(0) else null
                rows.add(JSONObject().put("number", number ?: JSONObject.NULL).put("type", type)
                    .put("timestamp", c.getLong(2)).put("durationSeconds", c.getLong(3)))
            }
        }
        return JSONObject().put("calls", JSONArray(rows.asReversed()))
    }
}
