package ai.opencode.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Test-owned, authenticated loopback server; use its advertised tool path rather than a direct handler call. */
internal fun DeviceMcp.smokeCall(name: String, args: JSONObject = JSONObject(), expectError: Boolean = false, timeoutMs: Int = 10000): String {
    val r = smokeToolResult(name, args, timeoutMs)
    check(r.getBoolean("isError") == expectError) { r.toString() }
    return r.getJSONArray("content").getJSONObject(0).getString("text")
}

internal fun DeviceMcp.smokeToolResult(name: String, args: JSONObject, timeoutMs: Int): JSONObject {
    return smokeResult("tools/call", JSONObject().put("name", name).put("arguments", args), timeoutMs)
}

internal fun DeviceMcp.smokeResult(method: String, params: JSONObject = JSONObject(), timeoutMs: Int = 10000): JSONObject {
    val c = URL("http://127.0.0.1:$listeningPort/mcp").openConnection() as HttpURLConnection
    try {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method)
            .put("params", params).toString().toByteArray()
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = timeoutMs
        c.setRequestProperty("Authorization", "Bearer $token"); c.setRequestProperty("Content-Type", "application/json")
        c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) }
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("result")
    } finally { c.disconnect() }
}
