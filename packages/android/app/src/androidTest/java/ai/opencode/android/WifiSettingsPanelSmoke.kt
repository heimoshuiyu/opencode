package ai.opencode.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The only state-changing Wi-Fi action here is a click on the visible Android Settings switch. */
internal fun wifiSettingsPanelSmoke(test: Instrumentation): String {
    val sys = test.uiAutomation
    fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(sys.executeShellCommand(cmd)).bufferedReader().use { it.readText().trim() }
    check(shell("getprop ro.boot.qemu") == "1") { "Disposable emulator required" }
    fun status() = shell("cmd wifi status").lineSequence().first().also { check(it == "Wifi is enabled" || it == "Wifi is disabled") { it } }
    val on = "Wifi is enabled"; val off = "Wifi is disabled"; check(status() == on) { "Test requires Wi-Fi initially on" }
    val c = test.targetContext; val a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("local", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val interaction = main { a.deviceInteraction }; val rpc = DeviceMcp(c, { error("Unexpected location request") }, interaction::invoke)
    val worker = Executors.newSingleThreadExecutor(); val trace = JSONArray()
    fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25000
        while (!predicate()) { check(SystemClock.elapsedRealtime() < end) { "Timed out: $label" }; SystemClock.sleep(80) }
    }
    val systemUi = "com.android.systemui"
    fun panelControl(id: String): AccessibilityNodeInfo? {
        val root = sys.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != systemUi) return null
        val panel = root.findAccessibilityNodeInfosByViewId("$systemUi:id/internet_connectivity_dialog").singleOrNull { it.isVisibleToUser } ?: return null
        return panel.findAccessibilityNodeInfosByViewId("$systemUi:id/$id").singleOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable }
    }
    fun settingsSwitch(): AccessibilityNodeInfo? {
        val root = sys.rootInActiveWindow ?: return null
        if (root.packageName != "com.android.settings") return null
        val title = root.findAccessibilityNodeInfosByText("Wi-Fi").singleOrNull { it.isVisibleToUser && it.text == "Wi-Fi" } ?: return null
        val row = generateSequence(title) { it.parent }.take(4).firstOrNull { it.isClickable && it.isEnabled } ?: return null
        return row.findAccessibilityNodeInfosByViewId("com.android.settings:id/switchWidget").singleOrNull { it.isVisibleToUser && it.isEnabled && it.isCheckable }
    }
    try {
        sys.serviceInfo = sys.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        rpc.start(10000, true)
        val invalid = rpc.smokeCall("wifi_settings", JSONObject().put("enabled", "false"), expectError = true)
        check(invalid.contains("enabled must be a boolean")) { invalid }
        trace.put(JSONObject().put("action", "string-false").put("error", invalid))
        for (action in listOf("legacy", "decline", "cancel", "off", "on")) {
            await("local screen") { main { a.hasWindowFocus() } }
            val before = status(); check(before == if (action == "on") off else on)
            val enabled = when (action) { "legacy" -> null; "on" -> true; else -> false }
            val args = JSONObject().apply { if (enabled != null) put("enabled", enabled) }
            val message = when (enabled) {
                null -> a.getString(R.string.device_request_detail, "{}")
                true -> a.getString(R.string.wifi_panel_enable)
                false -> a.getString(R.string.wifi_panel_disable)
            }
            val call = worker.submit<String> { rpc.smokeCall("wifi_settings", args, expectError = action == "decline", timeoutMs = 60000) }
            await("OpenCode confirmation") { main {
                if (call.isDone) error("MCP returned before approval: ${call.get()}")
                val root = WindowInspector.getGlobalWindowViews().singleOrNull { it.hasWindowFocus() &&
                    it.findViewById<TextView>(android.R.id.message)?.text == message } ?: return@main false
                root.findViewById<Button>(if (action == "decline") android.R.id.button2 else android.R.id.button1).performClick()
            } }
            if (action == "decline") {
                val error = call.get(10, TimeUnit.SECONDS); check(error.contains("Device request declined")) { error }
                check(status() == before && settingsSwitch() == null && panelControl("wifi_toggle") == null)
                trace.put(JSONObject().put("action", action).put("error", error)); continue
            }
            var toggle: AccessibilityNodeInfo? = null
            await("visible Wi-Fi switch: $action") { toggle = if (enabled == null) panelControl("wifi_toggle") else settingsSwitch(); toggle?.isCheckable == true }
            val opened = status(); check(opened == before && toggle!!.isChecked == (before == on)) { "Opening alone changed Wi-Fi" }
            fun stillWaiting() {
                try { error("MCP completed with panel still open ($action): ${call.get(400, TimeUnit.MILLISECONDS)}") }
                catch (_: TimeoutException) { check(settingsSwitch() != null) { "Settings closed prematurely" } }
            }
            if (enabled != null) stillWaiting() else check(JSONObject(call.get(10, TimeUnit.SECONDS)).length() == 1)
            val wanted = if (action == "off") off else on
            if (action == "off" || action == "on") {
                val row = generateSequence(toggle!!) { it.parent }.take(4).first { it.isClickable && it.isVisibleToUser && it.isEnabled }
                check(row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Wi-Fi switch rejected click" }
                await("system state after switch: $wanted") { status() == wanted }
                stillWaiting()
            }
            val pendingBeforeDismiss = !call.isDone
            check(pendingBeforeDismiss == (enabled != null))
            check(sys.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await("returned from Internet panel") { main { a.hasWindowFocus() } }
            val after = status(); check(after == wanted) { "$action: $before -> $after" }
            val reply = JSONObject(call.get(10, TimeUnit.SECONDS)); check(reply.get("opened") == true)
            if (enabled != null) {
                val actual = after == on
                check(reply.length() == 4 && reply.get("requestedEnabled") == enabled && reply.get("actualEnabled") == actual &&
                    reply.get("achieved") == (enabled == actual)) { "$action: $reply; independent status: $after" }
            }
            trace.put(JSONObject().put("action", action).put("before", before).put("mcp", reply).put("panelSwitch", toggle!!.viewIdResourceName)
                .put("atPanelOpen", opened).put("pendingBeforeDismiss", pendingBeforeDismiss).put("after", after))
        }
        return trace.toString()
    } finally { main { interaction.cancel("Fixture closed"); a.finish() }; worker.shutdownNow(); rpc.stop() }
}
