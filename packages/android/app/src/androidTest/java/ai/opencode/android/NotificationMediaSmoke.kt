package ai.opencode.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** After setup only clicks on the actual SystemUI transport control change playback until cleanup. */
internal fun notificationMediaSmoke(test: Instrumentation, skip: Boolean = false): String {
    check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator required" }
    val c = test.targetContext; val os = c.getSystemService(NotificationManager::class.java)
    val rpc = DeviceMcp(c, { error("Unexpected location") }, { _, _ -> error("Unexpected foreground action") })
    val id = "media-${UUID.randomUUID()}"; val title = "Synthetic track ${id.takeLast(6)}"
    fun wav(prefix: String, seconds: Int): File {
        val file = File.createTempFile(prefix, ".wav", File(c.filesDir, "home/projects").apply { mkdirs() })
        val rate = 8000; val length = rate * 2 * seconds
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36+length).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate*2).putShort(2).putShort(16).put("data".toByteArray()).putInt(length)
        file.outputStream().use { it.write(header.array()); it.write(ByteArray(length)) }; return file
    }
    val file = wav("media-A-", 180); val second = if (skip) wav("media-B-", 137) else null
    fun player(op: String, path: String? = null) = JSONObject(rpc.smokeCall("media_player", JSONObject().put("operation", op).put("path", path)))
    fun notice() = os.activeNotifications.singleOrNull { it.tag == id && it.id == 2 }?.notification
    fun await(label: String, timeout: Long, f: () -> Boolean) {
        val end = SystemClock.elapsedRealtime()+timeout
        while (!f()) { check(SystemClock.elapsedRealtime() < end) { "Timed out: $label" }; SystemClock.sleep(100) }
    }
    val ui = test.uiAutomation
    fun clickTransport(label: String, systemLabel: String, resource: String = "actionPlayPause"): String {
        var target: AccessibilityNodeInfo? = null
        await("SystemUI $label on $title", 20000) {
            val root = ui.rootInActiveWindow ?: return@await false
            if (root.packageName?.toString() != "com.android.systemui") return@await false
            val card = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/qs_media_controls").singleOrNull { node ->
                node.isVisibleToUser && node.findAccessibilityNodeInfosByViewId("com.android.systemui:id/header_title").any { it.text?.toString() == title }
            } ?: return@await false
            target = card.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$resource").singleOrNull {
                it.isVisibleToUser && it.isClickable && it.isEnabled && (resource != "actionPlayPause" || it.contentDescription?.toString() in setOf(label, systemLabel))
            }; target != null
        }
        val button = checkNotNull(target)
        val observed = button.contentDescription.toString()
        check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "SystemUI rejected $label click" }
        return observed
    }
    try {
        rpc.start(10000, true)
        val args = JSONObject().put("type", "media").put("id", id).put("title", title).put("message", "OpenCode fixture").put("ongoing", true)
        check(rpc.smokeCall("send_notification", args, expectError = true).contains("Load a file")); check(notice() == null)
        val first = if (second == null) player("play", file.name) else JSONObject(rpc.smokeCall("media_player", JSONObject().put("operation", "play").put("paths", JSONArray(listOf(file.name, second.name)))))
        check(first.getString("state") == "playing" && first.getString("path") == file.name && first.getInt("durationMs") == 180000)
        val receipt = JSONObject(rpc.smokeCall("send_notification", args)); check(receipt.get("posted") == true && receipt.getString("id") == id)
        await("media notification", 10000) { notice() != null }
        val n = notice()!!; val style = n.extras.getString(Notification.EXTRA_TEMPLATE); check(style == Notification.MediaStyle::class.java.name)
        val labels = n.actions.map { it.title.toString() }
        val expected = if (skip) listOf(R.string.media_previous, R.string.media_pause, R.string.media_play, R.string.media_next) else listOf(R.string.media_pause, R.string.media_play)
        check(labels == expected.map { c.getString(it) }) { labels.toString() }
        @Suppress("DEPRECATION") val token = n.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)!!
        val control = MediaController(c, token); check(control.packageName == c.packageName)
        val prior = player("pause").getInt("positionMs")
        check(control.playbackState?.state == PlaybackState.STATE_PAUSED)
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        check(ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS))
        if (second != null) {
            val trace = JSONArray(); val clicks = JSONArray()
            fun observe(track: File, ms: Int) {
                var actual = JSONObject()
                await("paused native track ${track.name}", 8000) {
                    actual = player("info"); actual.optString("state") == "paused" && actual.optString("path") == track.name && actual.optInt("durationMs") == ms &&
                        control.playbackState?.state == PlaybackState.STATE_PAUSED && control.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) == ms.toLong()
                }
                trace.put(actual)
            }
            observe(file, 180000)
            clicks.put(clickTransport(c.getString(R.string.media_next), "Next", "actionNext")); observe(second, 137000)
            clicks.put(clickTransport(c.getString(R.string.media_previous), "Previous", "actionPrev")); observe(file, 180000)
            player("stop"); await("stop removes queue notice", 10000) { notice() == null }
            return JSONObject().put("id", id).put("style", style).put("orderedActions", JSONArray(labels)).put("clickedSystemUI", clicks).put("playerReadbacks", trace).put("removedOnStop", true).toString()
        }
        val playButton = clickTransport(c.getString(R.string.media_play), "Play")
        await("SystemUI play", 8000) { player("info").getString("state") == "playing" && control.playbackState?.state == PlaybackState.STATE_PLAYING }
        val start = player("info").getInt("positionMs")
        await("native playback advances", 6000) { player("info").getInt("positionMs") >= start + 400 }
        val later = player("info").getInt("positionMs")
        val pauseButton = clickTransport(c.getString(R.string.media_pause), "Pause")
        await("SystemUI pause", 8000) { player("info").getString("state") == "paused" && control.playbackState?.state == PlaybackState.STATE_PAUSED }
        val paused = player("info"); SystemClock.sleep(650); val stable = player("info")
        check(paused.getString("path") == file.name && stable.getString("state") == "paused" && paused.getInt("positionMs") == stable.getInt("positionMs"))
        player("stop"); await("media notice released", 10000) { notice() == null }
        return JSONObject().put("id", id).put("style", style).put("orderedActions", JSONArray(labels)).put("sessionPackage", control.packageName)
            .put("clickedSystemUI", JSONArray(listOf(playButton, pauseButton))).put("beforePlayMs", prior).put("playingPositionsMs", JSONArray(listOf(start,later))).put("pausedPositionsMs", JSONArray(listOf(paused.getInt("positionMs"),stable.getInt("positionMs"))))
            .put("stationaryIntervalMs",650).put("removedOnStop", true).toString()
    } finally { try { player("stop") } finally { rpc.stop(); file.delete(); second?.delete() } }
}
