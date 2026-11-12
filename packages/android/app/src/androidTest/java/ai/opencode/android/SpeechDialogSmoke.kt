package ai.opencode.android

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Requires an installed recognizer and synthetic audio on the emulator mic. No injected Activity results. */
internal fun speechDialogSmoke(test: Instrumentation, scenario: String): String {
    val c = test.targetContext
    val a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("local", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { a.deviceInteraction }; val server = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor()
    fun await(checkUi: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 115000
        while (!main(checkUi)) { check(SystemClock.elapsedRealtime() < end) { "Missing expected foreground UI" }; SystemClock.sleep(50) }
    }
    try {
        server.start(10000, true); await { a.hasWindowFocus() }
        val future = worker.submit<String> { server.smokeCall("dialog", JSONObject().put("widget", "speech").put("prompt", "Synthetic audio fixture"), scenario != "speech-share", 125000) }
        fun tap(positive: Int, approve: Boolean): String {
            var message = ""
            await {
                if (future.isDone) error("MCP finished before expected UI: ${future.get()}")
                val root = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() &&
                    it.findViewById<Button>(android.R.id.button1)?.text == a.getString(positive) } ?: return@await false
                message = root.findViewById<TextView>(android.R.id.message).text.toString()
                root.findViewById<Button>(if (approve) android.R.id.button1 else android.R.id.button2).performClick()
            }; return message
        }
        tap(R.string.speech_start, scenario != "speech-start-cancel")
        val review = if (scenario in setOf("speech-share", "speech-review-cancel")) tap(R.string.speech_share, scenario == "speech-share").substringAfter("\n\n") else null
        val result = future.get(115, TimeUnit.SECONDS)
        when (scenario) {
            "speech-share" -> check(JSONObject(result).getString("text") == review)
            // Some recognizers report RESULT_OK with no hypothesis when their Cancel button is used.
            "speech-picker-cancel" -> check(result in setOf("System action cancelled", "No speech recognized")) { "Expected recognizer dismissal without a transcript: $result" }
            else -> check(result == "Device request declined")
        }
        return "review=${review ?: "none"}; MCP=$result"
    } finally { main { ui.cancel("Fixture closed"); a.finish() }; server.stop(); worker.shutdownNow() }
}
