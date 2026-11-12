package ai.opencode.android

import android.app.DownloadManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONObject
import java.security.DigestInputStream
import java.security.MessageDigest

/** Runs on the MCP request thread, not the UI thread. DownloadManager cannot write directly to our private workspace. */
internal fun saveWorkspaceDownload(context: Context, id: Long, path: String): JSONObject {
    val manager = context.getSystemService(DownloadManager::class.java)
    val deadline = SystemClock.elapsedRealtime() + 90000
    val query = DownloadManager.Query().setFilterById(id)
    try {
        while (true) {
            val complete = manager.query(query).use { rows ->
                check(rows.moveToFirst()) { "Download was removed" }
                val status = rows.getInt(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                check(status != DownloadManager.STATUS_FAILED) { "System download failed (reason ${rows.getInt(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})" }
                status == DownloadManager.STATUS_SUCCESSFUL
            }
            if (complete) break
            check(SystemClock.elapsedRealtime() < deadline) { "Download timed out before saving to the workspace" }; Thread.sleep(500)
        }
        val target = WorkspaceOutput(context.filesDir, path, "Download")
        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id)).use { target.writeNew(DigestInputStream(it, hash)) }
        return JSONObject().put("downloadId", id).put("path", target.file.path).put("bytes", bytes).put("sha256", hash.digest().joinToString("") { "%02x".format(it) })
    } catch (e: Exception) {
        runCatching { manager.remove(id) }; throw e
    }
}
