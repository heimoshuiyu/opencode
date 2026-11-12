package ai.opencode.android

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.tech.Ndef
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** One confirmed foreground tap, NDEF only. Tag contents are data, never dispatched as intents. */
internal class DeviceNfc(private val activity: Activity, private val adapter: NfcAdapter) {
    private val claimed = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var link: Ndef? = null

    fun start(message: NdefMessage?, full: Boolean, complete: (Result<JSONObject>) -> Unit) {
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE
        adapter.enableReaderMode(activity, { tag ->
            if (!claimed.compareAndSet(false, true) || closed) return@enableReaderMode
            val result = runCatching {
                val tech = Ndef.get(tag) ?: error("Tag is not NDEF-formatted")
                tech.use {
                    link = tech
                    check(!closed) { "NFC request cancelled" }; tech.connect()
                    if (message != null) {
                        check(tech.isWritable) { "Tag is read-only" }
                        require(message.byteArrayLength <= tech.maxSize) { "Text exceeds tag capacity" }
                        check(!closed) { "NFC request cancelled" }
                        tech.writeNdefMessage(message)
                        check(message == tech.ndefMessage) { "Write could not be verified; read the tag before retrying" }
                        JSONObject().put("written", true).put("verified", true).put("bytes", message.byteArrayLength)
                    } else {
                        val records = decode(tech.ndefMessage, full)
                        JSONObject().put("records", records).apply { if (full) {
                            put("id", tag.id.orEmptyBytes().joinToString("") { b -> "%02x".format(b) })
                            put("type", tech.type); put("maxBytes", tech.maxSize); put("writable", tech.isWritable)
                            put("technologies", JSONArray(tag.techList.toList()))
                        } }
                    }
                }
            }; link = null
            activity.runOnUiThread { if (!closed) complete(result) }
        }, flags, null)
    }

    fun close() {
        closed = true
        runCatching { adapter.disableReaderMode(activity) }
        runCatching { link?.close() }
    }

    companion object {
        private fun ByteArray?.orEmptyBytes() = this ?: byteArrayOf()
        fun textMessage(text: String): NdefMessage {
            require(text.toByteArray().size in 1..4096) { "Expected 1–4096 UTF-8 bytes" }
            return NdefMessage(NdefRecord.createTextRecord("en", text))
        }
        fun decode(message: NdefMessage?, full: Boolean): JSONArray = JSONArray().apply {
            if (message == null) return@apply
            require(message.byteArrayLength <= 32768) { "NDEF message is too large" }
            for (record in message.records) {
                val bytes = record.payload; val data = JSONObject()
                if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
                    require(bytes.isNotEmpty()); val status = bytes[0].toInt() and 255; val offset = 1 + (status and 63)
                    require(offset <= bytes.size) { "Malformed text record" }
                    data.put("text", String(bytes, offset, bytes.size - offset, if (status and 128 == 0) Charsets.UTF_8 else Charsets.UTF_16))
                } else record.toUri()?.let { data.put("uri", it.toString()) }
                if (full || data.length() == 0) data.put("payloadBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                if (full) data.put("tnf", record.tnf.toInt()).put("typeBase64", Base64.encodeToString(record.type, Base64.NO_WRAP))
                    .put("mime", record.toMimeType() ?: JSONObject.NULL)
                put(data)
            }
        }
    }
}
