package ai.opencode.android

import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID

/** Do not send the deleteIntent from the test. The operator swipes one real SystemUI row. */
internal fun notificationDeleteSmoke(test: Instrumentation): String {
    check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator required" }
    val c = test.targetContext
    val rpc = DeviceMcp(c, { error("Unexpected location") }, { _, _ -> error("Unexpected UI call") })
    val os = c.getSystemService(NotificationManager::class.java)
    val prefs = c.getSharedPreferences("task-dismissals", Context.MODE_PRIVATE)
    fun marker() = prefs.getString("last", null)
    fun active(id: String) = os.activeNotifications.singleOrNull { it.id == 2 && it.tag == id }
    fun await(label: String, limit: Long = 10000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + limit
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timed out: $label" }; SystemClock.sleep(75) }
    }
    val run = UUID.randomUUID().toString().take(12); val swipe = "swipe-$run"; val control = "remove-$run"
    fun args(id: String) = JSONObject().put("id", id).put("title", id).put("message", "Synthetic dismissal fixture").put("onDelete", "record_dismissal")
    fun post(id: String) {
        check(JSONObject(rpc.smokeCall("send_notification", args(id))).getString("id") == id)
        await("delete intent for $id") { active(id)?.notification?.deleteIntent != null }
    }
    try {
        rpc.start(10000, true)
        val before = marker()
        check(rpc.smokeCall("send_notification", args(swipe).put("onDelete", "unknown"), expectError = true) == "onDelete must be record_dismissal")
        post(swipe)
        check(marker() == before)
        test.sendStatus(1, Bundle().apply { putString("ready", "Swipe '$swipe' sideways in the notification shade; no new marker") })
        await("notification swiped and dismissal persisted", 120000) { active(swipe) == null && marker()?.let { JSONObject(it).optString("id") == swipe } == true }
        val afterSwipe = checkNotNull(marker()); val receipt = JSONObject(afterSwipe)
        check(receipt.getLong("dismissedAt") > 0)
        post(control); check(marker() == afterSwipe)
        val removed = JSONObject(rpc.smokeCall("notification_remove", JSONObject().put("id", control)))
        check(removed.get("removed") == true); await("control absent") { active(control) == null }
        val current = JSONObject(rpc.smokeCall("notification_last_dismissal")).getJSONObject("last")
        check(marker() == afterSwipe && current.getString("id") == swipe && current.getLong("dismissedAt") == receipt.getLong("dismissedAt"))
        return JSONObject().put("unchangedAtPost", true).put("swipeAbsent", active(swipe) == null).put("markerAfterSwipe", receipt)
            .put("controlId", control).put("controlRemoved", removed).put("controlAbsent", active(control) == null).put("markerAfterMcpRemove", current).toString()
    } finally {
        try { for (id in listOf(swipe, control)) if (active(id) != null) rpc.smokeCall("notification_remove", JSONObject().put("id", id)) }
        finally { rpc.stop() }
    }
}
