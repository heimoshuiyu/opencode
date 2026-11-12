package ai.opencode.android

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Test the adapter-absent MCP contract and the Android NDEF wire codec; not an RF/tag write test. */
internal fun nfcSmoke(context: Context): String {
    val server = DeviceMcp(context, { error("Unexpected location call") }, { _, _ -> error("Unexpected foreground request") })
    try {
        server.start(10000, true)
        fun call(op: String): Pair<Boolean, String> {
            val a = JSONObject().put("operation", op); if (op == "write") a.put("text", "smoke")
            val b = JSONObject().put("jsonrpc","2.0").put("id",1).put("method","tools/call").put("params",JSONObject().put("name","nfc").put("arguments",a)).toString().toByteArray()
            val c = URL("http://127.0.0.1:${server.listeningPort}/mcp").openConnection() as HttpURLConnection
            try {
                c.requestMethod="POST"; c.doOutput=true; c.connectTimeout=5000; c.readTimeout=10000
                c.setRequestProperty("Authorization","Bearer ${server.token}"); c.setRequestProperty("Content-Type","application/json"); c.setFixedLengthStreamingMode(b.size)
                c.outputStream.use { it.write(b) }; val r=JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("result")
                return r.getBoolean("isError") to r.getJSONArray("content").getJSONObject(0).getString("text")
            } finally { c.disconnect() }
        }
        val info = call("info"); check(!info.first); val status=JSONObject(info.second)
        val present=NfcAdapter.getDefaultAdapter(context)!=null; check(status.getBoolean("present")==present)
        if (!present) for (op in listOf("read","write")) { val response=call(op); check(response.first && response.second=="No NFC adapter") }
        val text="测试 OpenCode"; val utf8=DeviceNfc.textMessage(text).records.single()
        val uri=NdefRecord.createUri("https://example.org/")
        val binary=NdefRecord.createMime("application/octet-stream", byteArrayOf(0,1,-1))
        val encoded=NdefMessage(utf8,uri,binary).toByteArray(); val decoded=DeviceNfc.decode(NdefMessage(encoded),true)
        check(decoded.length()==3 && decoded.getJSONObject(0).getString("text")==text && decoded.getJSONObject(1).getString("uri")=="https://example.org/")
        check(Base64.decode(decoded.getJSONObject(2).getString("payloadBase64"),Base64.DEFAULT).contentEquals(byteArrayOf(0,1,-1)))
        check(!DeviceNfc.decode(NdefMessage(encoded),false).getJSONObject(0).has("payloadBase64"))
        check(runCatching { DeviceNfc.textMessage("x".repeat(4097)) }.isFailure)
        return "MCP info=$status; missing-adapter read/write checks=${if(present) "not applicable" else "passed"}; NDEF text/URI/binary wire round-trip passed; oversized write rejected; no RF transfer exercised"
    } finally { server.stop() }
}
