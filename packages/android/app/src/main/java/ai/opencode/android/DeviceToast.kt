package ai.opencode.android

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.roundToInt

@Suppress("DEPRECATION") // Only a foreground custom toast supports gravity and colors on Android 11+.
internal fun showDeviceToast(activity: Activity, message: String, args: JSONObject) {
    val styled = listOf("gravity", "textColor", "backgroundColor").any(args::has)
    val duration = if (args.optString("duration", if (styled) "long" else "short") == "long") Toast.LENGTH_LONG else Toast.LENGTH_SHORT
    if (!styled) {
        Toast.makeText(activity, message, duration).show(); return
    }
    fun color(key: String, fallback: Int): Int {
        if (args.optString(key).isEmpty()) return fallback
        return try { Color.parseColor(args.getString(key)) } catch (_: IllegalArgumentException) { fallback }
    }
    val position = when (args.optString("gravity")) { "top" -> Gravity.TOP; "bottom" -> Gravity.BOTTOM; else -> Gravity.CENTER_VERTICAL }
    val dp = activity.resources.displayMetrics.density
    val label = TextView(activity).apply {
        text = "OpenCode · $message"; gravity = Gravity.CENTER
        setTextColor(color("textColor", Color.WHITE)); setBackgroundColor(color("backgroundColor", Color.GRAY))
        val padding = (12 * dp).roundToInt(); setPadding(padding, padding, padding, padding)
    }
    Toast(activity).apply {
        view = label; this.duration = duration
        setGravity(position or Gravity.CENTER_HORIZONTAL, 0, if (position == Gravity.CENTER_VERTICAL) 0 else (48 * dp).roundToInt())
    }.show()
}
