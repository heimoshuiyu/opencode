package ai.opencode.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal fun toastStyleSmoke(test: Instrumentation): String {
    val c = test.targetContext
    val a = test.startActivitySync(Intent(c, ServerPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { DeviceInteraction(a) }; val server = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor(); val out = mutableListOf<String>()
    fun await(label: String, f: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!main(f)) { check(SystemClock.elapsedRealtime() < end) { "Timed out: $label" }; SystemClock.sleep(25) }
    }
    fun root(text: String) = WindowInspector.getGlobalWindowViews().filterIsInstance<TextView>().firstOrNull {
        it.text == "OpenCode · $text" && it.isShown && it.height > 0 && (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_TOAST
    }
    try {
        server.start(10000, true)
        fun call(text: String, options: JSONObject, approve: Boolean): String {
            await("foreground focus") { a.hasWindowFocus() }
            val future = worker.submit<String> { server.smokeCall("toast", options.put("text", text), !approve, 60000) }
            await("confirmation") {
                val window = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() && it.findViewById<TextView>(android.R.id.message)?.text?.contains(text) == true } ?: return@await false
                window.findViewById<Button>(if (approve) android.R.id.button1 else android.R.id.button2).performClick()
            }
            return future.get(10, TimeUnit.SECONDS)
        }
        val bad = server.smokeCall("toast", JSONObject().put("text", "fixture").put("gravity", "left"), true); check(bad == "Expected top, middle or bottom"); out.add(bad)
        val denied = call("fixture denied", JSONObject().put("gravity", "top"), false); check(denied == "Device request declined"); out.add(denied)
        val ys = mutableListOf<Int>(); val times = mutableListOf<Long>()
        for (position in listOf("top", "middle", "bottom")) {
            val text = "fixture $position"; val opt = JSONObject().put("gravity", position)
            when (position) {
                "top" -> opt.put("duration", "short").put("textColor", "yellow").put("backgroundColor", "#80123456")
                "middle" -> opt.put("textColor", "not-a-color").put("backgroundColor", "")
                "bottom" -> opt.put("duration", "long").put("textColor", "#abcdef").put("backgroundColor", "blue")
            }
            val result = call(text, opt, true); check(JSONObject(result).getBoolean("shown"))
            var colors = ""
            await("toast $position") {
                val view = root(text) ?: return@await false
                val loc = IntArray(2); view.getLocationOnScreen(loc); ys.add(loc[1]); val fg = view.currentTextColor; val bg = (view.background as ColorDrawable).color
                val expected = when (position) { "top" -> Color.YELLOW to 0x80123456.toInt(); "middle" -> Color.WHITE to Color.GRAY; else -> 0xffabcdef.toInt() to Color.BLUE }
                check(fg to bg == expected); colors = "%08x/%08x".format(fg, bg); true
            }
            val begin = SystemClock.elapsedRealtime(); await("toast removed") { root(text) == null }; times.add(SystemClock.elapsedRealtime() - begin)
            out.add("$position y=${ys.last()} colors=$colors visibleMs=${times.last()} MCP=$result")
        }
        check(ys[0] < ys[1] && ys[1] < ys[2] && times[0] < times[1] && times[0] < times[2]) { out.toString() }
        out.add("plain=" + call("fixture plain", JSONObject(), true)); return out.joinToString("; ")
    } finally { main { ui.cancel("Fixture closed"); a.finish() }; server.stop(); worker.shutdownNow() }
}
