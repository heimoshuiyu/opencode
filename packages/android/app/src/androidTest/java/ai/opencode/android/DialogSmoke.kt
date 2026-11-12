package ai.opencode.android

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Authenticated MCP to the production interaction handler, driving real native controls on a test-owned screen. */
internal fun dialogSmoke(test: Instrumentation): String {
    val c = test.targetContext
    val activity = test.startActivitySync(Intent(c, ServerPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { DeviceInteraction(activity) }
    val server = DeviceMcp(c, { error("Unexpected location request") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor()
    fun nodes(v: View): Sequence<View> = sequence {
        yield(v); if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(nodes(v.getChildAt(i)))
    }
    fun awaitUi(f: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 7000
        while (!main(f)) { check(SystemClock.elapsedRealtime() < end) { "Dialog UI did not reach expected state" }; SystemClock.sleep(25) }
    }
    val trace = mutableListOf<String>()
    try {
        server.start(10000, true)
        fun run(widget: String, button: Int? = android.R.id.button1, options: JSONObject = JSONObject(), afterLayout: ((List<View>) -> Unit)? = null, interact: (List<View>) -> Unit = {}): String {
            val marker = "Dialog fixture ${trace.size}: $widget"
            val a = JSONObject().put("prompt", marker)
            if (widget != "text") a.put("widget", widget) // exercise the existing default contract
            if (widget in setOf("checkbox", "radio", "spinner", "sheet")) a.put("values", JSONArray(listOf("Red", "Green", "Blue")))
            options.keys().forEach { a.put(it, options.get(it)) }
            awaitUi { activity.hasWindowFocus() }
            val future = worker.submit<String> { server.smokeCall("dialog", a, expectError = button == android.R.id.button2) }
            lateinit var panel: View
            awaitUi {
                val root = WindowInspector.getGlobalWindowViews().firstOrNull {
                    it.isShown && it.findViewById<TextView>(android.R.id.message)?.text?.toString()?.endsWith(marker) == true
                } ?: return@awaitUi false
                panel = root; interact(nodes(root).toList())
                true
            }
            if (afterLayout != null) test.waitForIdleSync()
            main {
                afterLayout?.invoke(nodes(panel).toList())
                if (button != null) { val b = panel.findViewById<Button>(button); check(b.isShown && b.isEnabled); b.performClick() }
            }
            val result = future.get(5, TimeUnit.SECONDS); trace.add("$widget=$result"); return result
        }
        val legacy = JSONObject(run("text") { views ->
            check(views.none { it.id == R.id.dialog_caller_title }); val heading = activity.getString(R.string.device_request, "dialog")
            check(views.filterIsInstance<TextView>().any { it.isShown && it.text == heading }); trace.add("legacy heading=$heading; no caller title")
            views.filterIsInstance<EditText>().single().setText("fixture input")
        }); check(legacy.length() == 1 && legacy.getString("text") == "fixture input")
        for (approve in listOf(true, false)) {
            val fixture = if (approve) "Maple-47" else "Discard-92"
            val result = run("text", if (approve) android.R.id.button1 else android.R.id.button2, JSONObject().put("masked", true), afterLayout = { views ->
                val field = views.filterIsInstance<EditText>().single(); val display = field.layout.text.toString()
                check(field.getGlobalVisibleRect(android.graphics.Rect()) && field.text.toString() == fixture)
                check(display == "•".repeat(fixture.length)) { "Unmasked glyph in visible field layout" }
                val share = views.filterIsInstance<Button>().single { it.id == android.R.id.button1 }
                val notice = views.filterIsInstance<TextView>().single { it.id == android.R.id.message }
                check(share.isShown && share.text == activity.getString(R.string.dialog_share_original))
                check(notice.isShown && notice.text.startsWith(activity.getString(R.string.dialog_masked_notice, "").trimEnd()))
                trace.add("masked layout=$display; action=${share.text}; sharing notice visible")
            }) { views ->
                val field = views.filterIsInstance<EditText>().single(); field.requestFocus()
                for (ch in fixture) {
                    field.append(ch.toString()); val display = field.transformationMethod.getTransformation(field.text, field)
                    check(display.toString() == "•".repeat(field.length())) { "Last character briefly exposed" }
                }
            }
            if (approve) { val data = JSONObject(result); check(data.length() == 1 && data.getString("text") == fixture) }
            else check(result == "Device request declined")
        }
        val invalidMask = server.smokeCall("dialog", JSONObject().put("prompt", "fixture").put("masked", "true"), true)
        check(invalidMask == "Expected boolean masked"); trace.add(invalidMask)
        val title = "行程备注"; val titled = JSONObject().put("title", title)
        fun checkTitle(views: List<View>, heading: String) {
            val origin = views.single { it.id == R.id.dialog_origin } as TextView
            val caller = views.single { it.id == R.id.dialog_caller_title } as TextView
            val top = android.graphics.Rect(); val below = android.graphics.Rect()
            check(origin.getGlobalVisibleRect(top) && caller.getGlobalVisibleRect(below) && top.bottom <= below.top)
            check(origin.text.toString().startsWith("OpenCode") && origin.text == activity.getString(R.string.dialog_origin_heading, heading))
            check(caller.text == activity.getString(R.string.dialog_caller_heading, title)); trace.add("visible=${origin.text} / ${caller.text}")
        }
        val submitted = JSONObject(run("text", options = titled) { views ->
            checkTitle(views, activity.getString(R.string.device_request, "dialog")); views.filterIsInstance<EditText>().single().setText("approved note")
        }); check(submitted.length() == 1 && submitted.getString("text") == "approved note")
        check(run("text", android.R.id.button2, titled) { views ->
            checkTitle(views, activity.getString(R.string.device_request, "dialog")); val field = views.filterIsInstance<EditText>().single()
            field.setText("discard fixture"); check(field.text.toString() == "discard fixture")
        } == "Device request declined")
        check(JSONObject(run("confirm")).getBoolean("confirmed")); check(!JSONObject(run("confirm", android.R.id.button3)).getBoolean("confirmed"))
        val radio = JSONObject(run("radio") { views ->
            check(!views.single { it.id == android.R.id.button1 }.isEnabled)
            views.filterIsInstance<RadioButton>().single { it.text == "Green" }.performClick()
        }); check(radio.getInt("index") == 1 && radio.getString("text") == "Green")
        val checks = JSONObject(run("checkbox") { views -> views.filterIsInstance<CheckBox>().filter { it.text != "Green" }.forEach { it.performClick() } }).getJSONArray("values")
        check(checks.length() == 2 && (0..1).map { checks.getJSONObject(it).getInt("index") to checks.getJSONObject(it).getString("text") } == listOf(0 to "Red", 2 to "Blue"))
        val spinner = JSONObject(run("spinner") { it.filterIsInstance<Spinner>().single().setSelection(2) }); check(spinner.getInt("index") == 2 && spinner.getString("text") == "Blue")
        check(JSONObject(run("date") { it.filterIsInstance<DatePicker>().single().updateDate(2032, 1, 29) }).getString("text") == "2032-02-29")
        check(JSONObject(run("time") { it.filterIsInstance<TimePicker>().single().apply { hour = 7; minute = 5 } }).getString("text") == "07:05")
        check(run("checkbox", android.R.id.button2) { it.filterIsInstance<CheckBox>().last().performClick() } == "Device request declined")
        val counter = JSONObject(run("counter")); check(counter.getInt("value") == 50 && counter.getString("text") == "50")
        val range = JSONObject().put("min", -2).put("max", 0).put("start", -1)
        val changed = JSONObject(run("counter", options = range) { views ->
            val minus = views.filterIsInstance<Button>().single { it.text == "−" }; val plus = views.filterIsInstance<Button>().single { it.text == "+" }
            val label = views.filterIsInstance<TextView>().single { it.text == "-1" }
            plus.performClick(); check(label.text == "0" && !plus.isEnabled)
            minus.performClick(); minus.performClick(); check(label.text == "-2" && !minus.isEnabled)
        }); check(changed.getInt("value") == -2 && changed.getString("text") == "-2")
        val invalid = server.smokeCall("dialog", JSONObject().put("prompt", "Bad range").put("widget", "counter").put("min", 0).put("max", 3).put("start", 4), expectError = true)
        check(invalid == "Expected min <= start <= max"); trace.add("invalid counter=$invalid")
        val sheet = JSONObject(run("sheet", null, titled) { views ->
            checkTitle(views, activity.getString(R.string.dialog_sheet_title))
            val window = views.first().layoutParams as android.view.WindowManager.LayoutParams
            check((window.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK) == android.view.Gravity.BOTTOM)
            views.filterIsInstance<Button>().single { it.isShown && it.text == "Green" }.performClick()
        }); check(sheet.getInt("index") == 1 && sheet.getString("text") == "Green")
        check(run("sheet", android.R.id.button2) == "Device request declined")
        val multiline = run("text", options = JSONObject().put("hint", "Two lines").put("multiline", true)) { views ->
            val field = views.filterIsInstance<EditText>().single()
            check(field.hint == "Two lines" && field.minLines >= 4); field.setText("one\ntwo")
        }; check(JSONObject(multiline).getString("text") == "one\ntwo")
        val numeric = run("text", options = JSONObject().put("numeric", true)) { views ->
            val field = views.filterIsInstance<EditText>().single()
            check((field.inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_NUMBER)
            field.append("-12.5")
        }; check(JSONObject(numeric).getString("text") == "-12.5")
        val mixed = server.smokeCall("dialog", JSONObject().put("prompt", "Mixed input").put("multiline", true).put("numeric", true), expectError = true)
        check(mixed == "Cannot combine numeric and multiline"); trace.add(mixed)
        val formatted = run("date", options = JSONObject().put("dateFormat", "dd/MM/yyyy HH:mm")) { it.filterIsInstance<DatePicker>().single().updateDate(2032, 1, 29) }
        check(JSONObject(formatted).getString("text") == "29/02/2032 00:00")
        awaitUi { activity.hasWindowFocus() }
        val badDate = server.smokeCall("dialog", JSONObject().put("prompt", "Bad date").put("widget", "date").put("dateFormat", "yyyy'"), expectError = true)
        check(badDate == "Invalid date format"); trace.add(badDate)
        return trace.joinToString("; ")
    } finally {
        main { ui.cancel("Fixture closed"); activity.finish() }; server.stop(); worker.shutdownNow()
    }
}
