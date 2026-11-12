package ai.opencode.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Android owns the timers; the user's approvals live only for this local host's lifetime. */
internal object WorkspaceJobs {
    internal enum class Network(val wire: String, val androidType: Int, val label: Int) {
        NONE("none", JobInfo.NETWORK_TYPE_NONE, R.string.job_net_none),
        ANY("any", JobInfo.NETWORK_TYPE_ANY, R.string.job_net_any),
        UNMETERED("unmetered", JobInfo.NETWORK_TYPE_UNMETERED, R.string.job_net_unmetered),
        CELLULAR("cellular", JobInfo.NETWORK_TYPE_CELLULAR, R.string.job_net_cellular),
        NOT_ROAMING("not_roaming", JobInfo.NETWORK_TYPE_NOT_ROAMING, R.string.job_net_not_roaming);

        companion object {
            fun parse(value: Any): Network = entries.firstOrNull { it.wire == value }
                ?: error("network must be one of: ${entries.joinToString { it.wire }}")
        }
    }
    internal class Spec(val path: String, val hash: String, val delay: Long, val period: Long, val requiresCharging: Boolean, val requiresBatteryNotLow: Boolean, val requiresStorageNotLow: Boolean, val network: Network, val persisted: Boolean, val nonce: String = UUID.randomUUID().toString()) {
        fun json(id: Int? = null) = JSONObject().put("id", id).put("path", path).put("sha256", hash)
            .put("delayMs", delay).put("periodMs", period).put("requiresCharging", requiresCharging)
            .put("requiresBatteryNotLow", requiresBatteryNotLow).put("requiresStorageNotLow", requiresStorageNotLow)
            .put("network", network.wire).put("persisted", persisted)
    }
    private val approved = mutableMapOf<Int, Spec>()
    internal class Run(val spec: Spec) { var process: Process? = null; var group = 0 }
    private val runs = mutableMapOf<Int, Run>()
    private var host = Any()
    private fun scheduler(c: Context) = c.getSystemService(JobScheduler::class.java)
    private fun workspace(c: Context) = File(c.filesDir.canonicalFile, "home/projects")

    // Android owns the registration. A local nonce proves ownership but is never execution approval.
    private fun tokens(c: Context) = c.getSharedPreferences("retained-job-generations", Context.MODE_PRIVATE)
    private fun retained(c: Context, job: JobInfo): Spec? {
        if (!job.isPersisted || job.service != ComponentName(c, WorkspaceJobService::class.java)) return null
        val nonce = job.extras.getString("nonce") ?: return null
        if (tokens(c).getString(job.id.toString(), null) != nonce) return null
        val data = job.extras.getString("retained") ?: return null
        return runCatching { JSONObject(data).let { j -> Spec(j.getString("path"), j.getString("sha256"), j.getLong("delayMs"), j.getLong("periodMs"),
            j.getBoolean("requiresCharging"), j.getBoolean("requiresBatteryNotLow"), j.getBoolean("requiresStorageNotLow"), Network.parse(j.getString("network")), true, nonce) } }.getOrNull()
    }
    private fun known(c: Context, job: JobInfo) = approved[job.id]?.takeIf { job.service == ComponentName(c, WorkspaceJobService::class.java) && job.extras.getString("nonce") == it.nonce } ?: retained(c, job)

