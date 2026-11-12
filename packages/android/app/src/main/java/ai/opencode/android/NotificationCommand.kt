package ai.opencode.android

import android.app.Activity
import android.app.KeyguardManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.TimeUnit

/** An immutable one-shot notification intent opens review; only the positive button starts a shell. */
class NotificationCommand : Activity() {
    companion object {
        // Reject invisible/control and bidi characters rather than showing a different string from the one run.
        internal fun valid(value: Any?) = value is String && value.isNotBlank() && value.length <= 1024 && value.all { it in ' '..'~' }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val command = intent.getStringExtra("command")
        if (state != null || command == null || !valid(command)) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(body)
        val pad = (20 * resources.displayMetrics.density).toInt()
        body.setPadding(pad, pad, pad, pad)
        body.addView(TextView(this).apply { setText(R.string.notification_command_review); textSize = 18f })
        body.addView(ScrollView(this).apply {
            addView(TextView(this@NotificationCommand).apply {
                text = command; typeface = Typeface.MONOSPACE; textSize = 16f; setPadding(0, pad, 0, pad)
            })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(Button(this).apply { setText(R.string.notification_command_deny); setOnClickListener { finish() } })
        var consumed = false
        body.addView(Button(this).apply {
            setText(R.string.notification_command_allow); filterTouchesWhenObscured = true
            setOnClickListener {
                if (consumed || isFinishing || !hasWindowFocus() || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return@setOnClickListener
                consumed = true; isEnabled = false
                val ctx = applicationContext
                Thread {
                    val ok = runCatching {
                        val home = File(ctx.filesDir, "home"); check(home.isDirectory || home.mkdir())
                        val devNull = File("/dev/null")
                        val pb = ProcessBuilder("/system/bin/sh", "-c", command).directory(home)
                            .redirectInput(devNull).redirectOutput(devNull).redirectError(devNull)
                        pb.environment().apply { clear(); put("HOME", home.path); put("PATH", "/system/bin"); put("TMPDIR", ctx.cacheDir.path) }
                        val proc = pb.start()
                        try { proc.waitFor(30, TimeUnit.SECONDS) && proc.exitValue() == 0 }
                        finally { if (proc.isAlive) proc.destroyForcibly() }
                    }.getOrDefault(false)
                    runOnUiThread { Toast.makeText(ctx, if (ok) R.string.notification_command_done else R.string.notification_command_failed, Toast.LENGTH_SHORT).show() }
                }.start()
                finish()
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) return true
        return super.dispatchTouchEvent(event)
    }
}
