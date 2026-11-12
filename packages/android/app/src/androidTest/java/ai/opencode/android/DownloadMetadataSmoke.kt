package ai.opencode.android

import android.app.DownloadManager
import android.app.Instrumentation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Real HTTPS transfer on an isolated AVD. Query the system provider independently of the MCP result. */
internal fun downloadMetadataSmoke(test: Instrumentation, named: Boolean = false): String {
    val c = test.targetContext
    val a = test.startActivitySync(Intent(c, ServerPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { DeviceInteraction(a) }; val rpc = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val dm = c.getSystemService(DownloadManager::class.java); val worker = Executors.newSingleThreadExecutor()
    val bytes = if (named) ByteArray(192) { when (it % 3) { 0 -> 0; 1 -> -128; else -> 65 } } else "OpenCode Android DownloadManager fixture v1\n".repeat(4).toByteArray(Charsets.UTF_8)
    val url = "https://httpbingo.org/base64/" + Base64.encodeToString(bytes, Base64.NO_WRAP)
    val title = "OpenCode 合成文件 01"; val description = "Metadata check: 标题、描述 & SHA-256"
    val args = JSONObject().put("url", url).put("title", title).put("description", description)
    val relative = "nested/sample.bin"; val workspace = File(c.filesDir.canonicalFile, "home/projects"); val target = File(workspace, relative)
    if (named) args.put("path", relative)
    fun fixtureIds(source: String = url) = dm.query(DownloadManager.Query()).use { rows -> buildSet {
        while (rows.moveToNext()) if (rows.getString(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) == source)
            add(rows.getLong(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)))
    } }
    fun awaitUi(f: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25000
        while (!main(f)) { check(SystemClock.elapsedRealtime() < end) { "Missing download confirmation or foreground focus" }; SystemClock.sleep(50) }
    }
    fun submit(approve: Boolean, input: JSONObject = args, expectError: Boolean = !approve): String {
        awaitUi { a.hasWindowFocus() }
        val request = worker.submit<String> { rpc.smokeCall("download", input, expectError, 125000) }
        awaitUi {
            if (request.isDone) error("MCP finished before confirmation: ${request.get()}")
            val root = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() && it.findViewById<TextView>(android.R.id.message)?.text?.let { text ->
                text.contains(input.getString("url")) && text.contains(title) && text.contains(description) &&
                    (!named || text.contains(a.getString(R.string.download_destination, relative))) } == true } ?: return@awaitUi false
            root.findViewById<Button>(if (approve) android.R.id.button1 else android.R.id.button2).performClick()
        }; return request.get(if (named) 110 else 10, TimeUnit.SECONDS)
    }
    var id: Long? = null
    try {
        rpc.start(10000, true)
        if (named) {
            val parent = target.parentFile!!; check(parent.canonicalFile == parent && (parent.isDirectory || parent.mkdirs()))
            check(!target.exists()) { "Test requires an absent nested/sample.bin" }
        }
        val before = fixtureIds(); val declined = submit(false); check(declined == "Device request declined" && fixtureIds() == before)
        if (named) check(!target.exists()) { "Decline created a workspace file" }
        val submitted = JSONObject(submit(true)); val key = submitted.getLong("downloadId"); id = key
        val deadline = SystemClock.elapsedRealtime() + 90000
        val row = JSONObject()
        while (true) {
            dm.query(DownloadManager.Query().setFilterById(key)).use { rows ->
                check(rows.moveToFirst()); fun i(column: String) = rows.getColumnIndexOrThrow(column)
                check(rows.getLong(i(DownloadManager.COLUMN_ID)) == key && rows.getString(i(DownloadManager.COLUMN_URI)) == url)
                row.put("title", rows.getString(i(DownloadManager.COLUMN_TITLE))).put("description", rows.getString(i(DownloadManager.COLUMN_DESCRIPTION)))
                    .put("status", rows.getInt(i(DownloadManager.COLUMN_STATUS))).put("reason", rows.getInt(i(DownloadManager.COLUMN_REASON)))
                    .put("downloadedBytes", rows.getLong(i(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)))
            }
            if (row.getInt("status") == DownloadManager.STATUS_SUCCESSFUL) break
            check(row.getInt("status") != DownloadManager.STATUS_FAILED && SystemClock.elapsedRealtime() < deadline) { "System download $key: $row" }; SystemClock.sleep(250)
        }
        check(row.getString("title") == title && row.getString("description") == description && row.getLong("downloadedBytes") == bytes.size.toLong()) { row.toString() }
        val saved = ParcelFileDescriptor.AutoCloseInputStream(dm.openDownloadedFile(key)).use { it.readNBytes(bytes.size + 1) }
        val digest = MessageDigest.getInstance("SHA-256").digest(saved).joinToString("") { "%02x".format(it) }
        check(saved.contentEquals(bytes) && digest == (if (named) "e0e1f04ce4c5cf804f6815d3d0a0d0406380ed8e26ca22a98963c7b1fbf6c1fe" else "e702357ac3e9122a0ed066946453f9ca47143ed96cc514163c18456aed5fe7fe"))
        val report = JSONObject().put("declined", declined).put("MCP", submitted).put("systemRow", row).put("sha256", digest)
        if (named) {
            check(File(submitted.getString("path")).canonicalFile == target.canonicalFile && target.readBytes().contentEquals(bytes))
            check(submitted.getLong("bytes") == bytes.size.toLong() && submitted.getString("sha256") == digest)
            val other = "https://httpbingo.org/base64/T1RIRVI=" // different body (OTHER), same target
            val previous = fixtureIds(other); val collision = submit(true, JSONObject(args.toString()).put("url", other), true)
            check(collision == "Download destination already exists" && fixtureIds(other) == previous && target.readBytes().contentEquals(bytes))
            report.put("cancelCheck", "no target, no new queue entry").put("collision", "$collision; original bytes unchanged, no new queue entry")
        } else check(submitted.length() == 1)
        return report.toString()
    } finally { id?.let { dm.remove(it) }; main { ui.cancel("Fixture closed"); a.finish() }; rpc.stop(); worker.shutdownNow() }
}
