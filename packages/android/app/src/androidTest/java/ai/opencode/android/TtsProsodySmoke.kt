package ai.opencode.android

import android.app.Instrumentation
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Android's tts_speak_success event records the receiving engine, caller, length, rate and pitch. */
internal fun ttsProsodySmoke(test: Instrumentation, routing: Boolean = false, engines: Boolean = false): String {
    val c = test.targetContext
    val activity = test.startActivitySync(Intent(c, ServerPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { DeviceInteraction(activity) }; val server = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor(); val trace = mutableListOf<String>()
    val phrase = "This is a speech test. The red bird and the blue bird are sitting on a tree."
    fun await(f: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (!main(f)) { check(SystemClock.elapsedRealtime() < end) { "Missing TTS prompt or foreground focus" }; SystemClock.sleep(30) }
    }
    try {
        server.start(10000, true)
        fun speak(rate: Double?, pitch: Double?, approve: Boolean, stream: String? = null, usage: Int? = null, engine: String? = null): Long {
            await { activity.hasWindowFocus() }
            val args = JSONObject().put("text", phrase).put("rate", rate as Any?).put("pitch", pitch as Any?).put("stream", stream).put("engine", engine)
            val audio = c.getSystemService(AudioManager::class.java)
            if (usage != null) check(audio.activePlaybackConfigurations.none { it.audioAttributes.usage == usage }) { "Stream already active: $stream" }
            val call = worker.submit<String> { server.smokeCall("tts_speak", args, !approve, 60000) }
            await {
                if (call.isDone) error("Result before confirmation: ${call.get()}")
                val root = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() &&
                    it.findViewById<TextView>(android.R.id.message)?.text?.let { msg -> when {
                        engine != null -> msg == activity.getString(R.string.tts_engine_notice, engine, phrase)
                        stream != null -> msg == activity.getString(R.string.tts_stream_notice, stream, phrase)
                        else -> msg.contains(phrase)
                    } } == true } ?: return@await false
                root.findViewById<Button>(if (approve) android.R.id.button1 else android.R.id.button2).performClick()
            }
            val t = SystemClock.elapsedRealtime()
            if (approve && usage != null) {
                while (audio.activePlaybackConfigurations.none { it.audioAttributes.usage == usage }) {
                    check(!call.isDone && SystemClock.elapsedRealtime() - t < 15000) { "No active audio usage $usage for $stream" }; SystemClock.sleep(25)
                }
                trace.add("stream=$stream activeUsage=$usage")
            }
            val result = call.get(60, TimeUnit.SECONDS); val ms = SystemClock.elapsedRealtime() - t
            trace.add("engine=$engine rate=$rate pitch=$pitch ms=$ms result=$result")
            check(if (approve) JSONObject(result).getBoolean("spoken") else result == "Device request declined"); return ms
        }
        if (engines) {
            val listed = org.json.JSONArray(server.smokeCall("tts_engines")); val packages = (0 until listed.length()).map { listed.getJSONObject(it).getString("package") }
            val google = "com.google.android.tts"; val espeak = "com.reecedunn.espeak"
            check(packages.containsAll(listOf(google, espeak))) { "Install eSpeak NG in the test emulator: $packages" }; trace.add("listed=$packages")
            val unknown = server.smokeCall("tts_speak", JSONObject().put("text", phrase).put("engine", "fixture.missing"), true)
            check(unknown == "Unknown TTS engine; use tts_engines"); trace.add(unknown)
            speak(null, null, false, engine = espeak); speak(null, null, true); speak(null, null, true, engine = espeak); speak(null, null, true, engine = google)
            return trace.joinToString("; ")
        }
        if (routing) {
            val invalid = server.smokeCall("tts_speak", JSONObject().put("text", phrase).put("stream", "other"), true)
            check(invalid == "Unknown TTS stream"); trace.add(invalid)
            speak(null, null, false, "alarm")
            for ((stream, usage) in mapOf("alarm" to AudioAttributes.USAGE_ALARM, "music" to AudioAttributes.USAGE_MEDIA,
                "notification" to AudioAttributes.USAGE_NOTIFICATION, "ring" to AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
                "system" to AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, "voice_call" to AudioAttributes.USAGE_VOICE_COMMUNICATION)) speak(null, null, true, stream, usage)
            return trace.joinToString("; ")
        }
        val invalid = server.smokeCall("tts_speak", JSONObject().put("text", phrase).put("pitch", "1.2"), true)
        check(invalid == "pitch must be a number in 0.25..4"); trace.add(invalid)
        speak(1.25, 1.25, false); speak(null, null, true)
        val slow = speak(0.5, 0.75, true); val fast = speak(2.0, 0.75, true)
        check(slow > fast) { trace.toString() }; return trace.joinToString("; ")
    } finally { main { ui.cancel("Fixture closed"); activity.finish() }; server.stop(); worker.shutdownNow() }
}
