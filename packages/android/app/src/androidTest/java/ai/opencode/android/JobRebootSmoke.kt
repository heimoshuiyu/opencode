package ai.opencode.android

import android.app.Instrumentation
import android.app.job.JobScheduler
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Two invocations on the same disposable AVD; reboot the guest between them, without reinstalling. */
internal fun jobRebootSmoke(test: Instrumentation, prepare: Boolean): String {
    val c = test.targetContext; val os = c.getSystemService(JobScheduler::class.java)
    val boot = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT)
    val keep = 27101; val drop = 27102
    val a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("local", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { a.deviceInteraction }; val rpc = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor(); val dir = File(c.filesDir, "home/projects/reboot-proof")
    val state = File(dir, "fixture.json"); val out = JSONObject().put("boot", boot)
    fun path(id: Int) = "reboot-proof/$id.sh"
    fun output(id: Int) = File(dir, "$id.out")
    fun await(label: String, f: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 60000
        while (!f()) { check(SystemClock.elapsedRealtime() < until) { "Timed out: $label" }; SystemClock.sleep(50) }
    }
    fun call(op: String, id: Int? = null) = JSONObject(rpc.smokeCall("job_scheduler", JSONObject().put("operation", op).put("id", id as Any?)))
    fun list() = call("list").getJSONArray("jobs").let { j -> (0 until j.length()).map { j.getJSONObject(it) }.associateBy { it.getInt("id") } }
    fun confirmed(op: String, id: Int, yes: Boolean): String {
        await("foreground") { main { a.hasWindowFocus() && LocalHostService.running } }
        val args = JSONObject().put("operation", op).put("id", id)
        if (op == "schedule") args.put("path", path(id)).put("persisted", true).put("delayMs", 3600000).put("network", "none").put("requiresBatteryNotLow", false)
        val future = worker.submit<String> { rpc.smokeCall("job_scheduler", args, !yes, 90000) }
        await("confirmation") { main {
            if (future.isDone) error("Premature result: ${future.get()}")
            val root = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() && it.findViewById<TextView>(android.R.id.message)?.text?.let { m ->
                m.contains(a.getString(R.string.job_retained_notice)) && m.contains(path(id)) && m.contains(File(c.filesDir, "home/projects/${path(id)}").readText())
            } == true } ?: return@main false
            root.findViewById<Button>(if (yes) android.R.id.button1 else android.R.id.button2).performClick()
        } }; return future.get(10, TimeUnit.SECONDS)
    }
    try {
        rpc.start(10000, true)
        if (prepare) {
            check(os.getPendingJob(keep) == null && os.getPendingJob(drop) == null && !dir.exists()); check(dir.mkdirs())
            val mark = UUID.randomUUID().toString()
            for (id in listOf(keep, drop)) File(dir, "$id.sh").writeText("printf '%s\\n' '$mark:$id' > reboot-proof/$id.out\n")
            val denied = confirmed("schedule", keep, false); check(denied == "Device request declined" && os.getPendingJob(keep) == null)
            for (id in listOf(keep, drop)) { check(JSONObject(confirmed("schedule", id, true)).getBoolean("persisted")); check(os.getPendingJob(id)!!.isPersisted) }
            out.put("listedBeforeCancel", list().keys.sorted().toString()); check(call("cancel", drop).getInt("cancelled") == drop)
            val job = os.getPendingJob(keep)!!; check(list().keys == setOf(keep) && os.getPendingJob(drop) == null)
            state.writeText(JSONObject().put("boot", boot).put("generation", job.extras.getString("nonce")).put("mark", mark).toString())
            check(!output(keep).exists() && !output(drop).exists()); return out.put("denied", denied).put("retained", keep).put("cancelled", drop).toString()
        }
        val first = JSONObject(state.readText()); check(boot > first.getInt("boot")) { "A guest reboot is required" }
        val saved = os.getPendingJob(keep)!!; check(saved.isPersisted && saved.extras.getString("nonce") == first.getString("generation") && os.getPendingJob(drop) == null)
        check(list().keys == setOf(keep) && list().getValue(keep).getBoolean("needsConfirmation")); check(!output(keep).exists() && !output(drop).exists())
        val denied = confirmed("resume", keep, false); check(denied == "Device request declined" && list().getValue(keep).getBoolean("needsConfirmation"))
        check(JSONObject(confirmed("resume", keep, true)).getBoolean("resumed")); check(!list().getValue(keep).getBoolean("needsConfirmation"))
        check(os.getPendingJob(keep)!!.extras.getString("nonce") == first.getString("generation"))
        // Expedite only the hour-long test delay. Do not invoke the JobService or the script directly.
        val dispatch = ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("cmd jobscheduler run -f ${c.packageName} $keep")).bufferedReader().use { it.readText().trim() }
        await("one-shot completion") { output(keep).exists() && os.getPendingJob(keep) == null }; check(output(keep).readText() == "${first.getString("mark")}:$keep\n")
        check(!output(drop).exists() && os.getPendingJob(drop) == null && list().isEmpty())
        return out.put("previousBoot", first.getInt("boot")).put("unchangedGeneration", true).put("resumeDenied", denied).put("osDispatch", dispatch)
            .put("keptOutput", output(keep).readText().trim()).put("cancelledOutputExists", false).toString()
    } finally { main { ui.cancel("Fixture closed"); a.finish() }; rpc.stop(); worker.shutdownNow() }
}
