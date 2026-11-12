package ai.opencode.android

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Only a target Activity lifecycle callback writes the marker; posting and tapping never write it. */
internal fun notificationButtonSmoke(test: Instrumentation, checkBody: Boolean): String {
    check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator required" }
    val c = test.targetContext; val app = c.applicationContext as Application
    val rpc = DeviceMcp(c, { error("Unexpected location") }, { _, _ -> error("Unexpected foreground request") })
    val manager = c.getSystemService(NotificationManager::class.java)
    val id = "button-${UUID.randomUUID()}"; val title = "Synthetic actions ${id.takeLast(8)}"
    val labels = listOf("Picker", "Folders", "Remote")
    val names = listOf("open_app", "open_folders", "open_remote")
    val targets = listOf("ServerPicker", "FileAccessActivity", "MainActivity")
    val marker = File(c.cacheDir, "$id.log"); check(!marker.exists())
    fun lines() = if (marker.exists()) marker.readLines() else emptyList()
    val callback = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, state: Bundle?) {
            if (checkBody && a is MainActivity && a.intent.identifier == null && a.intent.getBooleanExtra("local", false)) marker.appendText("MainActivity local=true\n")
            val identifier = a.intent.identifier ?: return
            if (identifier.endsWith(":$id")) marker.appendText("$identifier ${a.javaClass.simpleName}\n")
        }
        override fun onActivityStarted(a: Activity) = Unit
        override fun onActivityResumed(a: Activity) = Unit
        override fun onActivityPaused(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
    }
    fun await(what: String, timeoutMs: Long = 15000, pred: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!pred()) { check(SystemClock.elapsedRealtime() < until) { "Missing $what" }; SystemClock.sleep(75) }
    }
    fun active() = manager.activeNotifications.singleOrNull { it.id == 2 && it.tag == id }?.notification
    app.registerActivityLifecycleCallbacks(callback)
    try {
        rpc.start(10000, true)
        val args = JSONObject().put("id", id).put("title", title).put("message", "Synthetic button smoke").put("ongoing", true)
        if (checkBody) {
            args.put("button1", "Picker").put("button1Action", "open_app")
            check(rpc.smokeCall("send_notification", args.put("action", "unknown"), true).startsWith("Unknown action"))
            check(active() == null && lines().isEmpty())
            val trace = JSONArray(); val expected = mutableListOf<String>()
            for (custom in listOf(true, false)) {
                val message = if (custom) "Tap body: folders" else "Tap body: local"
                if (custom) args.put("action", "open_folders") else args.remove("action")
                val sent = JSONObject(rpc.smokeCall("send_notification", args.put("message", message)))
                check(sent.get("posted") == true && sent.getString("id") == id)
                await("active body update") { active()?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT) == message }
                val notice = active()!!; val button = notice.actions.single()
                check(notice.contentIntent != null && notice.contentIntent != button.actionIntent && button.title.toString() == "Picker")
                check(lines() == expected)
                test.sendStatus(1, Bundle().apply { putString("ready", "Tap '$message' on '$title' (not Picker); markers=${lines().size}") })
                expected += if (custom) "body:$id FileAccessActivity" else "MainActivity local=true"
                await("operator's body tap", 120000) { lines().size >= expected.size }
                test.waitForIdleSync(); check(lines() == expected) { "Unexpected navigation: ${lines()}" }
                trace.put(JSONObject().put("action", if (custom) "open_folders" else "omitted").put("buttonAction", "open_app").put("markers", JSONArray(lines())))
            }
            return JSONObject().put("id", id).put("beforeClicks", 0).put("bodyClicks", trace).toString()
        }
        for (slot in 1..3) {
            val field = "button$slot"
            check(rpc.smokeCall("send_notification", args.put(field, labels[slot-1]), true) == "Supply $field and ${field}Action together")
            check(rpc.smokeCall("send_notification", args.put("${field}Action", "unknown"), true).startsWith("Unknown ${field}Action"))
            args.put("${field}Action", names[slot-1])
        }
        check(active() == null)
        val sent = JSONObject(rpc.smokeCall("send_notification", args))
        check(sent.get("posted") == true && sent.get("id") == id)
        await("active notification") { active() != null }
        val actions = active()!!.actions; val actualLabels = actions.map { it.title.toString() }; check(actualLabels == labels)
        check(actions.map { it.actionIntent }.distinct().size == 3)
        val before = lines().size; check(before == 0)
        val expected = mutableListOf<String>(); val trace = JSONArray()
        for (slot in listOf(2, 1, 3)) {
            test.sendStatus(1, Bundle().apply { putString("ready", "Tap '${labels[slot-1]}' on '$title'; markers=${lines().size}") })
            expected += "button$slot:$id ${targets[slot-1]}"
            await("operator's button $slot click", 120000) { lines().size >= expected.size }
            check(lines() == expected) { "Wrong delivery: ${lines()}" }
            val cancelled = try { actions[slot-1].actionIntent.send(); false } catch (_: PendingIntent.CanceledException) { true }
            test.waitForIdleSync(); check(cancelled && lines() == expected)
            trace.put(JSONObject().put("slot", slot).put("markers", JSONArray(lines())).put("replayCancelled", cancelled))
        }
        return JSONObject().put("sent", sent).put("activeButtonTitles", JSONArray(actualLabels))
            .put("beforeClick", before).put("clicks", trace).toString()
    } finally {
        app.unregisterActivityLifecycleCallbacks(callback)
        try { if (active() != null) rpc.smokeCall("notification_remove", JSONObject().put("id", id)) }
        finally { rpc.stop(); marker.delete() }
    }
}
