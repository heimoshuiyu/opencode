package ai.opencode.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Bridge the packaged composer's HTML file input to user-selected content, in either server mode. */
internal class AttachmentChooser(private val activity: Activity, private val shellPage: () -> Boolean) {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var menu: AlertDialog? = null
    private var code = 0
    private var next = 61000 // Outside DeviceInteraction's request codes, still within 16 bits.
    private var cameraUri: Uri? = null
    private var capture: File? = null
    private val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun show(result: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        if (callback != null || !shellPage() || !activity.hasWindowFocus() || params.mode !in setOf(FileChooserParams.MODE_OPEN, FileChooserParams.MODE_OPEN_MULTIPLE)) {
            result.onReceiveValue(null); return true
        }
        callback = result; code = next++; if (next >= 65000) next = 61000
        val types = params.acceptTypes.flatMap { it.split(',') }.mapNotNull {
            val v = it.trim().lowercase()
            when {
                v.startsWith('.') -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(v.drop(1))
                '/' in v -> v
                else -> null
            }
        }.distinct()
        val files = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            type = types.singleOrNull() ?: "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
        }
        val photo = types.isEmpty() || types.any { it in setOf("*/*", "image/*", "image/jpeg") }
        try {
            if (photo && params.isCaptureEnabled) requestCamera()
            else if (!photo) launch(files)
            else menu = AlertDialog.Builder(activity).setTitle(R.string.attachment_source)
                .setItems(arrayOf(activity.getString(R.string.attachment_files), activity.getString(R.string.attachment_camera))) { _, which ->
                    if (which == 0) launch(files) else requestCamera()
                }.setNegativeButton(android.R.string.cancel) { _, _ -> cancel() }.setOnCancelListener { cancel() }.show()
        } catch (_: Exception) { fail(R.string.attachment_unavailable) }
        return true
    }

    private fun launch(intent: Intent) {
        try { activity.startActivityForResult(intent, code) } catch (_: Exception) { fail(R.string.attachment_unavailable) }
    }
    private fun requestCamera() {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera()
        else activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), code)
    }
    fun permissionResult(request: Int): Boolean {
        if (request != code || callback == null) return false
        if (!shellPage()) cancel()
        else if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera()
        else fail(R.string.attachment_camera_denied)
        return true
    }
    private fun camera() {
        try {
            val dir = File(activity.cacheDir, "camera-output"); check(dir.isDirectory || dir.mkdir())
            val file = File.createTempFile("attachment-", ".jpg", dir); capture = file
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.camera-output", file); cameraUri = uri
            launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri).apply {
                clipData = ClipData.newRawUri("Camera output", uri); addFlags(grants)
            })
        } catch (_: Exception) { fail(R.string.attachment_unavailable) }
    }

    fun activityResult(request: Int, status: Int, data: Intent?): Boolean {
        if (request != code || callback == null) return false
        if (status != Activity.RESULT_OK || !shellPage()) { cancel(); return true }
        val photo = cameraUri
        if (photo != null) {
            if ((capture?.length() ?: 0L) <= 0L) { fail(R.string.attachment_empty); return true }
            capture = null // Keep the successful cache file readable while WebView consumes the File.
            finish(arrayOf(photo)); return true
        }
        val clip = data?.clipData
        val uris = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.distinct() else listOfNotNull(data?.data)
        if (uris.isEmpty() || !uris.all { uri -> uri.scheme == "content" && !uri.authority.orEmpty().substringAfterLast('@').startsWith("${activity.packageName}.") &&
                activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED }) {
            fail(R.string.attachment_unavailable); return true
        }
        finish(uris.toTypedArray()); return true
    }

    fun cancel() = finish(null)
    private fun fail(message: Int) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); cancel() }
    private fun finish(value: Array<Uri>?) {
        val done = callback ?: return; callback = null; code = 0
        menu?.dismiss(); menu = null
        cameraUri?.let { activity.revokeUriPermission(it, grants) }; cameraUri = null
        capture?.delete(); capture = null
        done.onReceiveValue(value)
    }
}
