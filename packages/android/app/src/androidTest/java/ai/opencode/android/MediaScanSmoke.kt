package ai.opencode.android

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Prerequisite: grant Documents/OpenCodeTestDir/Project read/write using the emulator's picker.
 * Only the new random fixture is written and removed. No shell identity or extra permissions. */
internal fun mediaScanSmoke(context: Context): String {
    val resolver = context.contentResolver
    val tree = resolver.persistedUriPermissions.single { it.isReadPermission && it.isWritePermission &&
        it.uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(it.uri) &&
        DocumentsContract.getTreeDocumentId(it.uri) == "primary:Documents/OpenCodeTestDir/Project" }.uri
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val dir = "scan-${UUID.randomUUID()}"
    val fixture = checkNotNull(DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, dir))
    val server = DeviceMcp(context, { error("Unexpected location request") }, { _, _ -> error("Unexpected UI") })
    try {
        val nested = checkNotNull(DocumentsContract.createDocument(resolver, fixture, DocumentsContract.Document.MIME_TYPE_DIR, "sub"))
        val top = "$dir/top.png"; val deep = "$dir/sub/deep.jpg"
        val png = checkNotNull(DocumentsContract.createDocument(resolver, fixture, "image/png", "top.png"))
        val jpg = checkNotNull(DocumentsContract.createDocument(resolver, nested, "image/jpeg", "deep.jpg"))
        val image = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        resolver.openOutputStream(png)!!.use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        resolver.openOutputStream(jpg)!!.use { check(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }; image.recycle()
        server.start(10000, true)
        var id = 0
        fun call(tool: String, args: JSONObject, fail: Boolean = false): String {
            val body = JSONObject().put("jsonrpc", "2.0").put("id", ++id).put("method", "tools/call")
                .put("params", JSONObject().put("name", tool).put("arguments", args)).toString().toByteArray()
            val c = URL("http://127.0.0.1:${server.listeningPort}/mcp").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 25000
                c.setRequestProperty("Authorization", "Bearer ${server.token}"); c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) }
                val response = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); check(response.getInt("id") == id)
                val result = response.getJSONObject("result"); val text = result.getJSONArray("content").getJSONObject(0).getString("text")
                check(result.getBoolean("isError") == fail) { text }; return text
            } finally { c.disconnect() }
        }
        val roots = JSONArray(call("saf_roots", JSONObject()))
        val root = (0 until roots.length()).map { roots.getJSONObject(it) }.single { it.getString("name") == "Project" && it.getBoolean("writable") }.getString("root")
        fun scan(args: JSONObject) = JSONObject(call("media_scan", args.put("root", root)))
        val fields = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE)
        fun verify(row: JSONObject): String {
            val path = row.getString("path"); check(path == top || path == deep)
            val uri = Uri.parse(row.getString("uri")); val mime = if (path == top) "image/png" else "image/jpeg"
            val name = path.substringAfterLast('/')
            check(MediaStore.getMediaUri(context, if (path == top) png else jpg) == uri && uri.authority == MediaStore.AUTHORITY)
            check(row.getString("name") == name && row.getString("mime") == mime)
            resolver.query(uri, fields, null, null, null)!!.use { c ->
                check(c.moveToFirst() && c.getLong(0) == ContentUris.parseId(uri) && c.getString(1) == name && c.getString(2) == mime)
            }
            return "$name=$uri ($mime)"
        }
        fun checkSet(args: JSONObject, vararg paths: String): String {
            val files = scan(args).getJSONArray("files"); val rows = (0 until files.length()).map { files.getJSONObject(it) }
            check(rows.map { it.getString("path") }.sorted() == paths.sorted()) { "Unexpected scan result: $files" }
            return rows.joinToString { verify(it) }
        }
        val shallow = checkSet(JSONObject().put("path", dir).put("recursive", false), top)
        val recursive = checkSet(JSONObject().put("path", dir).put("recursive", true), top, deep)
        val batch = checkSet(JSONObject().put("paths", JSONArray(listOf(deep, top, deep))), deep, top)
        verify(scan(JSONObject().put("path", top))) // legacy flat response, no files wrapper
        val denied = call("media_scan", JSONObject().put("root", "not-granted").put("paths", JSONArray(listOf(top))), true)
        check(denied.startsWith("No such grant"))
        val traversal = call("media_scan", JSONObject().put("root", root).put("paths", JSONArray(listOf(top, "../outside.png"))).put("recursive", true), true)
        check(traversal.contains("relative path"))
        return "nonrecursive=[$shallow]; recursive=[$recursive]; batch=[$batch]; MediaStore rows matched; unknown root and mixed traversal batch denied"
    } finally { server.stop(); DocumentsContract.deleteDocument(resolver, fixture) }
}
