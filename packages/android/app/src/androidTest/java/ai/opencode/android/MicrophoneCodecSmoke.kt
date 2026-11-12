package ai.opencode.android

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** On a disposable emulator; Android's microphone permission is granted through the system UI, not by this test. */
internal fun microphoneCodecSmoke(test: Instrumentation, options: Boolean = false, named: Boolean = false, nested: Boolean = false): String {
    val c = test.targetContext
    val a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("local", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { a.deviceInteraction }; val server = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor(); val out = JSONArray(); val dir = File(c.filesDir, "home/projects")
    fun files() = dir.listFiles { f -> f.name.startsWith("recording-") }?.map { it.name }?.toSet().orEmpty()
    fun await(f: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (!main(f)) { check(SystemClock.elapsedRealtime() < end) { "Missing recorder UI" }; SystemClock.sleep(40) }
    }
    try {
        server.start(10000, true)
        if (named || nested) {
            val name = if (nested) "nested/clip.m4a" else "笔记 ${java.util.UUID.randomUUID()}.m4a"; val target = File(dir, name)
            if (nested) { val parent = target.parentFile!!; check(parent.canonicalFile == File(dir.canonicalFile, "nested") && (parent.isDirectory || parent.mkdir())) }
            fun start(approve: Boolean, error: Boolean = !approve, path: String = name): String {
                await { a.hasWindowFocus() }
                val task = worker.submit<String> { server.smokeCall("microphone_record", JSONObject().put("operation", "start").put("fileName", path).put("seconds", 12), error, 75000) }
                await {
                    if (task.isDone) error("MCP returned before filename confirmation: ${task.get()}")
                    val notice = a.getString(R.string.mic_consent, 12, "aac", 44100, 1, 96000)
                    val shown = WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }.mapNotNull { it.findViewById<TextView>(android.R.id.message)?.text?.toString() }.firstOrNull()
                    if (shown != null && !shown.contains(path)) error("Path not in confirmation: $shown")
                    val root = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() &&
                        it.findViewById<TextView>(android.R.id.message)?.text == a.getString(R.string.mic_destination, notice, path) } ?: return@await false
                    root.findViewById<Button>(if (approve) android.R.id.button1 else android.R.id.button2).performClick()
                }
                return task.get(75, TimeUnit.SECONDS)
            }
            fun stop(error: Boolean = false) = server.smokeCall("microphone_record", JSONObject().put("operation", "stop"), error)
            val cacheBefore = a.cacheDir.list()?.filter { it.startsWith("recording-") }?.toSet().orEmpty()
            check(!target.exists()); val denied = start(false); check(denied == "Device request declined" && !target.exists()); out.put("decline: $denied")
            val outside = File(dir.parentFile, "forbidden-${java.util.UUID.randomUUID()}.m4a"); check(!outside.exists())
            for (bad in listOf("../${outside.name}", outside.path, "clip.ogg")) {
                val error = server.smokeCall("microphone_record", JSONObject().put("operation", "start").put("fileName", bad), true)
                check(error.startsWith("Expected ") && !outside.exists() && !target.exists()); out.put("$bad: $error; no target")
            }
            if (nested) {
                val missing = File(dir, "missing-${java.util.UUID.randomUUID()}")
                val error = start(true, true, "${missing.name}/clip.m4a")
                check(error.contains("directory must already exist") && !missing.exists()); out.put("missing directory: $error")
                val alias = File(dir, "link-${java.util.UUID.randomUUID()}").toPath()
                java.nio.file.Files.createSymbolicLink(alias, outside.parentFile!!.toPath())
                try {
                    val linked = start(true, true, "${alias.fileName}/${outside.name}"); check(linked == error && !outside.exists()); out.put("linked directory: rejected; outside target absent")
                } finally { java.nio.file.Files.delete(alias) }
            }
            start(true); check(!target.exists()); check(target.createNewFile()); target.writeText("fixture marker")
            SystemClock.sleep(1500); val conflict = stop(true); check(conflict == "Recording destination already exists" && target.readText() == "fixture marker"); out.put("late collision: $conflict; marker unchanged")
            val exists = start(true, true); check(exists == conflict && target.readText() == "fixture marker"); out.put("existing file: $exists; marker unchanged"); check(target.delete())
            start(true); check(!target.exists()); SystemClock.sleep(1500)
            val result = JSONObject(stop()); check(File(result.getString("path")).canonicalFile == target.canonicalFile && result.getLong("bytes") == target.length() && target.length() > 0)
            check(a.cacheDir.list()?.filter { it.startsWith("recording-") }?.toSet().orEmpty() == cacheBefore)
            return out.put(result).toString()
        }
        data class Case(val encoder: String, val rate: Int, val channels: Int, val bitrate: Int, val approve: Boolean = true)
        val cases = if (options) listOf(Case("aac", 32000, 2, 128000, false), Case("aac", 16000, 1, 24000), Case("aac", 16000, 1, 48000), Case("aac", 32000, 2, 128000))
            else listOf(Case("opus", 48000, 1, 64000, false), Case("aac", 44100, 1, 96000), Case("amr_nb", 8000, 1, 12200), Case("amr_wb", 16000, 1, 23850), Case("opus", 48000, 1, 64000))
        if (options) {
            val before = files()
            for ((args, expected) in listOf(JSONObject().put("channels", 3) to "channels must be an integer in 1..2",
                JSONObject().put("bitrateBps", "64000") to "bitrateBps must be an integer in 4000..512000",
                JSONObject().put("encoder", "amr_wb").put("sampleRateHz", 44100) to "amr_wb requires mono at 16000 Hz")) {
                val error = server.smokeCall("microphone_record", args.put("operation", "start"), true); check(error == expected); out.put(error)
            }
            check(files() == before)
        }
        for ((encoder, rate, channels, bitrate, approve) in cases) {
            await { a.hasWindowFocus() }; val before = files()
            val args = JSONObject().put("operation", "start").put("seconds", 12)
            if (encoder != "aac") args.put("encoder", encoder) // unchanged default AAC contract
            if (options) args.put("sampleRateHz", rate).put("channels", channels).put("bitrateBps", bitrate)
            val pending = worker.submit<String> { server.smokeCall("microphone_record", args, !approve, 75000) }
            await {
                val root = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() &&
                    it.findViewById<TextView>(android.R.id.message)?.text == a.getString(R.string.mic_consent, 12, encoder, rate, channels, bitrate) } ?: return@await false
                root.findViewById<Button>(if (approve) android.R.id.button1 else android.R.id.button2).performClick()
            }
            val start = pending.get(75, TimeUnit.SECONDS)
            if (!approve) { check(start == "Device request declined" && before == files()); out.put("declined=$start; unchanged files"); continue }
            check(JSONObject(start).getString("encoder") == encoder)
            SystemClock.sleep(2300)
            val stop = JSONObject(server.smokeCall("microphone_record", JSONObject().put("operation", "stop")))
            val track = File(stop.getString("path")); check(stop.getString("state") == "stopped" && stop.getString("encoder") == encoder)
            val requested = stop.getJSONObject("requested")
            check(requested.getInt("bitrateBps") == bitrate && requested.getInt("sampleRateHz") == rate && requested.getInt("channels") == channels)
            check(stop.getInt("sampleRateHz") == rate && stop.getInt("channels") in 1..channels) { stop.toString() }
            // A virtual mic may negotiate mono when asked for stereo. Compare saved output, not the request.
            val source = android.media.MediaExtractor()
            try {
                source.setDataSource(track.path); val format = source.getTrackFormat(0)
                check(stop.getInt("channels") == format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT))
            } finally { source.release() }
            check((files() - before) == setOf(track.name) && track.length() == stop.getLong("bytes")); out.put(stop)
        }
        return out.toString()
    } finally { main { ui.stopMicrophone(); ui.cancel("Fixture closed"); a.finish() }; server.stop(); worker.shutdownNow() }
}
