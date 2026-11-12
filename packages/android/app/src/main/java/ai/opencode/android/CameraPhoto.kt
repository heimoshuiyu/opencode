package ai.opencode.android

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

class CameraOutputProvider : FileProvider()

/** Give the camera access only to a staging file. Never use the thumbnail in the result intent. */
internal class CameraPhoto(private val activity: Activity) {
    private var staging: File? = null
    private var output: Uri? = null
    private var destination: WorkspaceOutput? = null
    private val uriGrantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun prepare(args: JSONObject) {
        destination = if (args.has("path")) WorkspaceOutput(activity.filesDir, args.getString("path"), "Photo").also { it.checkNew() } else null
    }

    fun launch(code: Int) {
        val dir = File(activity.cacheDir.canonicalFile, "camera-output")
        check(dir.canonicalFile == dir && (dir.isDirectory || dir.mkdir())) { "Camera output unavailable" }
        val file = File.createTempFile("camera-", ".jpg", dir); staging = file
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.camera-output", file); output = uri
        activity.startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("Camera output", uri)
            addFlags(uriGrantFlags)
        }, code)
    }

    fun save(): JSONObject {
        val file = checkNotNull(staging)
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, dimensions)
        check(dimensions.outMimeType == "image/jpeg" && dimensions.outWidth > 0 && dimensions.outHeight > 0) { "Camera did not write a JPEG" }
        destination?.let { target ->
            val count = file.inputStream().use { target.writeNew(it) }
            return JSONObject().put("path", target.file.path).put("width", dimensions.outWidth).put("height", dimensions.outHeight).put("bytes", count)
        }
        val workspace = File(activity.filesDir.canonicalFile, "home/projects")
        check(workspace == workspace.canonicalFile && workspace.isDirectory) { "Workspace unavailable" }
        val dest = File(workspace, file.name)
        Files.move(file.toPath(), dest.toPath())
        return JSONObject().put("path", dest.path).put("width", dimensions.outWidth).put("height", dimensions.outHeight).put("bytes", dest.length())
    }

    fun clear() {
        try { output?.let { activity.revokeUriPermission(it, uriGrantFlags) } }
        finally { output = null; staging?.delete(); staging = null; destination = null }
    }

    fun saveSelected(jpeg: ByteArray, id: String): JSONObject {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        check(bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid camera JPEG" }
        destination?.let { target ->
            val count = jpeg.inputStream().use { target.writeNew(it) }
            return JSONObject().put("camera", id).put("path", target.file.path).put("width", bounds.outWidth).put("height", bounds.outHeight).put("bytes", count)
        }
        val workspace = File(activity.filesDir.canonicalFile, "home/projects")
        check(workspace == workspace.canonicalFile && workspace.isDirectory) { "Workspace unavailable" }
        val file = File.createTempFile("camera-", ".jpg", workspace)
        try { file.writeBytes(jpeg) } catch (e: Exception) { file.delete(); throw e }
        return JSONObject().put("camera", id).put("path", file.path).put("width", bounds.outWidth).put("height", bounds.outHeight).put("bytes", file.length())
    }
}
