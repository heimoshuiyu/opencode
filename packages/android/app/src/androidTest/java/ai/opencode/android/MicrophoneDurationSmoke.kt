package ai.opencode.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Deliberately runs in real time on a disposable emulator with a virtual microphone. */
internal fun microphoneDurationSmoke(test: Instrumentation, longRun: Boolean): String {
    val c = test.targetContext
    val a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("local", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { a.deviceInteraction }; val server = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor(); val result = JSONObject(); var other: Activity? = null
    val dir = File(c.filesDir, "home/projects"); val id = UUID.randomUUID()
    fun info() = JSONObject(server.smokeCall("microphone_record", JSONObject().put("operation", "info")))
    fun awaitUi(label: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (!main(predicate)) { check(SystemClock.elapsedRealtime() < end) { "Missing foreground UI: $label" }; SystemClock.sleep(40) }
    }
    fun button(message: String, buttonId: Int): Button? = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() &&
        it.findViewById<TextView>(android.R.id.message)?.text == message }?.findViewById<Button>(buttonId)?.takeIf { it.isShown && it.isEnabled }
    fun start(seconds: Int?, name: String, approve: Boolean) {
        awaitUi("local activity") { a.hasWindowFocus() }; check(!File(dir, name).exists())
        val args = JSONObject().put("operation", "start").put("fileName", name)
        if (seconds != null) args.put("seconds", seconds)
        val notice = if (seconds == 0) a.getString(R.string.mic_consent_no_limit, "aac", 44100, 1, 96000)
            else a.getString(R.string.mic_consent, seconds ?: 60, "aac", 44100, 1, 96000)
        val future = worker.submit<String> { server.smokeCall("microphone_record", args, !approve, 75000) }
        awaitUi("per-start consent") { button(a.getString(R.string.mic_destination, notice, name), if (approve) android.R.id.button1 else android.R.id.button2)?.performClick() == true }
        val response = future.get(75, TimeUnit.SECONDS)
        if (!approve) { check(response == "Device request declined" && !File(dir, name).exists()); result.put("declined", response); return }
        val started = JSONObject(response); check(started.getString("state") == "recording" && started.getJSONObject("requested").getInt("seconds") == (seconds ?: 60))
    }
    fun stopped(name: String, reason: String): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 10000
        var status = info()
        while (status.optString("state") == "recording" && SystemClock.elapsedRealtime() < deadline) { SystemClock.sleep(80); status = info() }
        check(status.optString("reason") == reason && status.getString("state") == "stopped") { status.toString() }
        val file = File(status.getString("path")); check(file.canonicalFile == File(dir, name).canonicalFile && status.getLong("bytes") == file.length() && file.length() > 0)
        return status
    }
    try {
        server.start(10000, true)
        val unlimited = "unlimited-$id.m4a"
        if (longRun) {
            start(0, unlimited, false); start(0, unlimited, true)
            SystemClock.sleep(315_000) // Do not simulate elapsed time or call a timer callback.
            val running = info(); check(running.getString("state") == "recording" && running.getLong("elapsedMs") > 300000); result.put("after300", running)
            awaitUi("active unlimited Stop button") {
                val stop = button(a.getString(R.string.mic_no_limit), android.R.id.button1)
                stop != null && stop.text == a.getString(R.string.mic_stop) && stop.performClick()
            }
            result.put("manual", stopped(unlimited, "user"))
        } else {
            for (invalid in listOf("0", -1, 301, JSONObject.NULL)) {
                val error = server.smokeCall("microphone_record", JSONObject().put("operation", "start").put("seconds", invalid), true)
                check(error == "seconds must be an integer in 0..300") { error }
            }
            val defaultName = "default-$id.m4a"; start(null, defaultName, true); SystemClock.sleep(60000)
            val timed = stopped(defaultName, "time limit"); check(timed.getLong("elapsedMs") >= 60000); result.put("omitted", timed)
            start(0, unlimited, true); SystemClock.sleep(1500)
            other = test.startActivitySync(Intent(c, ServerPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitUi("different foreground activity") { other.hasWindowFocus() }
            val left = stopped(unlimited, "left foreground"); val size = left.getLong("bytes"); SystemClock.sleep(1200)
            check(info().getLong("elapsedMs") == left.getLong("elapsedMs") && File(dir, unlimited).length() == size); result.put("left", left)
        }
        return result.toString()
    } finally { main { ui.stopMicrophone(); ui.cancel("Fixture closed"); other?.finish(); a.finish() }; server.stop(); worker.shutdownNow() }
}
