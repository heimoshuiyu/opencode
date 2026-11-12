package ai.opencode.android

import android.content.Context
import android.os.CancellationSignal
import android.provider.Telephony.Sms
import org.json.JSONArray
import org.json.JSONObject

/** One bounded SMS page. No caller-supplied URI, projection, SQL, or background subscription. */
internal object SmsMessages {
    const val DATE_ASC = "date ASC"
    const val DATE_DESC = "date DESC"
    val dateSortOrders = listOf(DATE_DESC, DATE_ASC)
    val boxes = mapOf("all" to Sms.MESSAGE_TYPE_ALL, "inbox" to Sms.MESSAGE_TYPE_INBOX,
        "sent" to Sms.MESSAGE_TYPE_SENT, "draft" to Sms.MESSAGE_TYPE_DRAFT, "outbox" to Sms.MESSAGE_TYPE_OUTBOX,
        "failed" to Sms.MESSAGE_TYPE_FAILED, "queued" to Sms.MESSAGE_TYPE_QUEUED)

    fun request(name: String, args: JSONObject): JSONObject {
        val mode = args.optString("mode", "messages")
        require(mode in listOf("messages", "conversations")) { "Unknown SMS mode" }
        val grouped = mode == "conversations"
        val fields = setOf("type", "address", "limit", "offset") + (if (name == "sms_list") setOf("mode", "bodyPrefix", "messageSortOrder", "messageReturnNoOrderReverse") else emptySet()) +
            (if (grouped && name == "sms_list") setOf("threadId", "conversationLimit", "conversationOffset", "conversationSortOrder", "conversationReturnNoOrderReverse", "conversationReturnNestedView") else emptySet())
        require(args.keys().asSequence().all { it in fields }) { "Unknown SMS filter for this mode" }
        require(name != "sms_inbox" || !args.has("type")) { "sms_inbox is inbox-only" }
        val box = if (name == "sms_inbox") "inbox" else args.optString("type", "all")
        require(box in boxes) { "Unknown SMS mailbox" }
        fun integer(key: String, default: Int, range: IntRange): Int {
            if (!args.has(key)) return default
            val value = args.get(key); require(value is Int && value in range) { "$key must be an integer in $range" }; return value
        }
        val limit = integer("limit", if (grouped) 1 else 10, 1..50)
        val query = JSONObject().put("type", box).put("limit", limit).put("offset", integer("offset", 0, 0..10000))
        fun dateSort(key: String): String {
            val sort = args.opt(key) ?: DATE_DESC
            require(sort is String && sort in dateSortOrders) { "$key must be date ASC or date DESC" }
            return sort
        }
        query.put("messageSortOrder", dateSort("messageSortOrder"))
        for (key in listOf("messageReturnNoOrderReverse", "conversationReturnNoOrderReverse", "conversationReturnNestedView")) {
            if (args.has(key)) query.put(key, args.get(key) as? Boolean ?: error("$key must be a boolean"))
        }
        if (grouped) {
            if (args.has("threadId")) {
                val id = args.get("threadId")
                require(id is Int || id is Long) { "threadId must be an integer" }
                query.put("threadId", (id as Number).toLong())
            }
            val groups = integer("conversationLimit", minOf(10, 50 / limit), 1..50)
            require(groups * limit <= 50) { "At most 50 messages across all conversations per request" }
            query.put("conversationLimit", groups).put("conversationOffset", integer("conversationOffset", 0, 0..10000))
                .put("conversationSortOrder", dateSort("conversationSortOrder"))
        }
        if (args.has("address")) {
            val address = args.get("address")
            require(address is String && address.isNotBlank() && address.length <= 64 && address.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) { "Invalid SMS address" }
            query.put("address", address)
        }
        if (args.has("bodyPrefix")) {
            val prefix = args.get("bodyPrefix")
            require(prefix is String && prefix.isNotBlank() && prefix.length <= 128 && prefix.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) { "Invalid bodyPrefix: expected nonblank text up to 128 UTF-16 units without control or format characters" }
            query.put("bodyPrefix", prefix)
        }
        return query
    }

