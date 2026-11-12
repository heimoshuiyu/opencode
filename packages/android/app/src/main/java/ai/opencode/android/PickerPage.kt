package ai.opencode.android

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Native chrome shared by the two pages that are available before any server is connected. */
internal class PickerPage(private val activity: Activity) {
    private val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun color(day: String, night: String) = Color.parseColor(if (dark) night else day)
    // Mirrors services/site/src/component/site/site.css. Keep the native shell offline.
    val paper = color("#FFFFFF", "#0C0C0C")
    val surface = color("#F5F5F5", "#1A1A1A")
    val ink = color("#141414", "#F2F2F2")
    val muted = color("#525252", "#B8B8B8")
    val line = color("#E0E0E0", "#2E2E2E")
    val accent = color("#3B5CF6", "#4F8BFF")
    val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun dp(n: Int) = (n * activity.resources.displayMetrics.density).roundToInt()
    private fun fill(color: Int) = GradientDrawable().apply { setColor(color) }
    fun rule(parent: LinearLayout = panel) { parent.addView(View(activity).apply { setBackgroundColor(line) }, -1, dp(1)) }
    fun text(value: CharSequence, size: Int, color: Int = ink, bold: Boolean = false, mono: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    fun gap(n: Int) { panel.addView(View(activity), 1, dp(n)) }

    init {
        val scroll = ScrollView(activity).apply { setBackgroundColor(paper); isFillViewport = true }
        val frame = FrameLayout(activity)
        frame.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        frame.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val width = minOf(r - l, dp(640)); val lp = panel.layoutParams
            if (width > 0 && width != lp.width) { lp.width = width; panel.layoutParams = lp }
        }
        panel.setPadding(dp(24), dp(8), dp(24), dp(32))
        scroll.addView(frame)
        activity.window.setDecorFitsSystemWindows(false)
        val bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val p = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(p.left, p.top, p.right, p.bottom); WindowInsets.CONSUMED
        }
        activity.setContentView(scroll)
        activity.window.insetsController?.setSystemBarsAppearance(if (dark) 0 else bars, bars)
    }

    fun header(title: Int, back: Boolean = false) {
        val bar = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56) }
        bar.addView(ImageView(activity).apply {
            setImageResource(R.drawable.site_wordmark); scaleType = ImageView.ScaleType.FIT_START
            contentDescription = "OpenCode"
        }, dp(148), dp(27))
        bar.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        if (back) bar.addView(action("←", activity.getString(R.string.back)) { activity.finish() })
        else bar.addView(text("/ Android", 13, muted, mono = true))
        panel.addView(bar); rule(); gap(30)
        panel.addView(text(activity.getString(title), 38, ink, mono = true).apply {
            letterSpacing = -0.03f; isAccessibilityHeading = true
        }); gap(8)
    }

    fun section(label: String) { gap(26); panel.addView(text(label, 15, ink, mono = true).apply { isAccessibilityHeading = true }); gap(10); rule() }

    fun action(label: String, description: String = label, click: () -> Unit) = Button(activity).apply {
        text = label; contentDescription = description; isAllCaps = false; textSize = 14f; setTextColor(accent)
        minWidth = dp(48); minimumWidth = dp(48); minimumHeight = dp(48); setPadding(dp(10), 0, dp(10), 0)
        stateListAnimator = null; background = RippleDrawable(ColorStateList.valueOf(0x243B5CF6), null, fill(Color.WHITE)); setOnClickListener { click() }
    }

    fun choice(marker: String, title: Int, detail: Int, primary: Boolean = false, click: () -> Unit) {
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(88); setPadding(dp(12), dp(14), dp(12), dp(14))
            background = RippleDrawable(ColorStateList.valueOf(0x403B5CF6), fill(if (primary) ink else paper), null)
            contentDescription = "${activity.getString(title)}. ${activity.getString(detail)}"; isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
                }
            }; setOnClickListener { click() }
        }
        row.addView(text("[$marker]", 13, if (primary) paper else muted, mono = true), dp(48), -2)
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text(activity.getString(title), 20, if (primary) paper else ink, true))
        copy.addView(text(activity.getString(detail), 14, if (primary) surface else muted).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(text(" →", 20, if (primary) paper else accent, mono = true))
        for (i in 0 until row.childCount) row.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        panel.addView(row); rule()
    }
}
