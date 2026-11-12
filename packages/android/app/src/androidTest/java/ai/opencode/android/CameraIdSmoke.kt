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
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Two virtual cameras; grant CAMERA through the Android permission dialog. Never supplies a JPEG to the app. */
internal fun cameraIdSmoke(test: Instrumentation, named: Boolean = false): String {
    val c = test.targetContext
    val a = test.startActivitySync(Intent(c, MainActivity::class.java).putExtra("local", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { a.deviceInteraction }; val rpc = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor(); val results = JSONArray()
    val dir = File(c.filesDir, "home/projects"); val staging = File(c.cacheDir, "camera-output")
    fun photos(d: File) = d.listFiles().orEmpty().filter { it.isFile && (d == staging || it.extension == "jpg") }.map { it.name to it.length() }.toMap()
    fun awaitUi(label: String, f: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 65000
        while (!main(f)) { check(SystemClock.elapsedRealtime() < deadline) { "Missing $label" }; SystemClock.sleep(50) }
    }
    try {
        rpc.start(10000, true)
        val cameras = JSONArray(rpc.smokeCall("camera_info")); val ids = (0 until cameras.length()).map { cameras.getJSONObject(it).getString("id") }
        check(ids.size == 2 && ids.distinct().size == 2) { "Requires two virtual cameras: $ids" }
        results.put("inventory=" + (0 until cameras.length()).joinToString { i -> "${ids[i]}:${cameras.getJSONObject(i).getString("facing")}" })
        val unknown = rpc.smokeCall("camera_photo", JSONObject().put("camera", "fixture-missing"), true); check(unknown == "Unknown camera ID; use camera_info"); results.put(unknown)
        data class Shot(val id: String?, val path: String? = null, val outcome: String = "accept")
        val nested = File(dir, "nested"); val suffix = java.util.UUID.randomUUID().toString()
        if (named) {
            check(nested.canonicalFile == File(dir.canonicalFile, "nested") && (nested.isDirectory || nested.mkdir()))
            check(!File(nested, "photo.jpg").exists()) { "Needs a fresh fixture workspace" }
        }
        val cases = if (named) listOf(Shot(ids.last(), "nested/cancel-$suffix.jpg", "cancel"), Shot(ids.last(), "nested/late-$suffix.jpg", "late"),
            Shot(ids.last(), "nested/selected-$suffix.jpeg"), Shot(null, "nested/photo.jpg"), Shot(null, "nested/photo.jpg", "existing"))
            else listOf(Shot(ids.last()), Shot(ids.first()), Shot(ids.last(), outcome = "cancel"))
        for ((id, path, outcome) in cases) {
            val target = path?.let { File(dir, it) }; val before = photos(dir); val beforeStage = photos(staging); val beforeNested = photos(nested)
            val original = if (outcome == "existing") target!!.readBytes() else null
            awaitUi("foreground") { a.hasWindowFocus() }
            val future = worker.submit<String> { rpc.smokeCall("camera_photo", JSONObject().put("camera", id).put("path", path), outcome != "accept", 115000) }
            val notice = if (id != null) a.getString(R.string.photo_selected_notice, id) else a.getString(R.string.photo_notice)
            val expected = if (path != null) a.getString(R.string.photo_destination, notice, path) else notice
            fun findButton(positive: String, consent: Boolean = false, negative: Boolean = false): Button? {
                if (future.isDone) error("MCP returned before $positive: ${future.get()}")
                val root = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() && it.findViewById<Button>(android.R.id.button1)?.text == positive &&
                    (!consent || it.findViewById<TextView>(android.R.id.message)?.text == expected) } ?: return null
                return root.findViewById<Button>(if (negative) android.R.id.button2 else android.R.id.button1)?.takeIf { it.isShown && it.isEnabled }
            }
            awaitUi("camera-specific consent") { findButton(a.getString(android.R.string.ok), consent = true)?.performClick() == true }
            if (outcome == "existing") {
                val wire = future.get(15, TimeUnit.SECONDS); check(wire == "Photo destination already exists" && target!!.readBytes().contentEquals(original))
                results.put("$path: $wire; original JPEG byte-for-byte unchanged"); continue
            }
            if (id == null) { // Drive the real external camera's shutter and accept button on the test AVD.
                val obj = JSONObject(future.get(115, TimeUnit.SECONDS)); check(target!!.canonicalFile == File(obj.getString("path")).canonicalFile && target.length() == obj.getLong("bytes"))
                check(photos(dir) == before && photos(staging) == beforeStage); results.put(obj); continue
            }
            val shutter = a.getString(R.string.photo_shutter); val use = a.getString(R.string.photo_accept)
            awaitUi("ready shutter for $id") { findButton(shutter) != null }
            val dump = ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("dumpsys media.camera")).bufferedReader().use { it.readText() }
            val connect = dump.lineSequence().firstOrNull { it.contains(" : CONNECT device ") && it.contains(c.packageName) }?.trim().orEmpty()
            check(connect.contains("CONNECT device $id ") || connect.contains("CONNECT device $id,")) { "Unexpected camera-service event: $connect" }
            results.put(connect)
            awaitUi("shutter") { findButton(shutter)?.performClick() == true }
            awaitUi("JPEG review") { findButton(use) != null }
            check(photos(dir) == before && photos(staging) == beforeStage && photos(nested) == beforeNested && target?.exists() != true) { "File appeared before review" }
            if (outcome == "late") { check(target!!.createNewFile()); target.writeText("fixture marker") }
            awaitUi("review choice") { findButton(use, negative = outcome == "cancel")?.performClick() == true }
            val wire = future.get(10, TimeUnit.SECONDS)
            if (outcome == "late") {
                check(wire == "Photo destination already exists" && target!!.readText() == "fixture marker"); results.put("late collision: $wire; marker unchanged"); continue
            }
            if (outcome == "cancel") {
                check(wire == "Device request declined" && photos(dir) == before && photos(staging) == beforeStage && photos(nested) == beforeNested && target?.exists() != true); results.put("post-shutter cancel: $wire; unchanged workspace and staging"); continue
            }
            val obj = JSONObject(wire); val file = File(obj.getString("path")); check(file.length() == obj.getLong("bytes") && obj.getString("camera") == id && photos(staging) == beforeStage)
            if (target != null) check(file.canonicalFile == target.canonicalFile && photos(dir) == before)
            else check(file.parentFile.canonicalFile == dir.canonicalFile && photos(dir).keys - before.keys == setOf(file.name)); results.put(obj)
        }
        return results.toString()
    } finally { main { ui.cancel("Fixture closed"); a.finish() }; rpc.stop(); worker.shutdownNow() }
}