    fun page(context: Context, query: JSONObject, signal: CancellationSignal): JSONObject {
        val box = query.getString("type")
        val where = mutableListOf<String>(); val values = mutableListOf<String>()
        if (box != "all") { where.add("${Sms.TYPE} = ?"); values.add(boxes.getValue(box).toString()) }
        if (query.has("address")) { where.add("${Sms.ADDRESS} = ?"); values.add(query.getString("address")) }
        if (query.has("bodyPrefix")) { where.add("instr(${Sms.BODY}, ?) = 1"); values.add(query.getString("bodyPrefix")) }
        val newestFirst = "${Sms.DATE} DESC, ${Sms._ID} DESC"
        val messageOrder = if (query.getString("messageSortOrder") == DATE_ASC) "${Sms.DATE} ASC, ${Sms._ID} ASC" else newestFirst
        fun cursor(columns: Array<String>, order: String, thread: Long? = null): android.database.Cursor {
            val predicates = if (thread == null) where else where + "${Sms.THREAD_ID} = ?"
            val args = if (thread == null) values else values + thread.toString()
            return context.contentResolver.query(Sms.CONTENT_URI, columns, predicates.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
                args.takeIf { it.isNotEmpty() }?.toTypedArray(), order, signal) ?: error("SMS provider unavailable")
        }
        var chars = 0 // One shared body budget, not one budget per thread.
        fun messages(thread: Long? = null): JSONArray {
            val rows = mutableListOf<JSONObject>()
            cursor(arrayOf(Sms.TYPE, Sms.ADDRESS, Sms.BODY, Sms.DATE), messageOrder, thread).use { c ->
                c.moveToPosition(query.getInt("offset") - 1)
                while (rows.size < query.getInt("limit") && c.moveToNext()) {
                    signal.throwIfCanceled()
                    val body = c.getString(2); chars += body?.length ?: 0
                    require(chars <= 65536) { "SMS page too large; request fewer messages" }
                    rows.add(JSONObject().put("type", boxes.entries.firstOrNull { it.value == c.getInt(0) }?.key ?: "unknown")
                        .put("address", c.getString(1) ?: JSONObject.NULL).put("body", body ?: JSONObject.NULL).put("timestamp", c.getLong(3)))
                }
            }
            return JSONArray(if (query.optBoolean("messageReturnNoOrderReverse")) rows else rows.asReversed())
        }
        if (!query.has("conversationLimit")) return JSONObject().put("messages", messages())

        // Rank threads by their most recent matching message, before applying per-thread offsets.
        // Discover the requested page using IDs only; don't read bodies to find conversations.
        val skip = query.getInt("conversationOffset"); val count = query.getInt("conversationLimit")
        val oldestActivityFirst = query.getString("conversationSortOrder") == DATE_ASC
        val latest = linkedSetOf<Long>()
        val threadFilter = if (query.has("threadId")) query.getLong("threadId") else null
        cursor(arrayOf(Sms.THREAD_ID), newestFirst, threadFilter).use { c ->
            // First occurrence is the maximum matching (date, _id) for each thread. ASC
            // reverses that distinct thread ranking, not the underlying SMS cursor.
            while ((oldestActivityFirst || latest.size < skip + count) && c.moveToNext()) {
                signal.throwIfCanceled()
                latest.add(c.getLong(0))
            }
        }
        val ranked = if (oldestActivityFirst) latest.toList().asReversed() else latest
        val page = ranked.asSequence().drop(skip).take(count).toList()
        val ordered = if (query.optBoolean("conversationReturnNoOrderReverse")) page else page.asReversed()
        val nested = query.optBoolean("conversationReturnNestedView", true)
        val output = JSONArray()
        for (id in ordered) {
            val items = messages(id)
            if (nested) output.put(JSONObject().put("threadId", id).put("messages", items))
            else for (i in 0 until items.length()) output.put(items.getJSONObject(i))
        }
        return JSONObject().put(if (nested) "conversations" else "messages", output)
    }
}
