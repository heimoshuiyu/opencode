package ai.opencode.android

import android.content.Context
import android.os.CancellationSignal
import android.provider.ContactsContract.CommonDataKinds.Phone
import org.json.JSONArray
import org.json.JSONObject

internal object PhoneContacts {
    fun page(context: Context, args: JSONObject, signal: CancellationSignal): JSONObject {
        val limit = args.getInt("limit"); val offset = args.getInt("offset")
        val rows = JSONArray()
        val fields = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER)
        val order = "${Phone.DISPLAY_NAME} ASC, ${Phone.CONTACT_ID} ASC, ${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.IS_PRIMARY} DESC, ${Phone._ID} ASC"
        val cursor = context.contentResolver.query(Phone.CONTENT_URI, fields, "${Phone.NUMBER} IS NOT NULL AND ${Phone.NUMBER} != ?", arrayOf(""), order, signal)
            ?: error("Contacts provider unavailable")
        cursor.use { c ->
            // Count aggregate contacts, not phone rows: extra numbers never shift a page boundary.
            var previous: Long? = null
            var skipped = 0
            while (rows.length() < limit && c.moveToNext()) {
                signal.throwIfCanceled()
                val id = c.getLong(0)
                if (id == previous) continue
                previous = id
                if (skipped < offset) { skipped++; continue }
                rows.put(JSONObject().put("name", c.getString(1) ?: JSONObject.NULL).put("number", c.getString(2)))
            }
        }
        return JSONObject().put("contacts", rows)
    }
}
