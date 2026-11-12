package ai.opencode.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.UUID

/** Operator types, submits or backs out in SystemUI between phases. Never constructs reply results. */
internal fun notificationReplySmoke(test: Instrumentation, phase: String, run: UUID): String {
    check(Build.HARDWARE in setOf("ranchu", "goldfish"))
    if (phase == "reply-system-ui") return exerciseReplyUi(test, run)
    val c = test.targetContext; val os = c.getSystemService(NotificationManager::class.java)
    val rpc = DeviceMcp(c, { error("Unexpected location") }, { _, _ -> error("Unexpected interaction") })
    val sent = "reply-$run"; val cancel = "draft-$run"; val text = "合成 $run · A&B"
    fun active(id: String) = os.activeNotifications.singleOrNull { it.id == 2 && it.tag == id }?.notification
    fun take(id: String) = JSONObject(rpc.smokeCall("notification_take_reply", JSONObject().put("id", id)))
    try {
        rpc.start(10000, true)
        if (phase != "reply-post-send") {
            val raw = checkNotNull(c.getSharedPreferences("task-replies", Context.MODE_PRIVATE).getString("latest", null)) { "No APK receipt" }
            val receipt = JSONObject(raw); check(receipt.getString("id") == sent && receipt.getString("text") == text) { "Receipt does not match synthetic input" }
            if (phase == "reply-verify") {
                check(active(cancel) != null) { "Cancelling input must not submit the notification" }
                check(take(cancel).isNull("reply"))
                val taken = take(sent).getJSONObject("reply"); check(taken.toString() == raw && take(sent).isNull("reply"))
                rpc.smokeCall("notification_remove", JSONObject().put("id", cancel))
                return JSONObject().put("apkReceipt", receipt).put("cancelId", cancel).put("cancelReceipt", JSONObject.NULL).put("mcpTaken", taken).put("consumed", true).toString()
            }
        }
        val positive = phase == "reply-post-send"; val id = if (positive) sent else cancel
        val title = if (positive) "Submit ${run.toString().take(8)}" else "Cancel ${run.toString().take(8)}"
        check(active(id) == null && take(id).isNull("reply"))
        val posted = JSONObject(rpc.smokeCall("send_notification", JSONObject().put("id", id).put("title", title).put("message", "Synthetic inline reply")
            .put("button1", "Reply here").put("button1Action", "reply")))
        check(posted.get("id") == id && posted.get("posted") == true)
        val end = SystemClock.elapsedRealtime() + 5000
        while (active(id) == null) { check(SystemClock.elapsedRealtime() < end); SystemClock.sleep(50) }
        val action = active(id)!!.actions.single(); check(action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && action.remoteInputs.single().allowFreeFormInput)
        return JSONObject().put("id", id).put("title", title).put("button", action.title.toString()).put("input", if (positive) text else "Discard $run").toString()
    } finally { rpc.stop() }
}

/** Drives the real shade editor while all requests stay on the guest, without a host IME. */
private fun exerciseReplyUi(test: Instrumentation, run: UUID): String {
    fun step(stage: String) = test.sendStatus(1, Bundle().apply { putString("replyStage", stage) })
    step("runner entered: $run")
    val ui = test.uiAutomation
    ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    fun nodes(n: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(n); for (i in 0 until n.childCount) n.getChild(i)?.let { yieldAll(nodes(it)) }
    }
    fun shade() = ui.windows.asSequence().mapNotNull { it.root }.filter { it.packageName?.toString() == "com.android.systemui" }.flatMap(::nodes).filter { it.isVisibleToUser }
    fun await(label: String, pred: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (!pred()) { check(SystemClock.elapsedRealtime() < end) { "Timed out: $label" }; SystemClock.sleep(75) }
    }
    val prefs = test.targetContext.getSharedPreferences("task-replies", Context.MODE_PRIVATE)
    fun editor() = shade().firstOrNull { it.isEditable && it.isFocused && it.viewIdResourceName?.contains("remote_input") == true }
    for (submit in listOf(true, false)) {
        val fixture = JSONObject(notificationReplySmoke(test, if (submit) "reply-post-send" else "reply-post-cancel", run))
        val title = fixture.getString("title"); val text = fixture.getString("input"); val before = prefs.getString("latest", null)
        step("posted ${fixture.getString("id")}")
        check(ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
        var action: AccessibilityNodeInfo? = null
        await("reply button on $title") {
            val heading = shade().firstOrNull { it.text?.toString() == title } ?: return@await false
            fun ancestors(node: AccessibilityNodeInfo) = generateSequence(node) { it.parent }.filter { it.viewIdResourceName?.endsWith("/expandableNotificationRow") == true }
            val rows = ancestors(heading).toList(); val row = rows.firstOrNull() ?: return@await false
            action = nodes(row).firstOrNull { it.isVisibleToUser && it.isClickable && it.isEnabled && (it.text?.toString().equals("Reply here", true) || it.contentDescription?.toString().equals("Reply here", true)) }
            if (action == null) {
                // A collapsed group can expose the child's title without its action or expander.
                // Expand only controls owned by this child's ancestor rows, never a sibling row.
                rows.asReversed().firstNotNullOfOrNull { owner -> nodes(owner).firstOrNull {
                    it.isVisibleToUser && it.isClickable && it.viewIdResourceName?.endsWith("/expand_button") == true &&
                        it.contentDescription?.toString() in setOf("Expand", "展开") && ancestors(it).firstOrNull() == owner
                } }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            action != null
        }
        check(action!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)); await("SystemUI inline editor") { editor() != null }
        check(editor()!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }))
        await("verbatim text in editor") { editor()?.text?.toString() == text }; check(prefs.getString("latest", null) == before)
        step("SystemUI editor matched ${if (submit) "reply" else "draft"}; stored receipt unchanged")
        if (submit) {
            val send = shade().single { it.isClickable && it.isEnabled && it.viewIdResourceName?.endsWith("/remote_input_send") == true }
            check(send.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("app reply receipt") { prefs.getString("latest", null)?.let(::JSONObject)?.optString("id") == fixture.getString("id") }
            val stored = JSONObject(checkNotNull(prefs.getString("latest", null)))
            check(stored.getString("text") == text && stored.getString("id") == fixture.getString("id"))
            step("stored after Send: $stored")
        } else {
            check(ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            SystemClock.sleep(350)
            if (editor() != null) check(ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await("dismissed editor") { editor() == null }; check(prefs.getString("latest", null) == before)
            step("editor closed with Back; stored after cancel: ${prefs.getString("latest", null)}")
        }
    }
    return notificationReplySmoke(test, "reply-verify", run)
}