    private fun script(c: Context, path: String): String {
        require(path.length <= 255 && path.endsWith(".sh") && path.split('/').all { it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._ -]*")) }) { "Expected a relative .sh file in the local workspace" }
        val file = File(workspace(c), path)
        require(file.canonicalFile == file) { "Symlinks are not accepted for scheduled scripts" }
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try {
            check(OsConstants.S_ISREG(Os.fstat(fd).st_mode)) { "Expected a regular script" }
            val bytes = ByteArray(8193); var size = 0
            while (size < bytes.size) {
                val n = Os.read(fd, bytes, size, bytes.size - size); if (n == 0) break; size += n
            }
            require(size in 1..8192) { "Scripts must be 1–8192 UTF-8 bytes" }
            return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, size)).toString().also { text ->
                require(text.none { (it.isISOControl() && it != '\n' && it != '\t') || Character.getType(it) == Character.FORMAT.toInt() }) { "Script contains non-displayable characters" }
            }
        } finally { Os.close(fd) }
    }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun call(c: Context, a: JSONObject, confirm: (JSONObject) -> Unit): JSONObject {
        val os = scheduler(c)
        when (a.getString("operation")) {
            "list" -> return synchronized(this) {
                JSONObject().put("jobs", JSONArray(os.allPendingJobs.filter { it.service == ComponentName(c, WorkspaceJobService::class.java) }
                    .mapNotNull { j -> known(c, j)?.json(j.id)?.put("needsConfirmation", approved[j.id]?.nonce != j.extras.getString("nonce"))?.put("running", runs.containsKey(j.id)) }))
            }
            "cancel" -> return synchronized(this) {
                val id = a.getInt("id"); check(cancelApproved(c, os, id)) { "No such approved job" }
                JSONObject().put("cancelled", id)
            }
            "cancel_all" -> return synchronized(this) {
                require(a.length() == 1) { "cancel_all takes no id or schedule options" }
                val ids = (approved.keys + os.allPendingJobs.filter { retained(c, it) != null }.map { it.id }).sorted().filter { cancelApproved(c, os, it) }
                JSONObject().put("cancelled", JSONArray(ids))
            }
            "resume" -> {
                require(a.length() == 2 && a.opt("id") is Int) { "resume requires only an integer id" }
                val id = a.getInt("id"); val lifetime = synchronized(this) { host }
                val spec = synchronized(this) { os.getPendingJob(id)?.let { retained(c, it) } } ?: error("No such retained job")
                val source = script(c, spec.path); check(digest(source) == spec.hash) { "Script changed; schedule it again" }
                confirm(spec.json(id).put("resume", true).put("script", source))
                return synchronized(this) {
                    check(host === lifetime && LocalHostService.running) { "Local server stopped" }
                    check(os.getPendingJob(id)?.let { retained(c, it) }?.nonce == spec.nonce) { "Job changed during confirmation" }
                    if (approved[id]?.nonce != spec.nonce) approved[id] = spec
                    spec.json(id).put("resumed", true)
                }
            }
            "schedule" -> Unit
            else -> error("Expected schedule, list, resume, cancel or cancel_all")
        }
        val lifetime = synchronized(this) { host }
        val path = a.getString("path"); val source = script(c, path)
        fun ms(key: String): Long {
            if (!a.has(key)) return 0
            val value = a.get(key); require(value is Long || value is Int) { "$key must be an integer" }; return (value as Number).toLong()
        }
        fun flag(key: String, default: Boolean = false) = if (!a.has(key)) default else a.get(key) as? Boolean ?: error("$key must be a boolean")
        val network = if (a.has("network")) Network.parse(a.get("network")) else Network.ANY
        val spec = Spec(path, digest(source), ms("delayMs"), ms("periodMs"), flag("requiresCharging"), flag("requiresBatteryNotLow", true), flag("requiresStorageNotLow"), network, flag("persisted"))
        require(spec.delay in 0..2592000000L && (spec.period == 0L || (spec.delay == 0L && spec.period in 900000L..2592000000L))) { "Use a delay of 0–30 days or a period of 15 minutes–30 days, not both" }
        val requested = if (!a.has("id")) null else a.get("id").let { require(it is Int && it >= 0) { "id must be an integer in 0..2147483647" }; it }
        val previous = synchronized(this) { requested?.let { slot(c, os, it) } }
        confirm(spec.json(requested).put("previousPath", previous?.path).put("script", source))
        synchronized(this) {
            check(host === lifetime && LocalHostService.running) { "Local server stopped" }
            val id = requested ?: generateSequence { kotlin.random.Random.nextInt(1, Int.MAX_VALUE) }.first { it !in approved && os.getPendingJob(it) == null }
            check(slot(c, os, id)?.nonce == previous?.nonce) { "Job changed during confirmation; review it again" }
            check(previous != null || (approved.keys + os.allPendingJobs.filter { retained(c, it) != null }.map { it.id }).size < 16) { "At most 16 approved jobs" }
            val builder = JobInfo.Builder(id, ComponentName(c, WorkspaceJobService::class.java))
                .setPersisted(spec.persisted)
                .setRequiredNetworkType(spec.network.androidType)
                .setRequiresCharging(spec.requiresCharging)
                .setRequiresBatteryNotLow(spec.requiresBatteryNotLow)
                .setRequiresStorageNotLow(spec.requiresStorageNotLow)
                .setExtras(PersistableBundle().apply { putString("nonce", spec.nonce); if (spec.persisted) putString("retained", spec.json(id).toString()) })
            if (spec.period == 0L) builder.setMinimumLatency(spec.delay) else builder.setPeriodic(spec.period)
            // Android replaces by ID. Do not cancel the old job or change its approval before success.
            check(os.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS) { "JobScheduler rejected the job" }
            if (previous != null) interrupt(id)
            if (spec.persisted && !tokens(c).edit().putString(id.toString(), spec.nonce).commit()) {
                os.cancel(id); approved.remove(id); error("Could not save retained job")
            }
            if (!spec.persisted && previous?.persisted == true) tokens(c).edit().remove(id.toString()).apply()
            approved[id] = spec
            return spec.json(id).put("replaced", previous != null)
        }
    }

    // Check both before showing the prompt and under the lock when applying the user's decision.
    private fun slot(c: Context, os: JobScheduler, id: Int): Spec? {
        val job = os.getPendingJob(id); val spec = approved[id] ?: job?.let { retained(c, it) }
        if (spec == null && job == null) return null
        check(spec != null && job?.service == ComponentName(c, WorkspaceJobService::class.java) && job.extras.getString("nonce") == spec.nonce) {
            "Job ID $id is not a pending job approved by this local host"
        }
        return spec
    }

    // Called under the host lock. An Android job ID alone is not proof of ownership.
    private fun cancelApproved(c: Context, os: JobScheduler, id: Int): Boolean {
        val job = os.getPendingJob(id)
        val spec = approved[id] ?: job?.let { retained(c, it) } ?: return false
        val owned = job != null && job.service == ComponentName(c, WorkspaceJobService::class.java) && job.extras.getString("nonce") == spec.nonce
        val running = runs[id]?.spec === spec
        if (owned) os.cancel(id)
        approved.remove(id)
        if (spec.persisted) tokens(c).edit().remove(id.toString()).apply()
        if (running) interrupt(id)
        return owned || running
    }

    @Synchronized fun begin(params: JobParameters): Run? {
        val id = params.jobId; val spec = approved[id] ?: return null
        if (!LocalHostService.running || spec.nonce != params.extras.getString("nonce") || id in runs) return null
        return Run(spec).also { runs[id] = it }
    }
    @Synchronized fun awaitingHost(c: Context, params: JobParameters): Boolean {
        val job = scheduler(c).getPendingJob(params.jobId) ?: return false
        val saved = retained(c, job) ?: return false
        return saved.nonce == params.extras.getString("nonce") && (!LocalHostService.running || approved[params.jobId]?.nonce != saved.nonce)
    }
    fun execute(c: Context, id: Int, run: Run) {
        val source = script(c, run.spec.path)
        check(digest(source) == run.spec.hash) { "Script changed; approval invalidated" }
        // A new process group lets Stop cancel regular shell children as well as the shell. Don't
        // pass the local service's credentials or use a path that could change after the hash check.
        val pb = ProcessBuilder("/system/bin/setsid", "/system/bin/sh", "-c", "echo \u0024\u0024; exec /system/bin/sh -s >/dev/null 2>&1")
            .directory(workspace(c)).redirectError(File("/dev/null"))
        pb.environment().apply { clear(); put("PATH", "/system/bin"); put("HOME", File(c.filesDir,"home").path); put("TMPDIR", c.cacheDir.path) }
        val child = synchronized(this) { check(runs[id] === run); pb.start().also { run.process = it } }
        try {
            val group = child.inputStream.bufferedReader().readLine()?.toIntOrNull() ?: error("Cannot start job shell")
            synchronized(this) {
                check(runs[id] === run && group > 1); run.group = group
            }
            child.outputStream.use { it.write(source.toByteArray()); it.write(10) }
            check(child.waitFor(60, TimeUnit.SECONDS)) { "Script timed out" }
            check(child.exitValue() == 0) { "Shell exit ${child.exitValue()}" }
        } finally { synchronized(this) { kill(run) } }
    }
    private fun kill(run: Run) {
        if (run.group > 1) { runCatching { Os.kill(-run.group, OsConstants.SIGKILL) }; run.group = 0 }
        run.process?.destroyForcibly(); run.process = null
    }
    @Synchronized fun interrupt(id: Int) { runs.remove(id)?.let(::kill) }
    @Synchronized fun stopRun(params: JobParameters) {
        if (runs[params.jobId]?.spec?.nonce == params.extras.getString("nonce")) interrupt(params.jobId)
    }
    @Synchronized fun complete(c: Context, id: Int, run: Run, failed: Boolean): Boolean {
        if (runs[id] !== run) return false
        if (failed && run.spec.period != 0L) cancelGeneration(c, id, run.spec.nonce)
        runs.remove(id); if (failed || run.spec.period == 0L) {
            approved.remove(id); if (run.spec.persisted) tokens(c).edit().remove(id.toString()).apply()
        }; return true
    }
    @Synchronized fun cancelGeneration(c: Context, id: Int, nonce: String?) {
        val os = scheduler(c); val job = os.getPendingJob(id)
        if (job != null && job.service == ComponentName(c, WorkspaceJobService::class.java) && job.extras.getString("nonce") == nonce) os.cancel(id)
    }
    @Synchronized fun shutdown(c: Context, revokeRetained: Boolean) {
        host = Any(); approved.clear(); runs.keys.toList().forEach(::interrupt)
        val os = scheduler(c); os.allPendingJobs.filter { it.service == ComponentName(c, WorkspaceJobService::class.java) && (revokeRetained || !it.isPersisted) }.forEach { os.cancel(it.id) }
        if (revokeRetained) tokens(c).edit().clear().commit()
    }
}

class WorkspaceJobService : JobService() {
    private val main = Handler(Looper.getMainLooper())
    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.jobId; val run = WorkspaceJobs.begin(params)
        if (run == null) {
            if (WorkspaceJobs.awaitingHost(this, params)) { main.post { jobFinished(params, true) }; return true }
            WorkspaceJobs.cancelGeneration(this, id, params.extras.getString("nonce")); return false
        }
        thread(name = "opencode-job-$id") {
            val result = runCatching { WorkspaceJobs.execute(this, id, run) }
            main.post {
                if (WorkspaceJobs.complete(this, id, run, result.isFailure)) {
                    result.exceptionOrNull()?.let { Log.w("OpenCodeJob", "Job $id stopped: ${it.message}") }
                    jobFinished(params, false)
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        WorkspaceJobs.stopRun(params); return false
    }
}
