package ai.opencode.android

import android.app.Instrumentation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Disposable guest only. Never read any pre-existing clipboard or print unexpected text. */
internal fun clipboardSmoke(test: Instrumentation): String {
    val qemu = test.uiAutomation.executeShellCommand("getprop ro.boot.qemu")
    check(ParcelFileDescriptor.AutoCloseInputStream(qemu).bufferedReader().use { it.readText().trim() } == "1")
    val c = test.targetContext
    val a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("local", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { a.deviceInteraction }; val rpc = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor()
    val original = "  合成剪贴板 🧪\n${UUID.randomUUID()}\tA&B  "
    fun await(label: String, f: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (!main(f)) { check(SystemClock.elapsedRealtime() < end) { "Missing $label" }; SystemClock.sleep(40) }
    }
    fun request(name: String, args: JSONObject, allow: Boolean): JSONObject {
        await("local screen") { a.hasWindowFocus() }
        val call = worker.submit<JSONObject> { rpc.smokeToolResult(name, args, 45000) }
        await("$name consent") {
            check(!call.isDone) { "$name completed before confirmation" }
            val root = WindowInspector.getGlobalWindowViews().singleOrNull { it.hasWindowFocus() &&
                it.findViewById<TextView>(android.R.id.message)?.text == a.getString(R.string.device_request_detail, args.toString()) }
            root?.findViewById<Button>(if (allow) android.R.id.button1 else android.R.id.button2)?.performClick() == true
        }
        val r = call.get(10, TimeUnit.SECONDS)
        val content = r.getJSONArray("content"); check(content.length() == 1 && r.get("isError") == !allow)
        val item = content.getJSONObject(0); check(item.get("type") == "text")
        if (allow) return JSONObject(item.getString("text"))
        check(r.length() == 2 && item.length() == 2 && item.get("text") == "Device request declined") { "Unexpected data in refusal" }
        return r
    }
    // Separate focused Activity, fresh empty widget, Android's paste action; no MCP getter involved.
    fun paste(): String {
        val probe = test.startActivitySync(Intent(c, ServerPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val field = main { EditText(probe).apply { showSoftInputOnFocus = false; probe.setContentView(this); requestFocus() } }
            await("paste field") { field.hasWindowFocus() && field.isFocused }
            return main {
                check(field.text.isEmpty() && field.onTextContextMenuItem(android.R.id.paste))
                field.text.toString().also { check(it == original) { "Foreground paste differs from fixture" } }
            }
        } finally { main { probe.finish() } }
    }
    try {
        rpc.start(10000, true)
        val set = request("clipboard_set", JSONObject().put("text", original), true); check(set.get("written") == true)
        val first = paste()
        val refusedWrite = request("clipboard_set", JSONObject().put("text", "拒绝替换 ${UUID.randomUUID()}"), false)
        val retained = paste()
        val refusedRead = request("clipboard_get", JSONObject(), false)
        val get = request("clipboard_get", JSONObject(), true); check(get.get("text") == original) { "Approved read differs from fixture" }
        return JSONObject().put("set", set).put("firstPaste", first).put("refusedWrite", refusedWrite).put("pasteAfterRefusal", retained)
            .put("refusedRead", refusedRead).put("get", get).toString()
    } finally { main { ui.cancel("Fixture closed"); a.finish() }; worker.shutdownNow(); rpc.stop() }
}
