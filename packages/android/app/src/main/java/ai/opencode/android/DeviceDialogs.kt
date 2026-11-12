package ai.opencode.android

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.inputmethod.EditorInfo
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.DatePicker
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal object DeviceDialogs {
    val widgets = listOf("text", "confirm", "radio", "checkbox", "spinner", "date", "time", "counter", "sheet", "speech")

    fun request(args: JSONObject): JSONObject {
        val widget = args.optString("widget", "text")
        require(widget in widgets) { "Unknown dialog widget" }
        val choices = widget in setOf("radio", "checkbox", "spinner", "sheet")
        val range = setOf("min", "max", "start")
        val options = when {
            choices -> setOf("values")
            widget == "counter" -> range
            widget == "date" -> setOf("dateFormat")
            widget == "text" -> setOf("hint", "multiline", "numeric", "masked")
            widget == "speech" -> setOf("hint")
            else -> emptySet()
        }
        require(args.keys().asSequence().all { it == "prompt" || it == "widget" || it == "title" || it in options }) { "Unexpected dialog option" }
        require(args.opt("prompt") is String) { "Expected a prompt" }
        if (args.has("title")) {
            val title = args.get("title")
            require(title is String && title.isNotBlank() && title.length <= 120 &&
                !title.contains(Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]"))) { "Expected a nonblank single-line title up to 120 characters without control or format characters" }
        }
        if (args.has("hint")) require(args.opt("hint") is String && args.getString("hint").length <= 256) { "Expected hint text up to 256 characters" }
        for (key in listOf("numeric", "multiline", "masked")) if (args.has(key)) require(args.opt(key) is Boolean) { "Expected boolean $key" }
        require(!args.optBoolean("numeric") || !args.optBoolean("multiline")) { "Cannot combine numeric and multiline" }
        if (args.has("dateFormat")) require(args.opt("dateFormat") is String && args.getString("dateFormat").length in 1..80) { "Expected a date format of 1–80 characters" }
        if (widget == "counter" && range.any(args::has)) {
            require(range.all { args.opt(it) is Int }) { "Expected min, max and start as 32-bit integers" }
            require(args.getInt("start") in args.getInt("min")..args.getInt("max")) { "Expected min <= start <= max" }
        }
        if (choices) {
            val values = args.getJSONArray("values"); require(values.length() in 1..8) { "Expected 1–8 choices" }
            for (i in 0 until values.length()) {
                val s = values.get(i); require(s is String && s.isNotBlank() && s.length <= 128 && s.none(Char::isISOControl)) { "Invalid choice label" }
            }
        }
        return args
    }

    fun builder(activity: Activity, args: JSONObject, heading: String): AlertDialog.Builder {
        val builder = AlertDialog.Builder(activity)
        if (!args.has("title")) return builder.setTitle(heading)
        val header = View.inflate(builder.context, R.layout.device_dialog_header, null)
        header.findViewById<TextView>(R.id.dialog_origin).text = activity.getString(R.string.dialog_origin_heading, heading)
        header.findViewById<TextView>(R.id.dialog_caller_title).text = activity.getString(R.string.dialog_caller_heading, args.getString("title"))
        return builder.setCustomTitle(header)
    }

    fun show(activity: Activity, args: JSONObject, accept: (JSONObject) -> Unit, decline: () -> Unit): AlertDialog {
        val widget = args.optString("widget", "text")
        val values = args.optJSONArray("values")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        fun choice(i: Int) = JSONObject().put("index", i).put("text", values[i])
        val heading = if (widget == "sheet") activity.getString(R.string.dialog_sheet_title) else activity.getString(R.string.device_request, "dialog")
        val masked = args.optBoolean("masked")
        val builder = builder(activity, args, heading)
            .setMessage(activity.getString(if (masked) R.string.dialog_masked_notice else R.string.device_request_detail, args.getString("prompt").take(500)))
            .setNegativeButton(android.R.string.cancel) { _, _ -> decline() }.setOnCancelListener { decline() }
        if (widget == "confirm") return builder
            .setPositiveButton(R.string.dialog_yes) { _, _ -> accept(JSONObject().put("confirmed", true).put("text", "yes")) }
            .setNeutralButton(R.string.dialog_no) { _, _ -> accept(JSONObject().put("confirmed", false).put("text", "no")) }.show()

        if (widget == "sheet") {
            val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            values.forEachIndexed { i, label -> rows.addView(Button(activity).apply {
                text = label; isAllCaps = false; setOnClickListener { accept(choice(i)) }
            }) }
            val dialog = builder.setView(ScrollView(activity).apply { addView(rows) }).create()
            dialog.window?.apply { setGravity(Gravity.BOTTOM); setWindowAnimations(android.R.style.Animation_InputMethod) }
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return dialog
        }

        val input: View
        val read: () -> JSONObject
        when (widget) {
            "counter" -> {
                val min = args.optInt("min", 0); val max = args.optInt("max", 100); var value = args.optInt("start", 50)
                val minus = Button(activity).apply { text = "−"; contentDescription = activity.getString(R.string.dialog_decrement) }
                val plus = Button(activity).apply { text = "+"; contentDescription = activity.getString(R.string.dialog_increment) }
                val label = TextView(activity).apply { gravity = Gravity.CENTER; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
                fun update() { label.text = value.toString(); minus.isEnabled = value > min; plus.isEnabled = value < max }
                minus.setOnClickListener { if (value > min) { value--; update() } }
                plus.setOnClickListener { if (value < max) { value++; update() } }
                update()
                input = LinearLayout(activity).apply { gravity = Gravity.CENTER; addView(minus); addView(label, LinearLayout.LayoutParams(0, -2, 1f)); addView(plus) }
                read = { JSONObject().put("value", value).put("text", value.toString()) }
            }
            "text" -> {
                val field = EditText(activity).apply {
                    if (args.has("hint")) hint = args.getString("hint")
                    if (args.optBoolean("numeric")) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    if (args.has("multiline")) {
                        setSingleLine(!args.getBoolean("multiline"))
                        if (args.getBoolean("multiline")) minLines = 4
                    }
                    if (masked) {
                        inputType = (inputType and InputType.TYPE_MASK_VARIATION.inv()) or (if (args.optBoolean("numeric"))
                            InputType.TYPE_NUMBER_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                        transformationMethod = DialogMask
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        isSaveEnabled = false
                    }
                    filters = arrayOf(InputFilter.LengthFilter(8192))
                }; input = field
                read = { JSONObject().put("text", field.text.toString()) }
            }
            "radio" -> {
                val group = RadioGroup(activity); input = group
                values.forEach { group.addView(RadioButton(activity).apply { id = View.generateViewId(); text = it }) }
                read = { choice(group.indexOfChild(group.findViewById<View>(group.checkedRadioButtonId))) }
            }
            "checkbox" -> {
                val group = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }; input = group
                val boxes = values.map { CheckBox(activity).apply { text = it }.also(group::addView) }
                read = {
                    val selected = boxes.indices.filter { boxes[it].isChecked }
                    JSONObject().put("values", JSONArray(selected.map(::choice))).put("text", selected.joinToString(prefix = "[", postfix = "]") { values[it] })
                }
            }
            "spinner" -> {
                val field = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values) }; input = field
                read = { choice(field.selectedItemPosition) }
            }
            "date" -> {
                val field = DatePicker(activity); input = field
                val format = if (args.has("dateFormat")) try { SimpleDateFormat(args.getString("dateFormat"), Locale.getDefault()) }
                    catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid date format") } else null
                read = {
                    val text = if (format == null) String.format(Locale.ROOT, "%04d-%02d-%02d", field.year, field.month + 1, field.dayOfMonth)
                    else format.format(Calendar.getInstance().apply { clear(); set(field.year, field.month, field.dayOfMonth) }.time)
                    JSONObject().put("text", text)
                }
            }
            "time" -> {
                val field = TimePicker(activity).apply { setIs24HourView(true) }; input = field
                read = { JSONObject().put("text", String.format(Locale.ROOT, "%02d:%02d", field.hour, field.minute)) }
            }
            else -> error("Unknown dialog widget")
        }
        val dialog = builder.setView(ScrollView(activity).apply { addView(input) })
            .setPositiveButton(if (masked) R.string.dialog_share_original else android.R.string.ok) { _, _ -> input.clearFocus(); accept(read()) }.show()
        if (input is RadioGroup) {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE); ok.isEnabled = false
            input.setOnCheckedChangeListener { _, id -> ok.isEnabled = id != View.NO_ID }
        }
        return dialog
    }

    // Keep native password-field behavior without Android's optional last-character preview.
    // The Layout needs a live, length-preserving display sequence as the Editable changes.
    private object DialogMask : PasswordTransformationMethod() {
        override fun getTransformation(source: CharSequence, view: View): CharSequence = object : CharSequence {
            override val length: Int get() = source.length
            override fun get(index: Int) = if (source[index] == '\n') '\n' else '•'
            override fun subSequence(startIndex: Int, endIndex: Int) = String(CharArray(endIndex - startIndex) { get(startIndex + it) })
            override fun toString() = subSequence(0, length)
        }
    }
}
