package ai.opencode.android

import android.app.Instrumentation
import android.content.Intent
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONObject

/** Caller sets and restores the ringer mode on the test AVD. Do not infer execution from vibrate() returning. */
internal fun vibrateSmoke(test: Instrumentation, scenario: String): String {
    val c = test.targetContext
    val a = test.startActivitySync(Intent(c, ServerPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val server = DeviceMcp(c, { error("Unexpected location") }, { _, _ -> error("Unexpected UI request") })
    val mode = c.getSystemService(AudioManager::class.java).ringerMode
    fun records(): List<String> = ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("dumpsys vibrator_manager")).bufferedReader().use { reader ->
        val dump = reader.readText(); check("Recent vibrations:" in dump)
        dump.substringAfter("Recent vibrations:").substringBefore("Aggregated vibration history:").lineSequence().filter { c.packageName in it }.map { it.trim() }.toList()
    }
    try {
        server.start(10000, true)
        val normal = scenario == "vibrate-normal"; val forced = scenario == "vibrate-forced"
        check(mode == if (normal) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_SILENT) { "Unexpected ringerMode=$mode" }
        val args = JSONObject(); if (!normal) args.put("milliseconds", if (forced) 653 else 431); if (forced) args.put("force", true)
        val before = records(); val reply = JSONObject(server.smokeCall("vibrate", args)); SystemClock.sleep(1550); val after = records()
        val added = after - before.toSet()
        val ms = if (normal) 1000 else if (forced) 653 else 431
        check(reply.getInt("milliseconds") == ms)
        if (!normal && !forced) {
            check(!reply.getBoolean("submitted") && reply.getString("reason") == "silent" && before == after) { "Silent request reached vibrator: $added" }
        } else {
            check(reply.getBoolean("submitted") && added.size == 1 && added.single().let {
                it.contains("finished") && it.contains("usage: ALARM") && it.contains("played: Step=${ms}ms")
            }) { "Expected one completed $ms ms alarm-usage effect: $added" }
        }
        return JSONObject().put("ringerMode", mode).put("mcp", reply).put("before", before.size).put("after", after.size).put("added", added.joinToString("; ")).toString()
    } finally { server.stop(); test.runOnMainSync { a.finish() } }
}
