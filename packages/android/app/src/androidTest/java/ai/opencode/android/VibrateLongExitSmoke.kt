package ai.opencode.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** No direct vibrator or lifecycle calls. Observe an active system effect before navigating away. */
internal fun vibrateLongExitSmoke(test: Instrumentation): String {
    val c = test.targetContext
    val a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("local", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { a.deviceInteraction }; val rpc = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor(); var next: Activity? = null
    fun dump() = ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("dumpsys vibrator_manager")).bufferedReader().use { it.readText() }
    fun recent(d: String): List<String> {
        check("Recent vibrations:" in d && "Aggregated vibration history:" in d)
        return d.substringAfter("Recent vibrations:").substringBefore("Aggregated vibration history:").lines().filter { c.packageName in it }.map { it.trim() }
    }
    fun current(d: String): String {
        check("CurrentVibration:" in d && "NextVibration:" in d)
        return d.substringAfter("CurrentVibration:").substringBefore("NextVibration:").trim()
    }
    fun await(label: String, timeoutMs: Long, checkState: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!checkState()) { check(SystemClock.elapsedRealtime() < end) { "Timed out: $label" }; SystemClock.sleep(40) }
    }
    fun request(yes: Boolean): String {
        await("local foreground", 25000) { main { a.hasWindowFocus() } }
        val future = worker.submit<String> { rpc.smokeCall("vibrate", JSONObject().put("milliseconds", 8000), !yes, 60000) }
        await("8000 ms confirmation", 25000) { main {
            if (future.isDone) error("Returned before confirmation: ${future.get()}")
            val root = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() &&
                it.findViewById<TextView>(android.R.id.message)?.text == a.getString(R.string.vibrate_long, 8000) } ?: return@main false
            root.findViewById<Button>(if (yes) android.R.id.button1 else android.R.id.button2).performClick()
        } }; return future.get(10, TimeUnit.SECONDS)
    }
    try {
        rpc.start(10000, true)
        val baseline = dump(); check(c.packageName !in current(baseline)); val before = recent(baseline)
        val denied = request(false); val refused = dump(); val afterDenial = recent(refused)
        check(denied == "Device request declined" && afterDenial == before && c.packageName !in current(refused))
        val reply = JSONObject(request(true)); check(reply.getBoolean("submitted") && reply.getInt("milliseconds") == 8000)
        var active = ""
        await("running 8000 ms vibration", 3000) {
            active = current(dump()); c.packageName in active && "8000" in active && active.contains("running", ignoreCase = true)
        }
        next = test.startActivitySync(Intent(c, ServerPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("ServerPicker in foreground", 5000) { main { next.hasWindowFocus() && !a.hasWindowFocus() } }
        var added = emptyList<String>(); var last = ""
        await("ended system record", 3000) {
            val snapshot = dump(); last = current(snapshot); added = recent(snapshot) - afterDenial.toSet()
            c.packageName !in last && added.isNotEmpty()
        }
        val entry = added.single(); val duration = Regex("duration:\\s*(\\d+)ms").find(entry)?.groupValues?.get(1)?.toLong() ?: error(entry)
        check(entry.contains("cancel", ignoreCase = true) && "Step=8000ms" in entry && "usage: ALARM" in entry && duration in 1L..7999L) { entry }
        return JSONObject().put("declined", denied).put("recordsBefore", before.size).put("recordsAfterDecline", afterDenial.size)
            .put("approved", reply).put("beforeSwitch", active).put("foreground", next.javaClass.simpleName)
            .put("afterSwitch", last).put("history", entry).put("systemDurationMs", duration).toString()
    } finally { main { next?.finish(); a.finish() }; rpc.stop(); worker.shutdownNow() }
}
