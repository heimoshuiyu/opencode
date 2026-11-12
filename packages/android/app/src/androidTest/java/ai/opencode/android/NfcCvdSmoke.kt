package ai.opencode.android

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** One fresh MCP call per tag operation; the host removes/re-presents a file-backed Casimir tag. */
internal fun nfcCvdSmoke(test: Instrumentation, scenario: String, run: String?): String {
    check(Build.DEVICE.startsWith("vsoc_") || Build.HARDWARE.startsWith("cutf_")) { "Disposable Cuttlefish guest required" }
    val c = test.targetContext
    val a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("local", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { a.deviceInteraction }; val rpc = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor()
    fun await(f: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25000
        while (!main(f)) { check(SystemClock.elapsedRealtime() < end) { "Missing foreground NFC confirmation: $scenario" }; SystemClock.sleep(40) }
    }
    try {
        rpc.start(10000, true)
        val info = JSONObject(rpc.smokeCall("nfc", JSONObject().put("operation", "info")))
        check(info.get("present") == true && info.get("enabled") == true) { "NFC not ready: $info" }
        if (scenario == "nfc-cvd-info") return info.toString()
        val first = "OpenCode NFC ${UUID.fromString(requireNotNull(run))}"
        val read = scenario == "nfc-cvd-read"; val decline = scenario == "nfc-cvd-decline"
        val text = if (decline) "$first declined" else first
        val args = if (read) JSONObject().put("operation", "read").put("detail", "full")
            else JSONObject().put("operation", "write").put("text", text)
        val notice = if (read) a.getString(R.string.device_request_detail, args.toString()) else a.getString(R.string.nfc_write_prompt, text)
        await { a.hasWindowFocus() }
        val call = worker.submit<String> { rpc.smokeCall("nfc", args, expectError = decline, timeoutMs = 120000) }
        await {
            check(!call.isDone) { "NFC call completed before confirmation: ${call.get()}" }
            val root = WindowInspector.getGlobalWindowViews().singleOrNull { it.hasWindowFocus() && it.findViewById<TextView>(android.R.id.message)?.text == notice }
            root?.findViewById<Button>(if (decline) android.R.id.button2 else android.R.id.button1)?.performClick() == true
        }
        val result = call.get(120, TimeUnit.SECONDS)
        if (decline) { check(result == "Device request declined") { result }; return result }
        val obj = JSONObject(result)
        if (read) {
            val records = obj.getJSONArray("records")
            check(obj.getString("id") == "084f4301" && records.length() == 1 && records.getJSONObject(0).getString("text") == first) { result }
        } else check(obj.get("written") == true && obj.get("verified") == true) { result }
        return result
    } finally { main { ui.cancel("Fixture closed"); a.finish() }; rpc.stop(); worker.shutdownNow() }
}
