package ai.opencode.android

import android.app.Instrumentation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Query Android's own media shell client, never its setter. Only confirmed MCP calls change volume. */
internal fun volumeSmoke(test: Instrumentation): String {
    fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText().trim() }
    check(shell("getprop ro.boot.qemu") == "1") { "Disposable emulator required" }
    val streams = mapOf("voice_call" to 0, "system" to 1, "ring" to 2, "music" to 3, "alarm" to 4, "notification" to 5)
    data class Reading(val level: Int, val min: Int, val max: Int)
    fun read(id: Int): Reading {
        val text = shell("cmd media_session volume --stream $id --get")
        val m = Regex("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)]").find(text) ?: error(text)
        return Reading(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }
    val c = test.targetContext
    val a = test.startActivitySync(Intent(c, ServerPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { DeviceInteraction(a) }; val rpc = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor()
    fun await(f: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (!main(f)) { check(SystemClock.elapsedRealtime() < end) { "Volume prompt/foreground not available" }; SystemClock.sleep(50) }
    }
    fun set(name: String, level: Int, allow: Boolean): String {
        await { a.hasWindowFocus() }
        val args = JSONObject().put("stream", name).put("level", level)
        val call = worker.submit<String> { rpc.smokeCall("volume", args, expectError = !allow, timeoutMs = 45000) }
        await {
            check(!call.isDone) { "Volume returned before consent: ${call.get()}" }
            val root = WindowInspector.getGlobalWindowViews().singleOrNull { it.hasWindowFocus() &&
                it.findViewById<TextView>(android.R.id.message)?.text == a.getString(R.string.device_request_detail, args.toString()) }
            root?.findViewById<Button>(if (allow) android.R.id.button1 else android.R.id.button2)?.performClick() == true
        }
        return call.get(10, TimeUnit.SECONDS)
    }
    try {
        rpc.start(10000, true)
        val initial = streams.mapValues { read(it.value) }
        val list = JSONArray(rpc.smokeCall("volume"))
        check(list.length() == streams.size)
        val rows = (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("stream") }
        check(rows.keys == streams.keys)
        for ((name, value) in initial) { val row = rows.getValue(name); check(row.get("level") == value.level && row.get("max") == value.max) { "$name: $row vs $value" } }
        val changes = JSONArray()
        for ((name, id) in streams) {
            val old = read(id); val target = if (old.level < old.max) old.level + 1 else old.level - 1
            check(target in maxOf(1, old.min)..old.max)
            if (name == "music") {
                check(set(name, target, false) == "Device request declined")
                check(read(id) == old) { "Declining changed music volume" }
            }
            val reply = JSONObject(set(name, target, true)); val actual = read(id).level
            check(reply.get("level") == actual && actual == target) { "$name: requested=$target reply=$reply system=$actual" }
            val restored = JSONObject(set(name, old.level, true)); check(restored.get("level") == old.level && read(id) == old) { "$name restore failed" }
            changes.put(JSONObject().put("stream", name).put("before", old.level).put("requested", target).put("mcp", reply).put("system", actual).put("restored", old.level))
        }
        check(streams.mapValues { read(it.value) } == initial) { "Final levels differ from initial snapshot" }
        return JSONObject().put("list", list).put("musicDeclineUnchanged", true).put("changes", changes).toString()
    } finally { main { ui.cancel("Fixture closed"); a.finish() }; worker.shutdownNow(); rpc.stop() }
}
