package ai.opencode.android

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** Real authenticated MCP HTTP requests against the APK implementation and a generated 12s PCM WAV. */
internal fun mediaPlayerSmoke(context: Context): String {
    val directory = File(context.filesDir, "home/projects").apply { mkdirs() }
    val file = File.createTempFile("player-smoke-", ".wav", directory)
    val rate = 8000; val samples = rate * 12
    val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
    wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(samples * 2)
    repeat(samples) { wav.putShort((512 * sin(2 * Math.PI * 440 * it / rate)).toInt().toShort()) }
    file.writeBytes(wav.array())
    val server = DeviceMcp(context, { error("Unexpected location call") }, { _, _ -> error("Unexpected interactive action") })
    val trace = mutableListOf<String>()
    var id = 0
    fun call(op: String, path: String? = null, fail: Boolean = false): JSONObject {
        val args = JSONObject().put("operation", op); if (path != null) args.put("path", path)
        val body = JSONObject().put("jsonrpc", "2.0").put("id", ++id).put("method", "tools/call")
            .put("params", JSONObject().put("name", "media_player").put("arguments", args)).toString().toByteArray()
        val c = URL("http://127.0.0.1:${server.listeningPort}/mcp").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 20000
            c.setRequestProperty("Authorization", "Bearer ${server.token}"); c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) }
            val envelope = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); check(envelope.getInt("id") == id)
            val r = envelope.getJSONObject("result"); val text = r.getJSONArray("content").getJSONObject(0).getString("text")
            check(r.getBoolean("isError") == fail) { "$op: $text" }; return if (fail) JSONObject().put("error", text) else JSONObject(text)
        } finally { c.disconnect() }
    }
    fun observed(op: String, state: String, path: String? = null): Int {
        val a = call(op, path); check(a.getString("state") == state) { "$op: $a" }
        val pos = a.getInt("positionMs"); if (state != "stopped") check(a.getInt("durationMs") in 11990..12010 && a.getString("path") == file.name)
        trace.add("$op=$state@${pos}ms"); return pos
    }
    fun advancing(from: Int) {
        val until = SystemClock.elapsedRealtime() + 4000
        do {
            Thread.sleep(120)
            val r = call("info"); check(r.getString("state") == "playing")
            val now = r.getInt("positionMs"); if (now >= from + 480) { trace.add("info=playing@${now}ms"); return }
        } while (SystemClock.elapsedRealtime() < until)
        error("Playback position did not advance from $from")
    }
    try {
        server.start(10000, true)
        observed("info", "stopped")
        advancing(observed("play", "playing", file.name))
        val paused = observed("pause", "paused"); Thread.sleep(800)
        check(observed("info", "paused") == paused) { "Position moved while paused" }
        val resumed = observed("play", "playing"); check(resumed >= paused - 50) { "Resume restarted the file" }; advancing(resumed)
        observed("stop", "stopped"); check(observed("info", "stopped") == 0)
        check(call("resume", fail = true).getString("error").contains("No file loaded"))
        call("play", "../opencode.json", fail = true)
        return trace.joinToString("; ")
    } finally { server.stop(); file.delete() }
}
