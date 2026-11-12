package ai.opencode.android

import android.content.Context
import android.content.ContentUris
import android.content.UriPermission
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.media.MediaScannerConnection
import android.os.storage.StorageManager
import android.os.ParcelFileDescriptor
import android.system.Os
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.DigestInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** SAF identifiers are opaque provider data, not filesystem paths. Resolve each name by listing
 * the children of an actual granted document. Never accept a caller-supplied content URI. */
internal class SharedDocuments(private val context: Context) {
    private val resolver = context.contentResolver
    init {
        // 0.4 offered individual documents. Remove those old permissions, not the documents.
        resolver.persistedUriPermissions.filter { !DocumentsContract.isTreeUri(it.uri) && DocumentsContract.isDocumentUri(context, it.uri) }.forEach {
            val flags = (if (it.isReadPermission) android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (it.isWritePermission) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            try { resolver.releasePersistableUriPermission(it.uri, flags) } catch (_: SecurityException) { }
        }
    }
    fun folders() = resolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    private fun key(uri: Uri) = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    private fun document(uri: Uri) = if (DocumentsContract.isTreeUri(uri) && uri.pathSegments.size == 2)
        DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri

    fun label(uri: Uri): String = runCatching {
        resolver.query(document(uri), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    fun roots() = JSONArray().apply {
        folders().forEach {
            put(JSONObject().put("root", key(it.uri)).put("name", label(it.uri))
                .put("directory", DocumentsContract.isTreeUri(it.uri)).put("writable", it.isWritePermission))
        }
    }

    private fun grant(args: JSONObject): UriPermission = folders().firstOrNull {
        key(it.uri) == args.getString("root")
    } ?: error("No such grant. Choose a folder under Folder access on the phone.")

    private fun segments(args: JSONObject) = segments(args.optString("path"))
    private fun segments(path: String): List<String> {
        if (path.isEmpty()) return emptyList()
        return path.split('/').also { parts -> require(parts.size <= 32 && parts.all {
            it.length in 1..255 && it != "." && it != ".." && '\\' !in it && it.none(Char::isISOControl)
        }) { "Use a relative path without empty, dot or parent components" } }
    }

    private fun children(parent: Uri) = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(parent,
        DocumentsContract.getDocumentId(parent)), columns, null, null, null) ?: error("Cannot list this folder")

    private fun child(parent: Uri, name: String): Uri? = children(parent).use { cursor ->
        var found: Uri? = null
        while (cursor.moveToNext()) if (cursor.getString(1) == name) {
            check(found == null) { "Ambiguous document name: $name" }
            found = DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
        }
        found
    }

    private fun resolve(root: Uri, parts: List<String>): Uri {
        return parts.fold(document(root)) { parent, name -> child(parent, name) ?: error("Document not found: $name") }
    }

    fun list(args: JSONObject): JSONObject {
        val folder = resolve(grant(args).uri, segments(args))
        require(DocumentsContract.isTreeUri(folder) && resolver.getType(folder) == Document.MIME_TYPE_DIR) { "Not a folder" }
        val offset = args.optInt("offset", 0)
        require(offset >= 0) { "Negative offset" }
        val entries = JSONArray()
        return children(folder).use { c ->
            c.moveToPosition(offset - 1)
            while (entries.length() < 100 && c.moveToNext()) entries.put(JSONObject()
                .put("name", c.getString(1)).put("directory", c.getString(2) == Document.MIME_TYPE_DIR))
            JSONObject().put("entries", entries).put("nextOffset", if (c.moveToNext()) offset + entries.length() else JSONObject.NULL)
        }
    }

    fun read(args: JSONObject): JSONObject {
        val uri = resolve(grant(args).uri, segments(args).also { require(it.isNotEmpty()) { "Specify a file path within the granted folder" } })
        val data = ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(data.size() + n <= 49152) { "Only UTF-8 documents up to 48 KiB are supported" }
                data.write(buffer, 0, n)
            }
        } ?: error("Document cannot be opened")
        return JSONObject().put("text", Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data.toByteArray())).toString())
    }

    private fun workspace() = File(context.filesDir.canonicalFile, "home/projects").also {
        check(it.canonicalFile == it && it.isDirectory) { "Local workspace unavailable" }
    }

    private fun copyBytes(input: InputStream, output: OutputStream): JSONObject {
        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = DigestInputStream(input, hash).copyTo(output, 64 * 1024)
        return JSONObject().put("bytes", bytes).put("sha256", hash.digest().joinToString("") { "%02x".format(it) })
    }

    fun readBinary(args: JSONObject): JSONObject {
        val granted = grant(args)
        val parts = segments(args).also { require(it.isNotEmpty()) { "Specify a file within the grant" } }
        val uri = resolve(granted.uri, parts)
        require(resolver.getType(uri) != Document.MIME_TYPE_DIR) { "Expected a file" }
        // Keep binary payloads out of JSON and the session transcript; return a fresh workspace file.
        return resolver.openInputStream(uri)?.use { input ->
            val file = Files.createTempFile(workspace().toPath(), "saf-read-", ".bin")
            try {
                Files.newOutputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { copyBytes(input, it) }
                    .put("workspacePath", file.fileName.toString())
            } catch (e: Exception) { Files.deleteIfExists(file); throw e }
        } ?: error("Document cannot be opened")
    }

    @Synchronized fun writeBinary(args: JSONObject): JSONObject {
        val granted = grant(args)
        require(granted.isWritePermission) { "Read-only grant" }
        val parts = segments(args).also { require(it.isNotEmpty()) { "Specify a file within the grant" } }
        val overwrite = args.opt("overwrite") ?: false
        require(overwrite is Boolean) { "overwrite must be a boolean" }
        val base = workspace()
        val source = File(base, segments(args.getString("workspacePath")).joinToString("/"))
        require(source.canonicalFile == source && source.isFile) { "Expected a regular workspace file, not a symlink" }
        val parent = resolve(granted.uri, parts.dropLast(1)); val existing = child(parent, parts.last())
        require(existing == null || (overwrite && resolver.getType(existing) != Document.MIME_TYPE_DIR)) {
            "Document exists: specify overwrite=true to replace a file"
        }
        val fd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            check(Os.readlink("/proc/self/fd/${fd.fd}") == source.path && android.system.OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode)) { "Workspace source changed" }
            val target = existing ?: DocumentsContract.createDocument(resolver, parent, "application/octet-stream", parts.last()) ?: error("Creation failed")
            try {
                val result = resolver.openOutputStream(target, "wt")?.use { copyBytes(input, it) } ?: error("Write failed")
                result.put("name", label(target))
            } catch (e: Exception) {
                if (existing == null) runCatching { DocumentsContract.deleteDocument(resolver, target) }
                throw e
            }
        }
    }

    @Synchronized fun write(args: JSONObject): JSONObject {
        val granted = grant(args)
        require(granted.isWritePermission) { "Read-only grant" }
        val bytes = args.getString("text").toByteArray()
        require(bytes.size <= 49152) { "Document exceeds 48 KiB" }
        val parts = segments(args)
        require(parts.isNotEmpty()) { "Specify a file path within the granted folder" }
        val parent = resolve(granted.uri, parts.dropLast(1))
        val existing = child(parent, parts.last())
        require(existing == null || (args.optBoolean("overwrite") && resolver.getType(existing) != Document.MIME_TYPE_DIR)) {
            "Document exists: specify overwrite=true to replace a file"
        }
        val target = existing ?: DocumentsContract.createDocument(resolver, parent, "text/plain", parts.last()) ?: error("Creation failed")
        resolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: error("Write failed")
        return JSONObject().put("name", label(target)).put("bytes", bytes.size)
    }

    fun stat(args: JSONObject): JSONObject {
        val g = grant(args); val target = resolve(g.uri, segments(args))
        val fields = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        return resolver.query(target, fields, null, null, null)?.use { c ->
            check(c.moveToFirst()) { "Document not found" }
            JSONObject().put("name", c.getString(0)).put("mime", c.getString(1)).put("writableGrant", g.isWritePermission)
                .put("size", if (c.isNull(2)) JSONObject.NULL else c.getLong(2)).put("modified", if(c.isNull(3)) JSONObject.NULL else c.getLong(3))
        } ?: error("Cannot stat document")
    }

    fun openMedia(args: JSONObject): android.content.res.AssetFileDescriptor {
        val root = grant(args).uri
        val parts = segments(args); require(parts.isNotEmpty()) { "Specify a file in the granted folder" }
        return resolver.openAssetFileDescriptor(resolve(root, parts), "r") ?: error("Document cannot be opened for playback")
    }

    /** MediaScannerConnection takes filesystem paths, not SAF URIs. Only map a document from
     * Android's local ExternalStorageProvider, after resolving every component inside its grant. */
    fun scanMedia(args: JSONObject): JSONObject {
        val root = grant(args).uri
        require(root.authority == "com.android.externalstorage.documents") { "Media scanning requires a local shared-storage folder" }
        require(args.has("path") xor args.has("paths")) { "Specify either path or paths" }
        val batch = args.has("paths")
        val inputs = if (batch) {
            val paths = args.getJSONArray("paths"); require(paths.length() in 1..128) { "Specify 1–128 relative paths" }
            (0 until paths.length()).map { paths.get(it) }
        } else listOf(args.get("path"))
        val recursive = if (args.has("recursive")) args.get("recursive") as? Boolean ?: error("Expected boolean recursive") else false
        val requested = inputs.map { require(it is String) { "Expected a relative path string" }; segments(it) }
        val id = DocumentsContract.getTreeDocumentId(root)
        val volumeId = id.substringBefore(':'); val relative = id.substringAfter(':')
        require(id.contains(':') && !relative.startsWith('/') && relative.split('/').none { it == "." || it == ".." }) { "Unsupported storage root" }
        val volume = context.getSystemService(StorageManager::class.java).storageVolumes.firstOrNull {
            (it.isPrimary && volumeId == "primary") || it.uuid.equals(volumeId, ignoreCase = true)
        }?.directory ?: error("Storage volume is not mounted")
        val base = File(volume.canonicalFile, relative)
        data class Item(val target: Uri, val file: File, val path: String, val mime: String)
        val items = linkedMapOf<String, Item>()
        val visited = mutableSetOf<Uri>()
        var entries = 0
        var directoryInput = false
        fun visit(target: Uri, parts: List<String>, mime: String, explicit: Boolean) {
            require(++entries <= 4096 && parts.size <= 32) { "Folder scan too large; select a smaller subfolder" }
            val path = parts.joinToString("/")
            val file = if (path.isEmpty()) base else File(base, path)
            val resolvedId = volumeId + ":" + listOf(relative, path).filter { it.isNotEmpty() }.joinToString("/")
            require(DocumentsContract.getDocumentId(target) == resolvedId && file.canonicalFile == file) { "Document is not inside the selected folder" }
            if (mime == Document.MIME_TYPE_DIR) {
                if (explicit) directoryInput = true
                if ((!explicit && !recursive) || !visited.add(target)) return
                children(target).use { c -> while (c.moveToNext()) {
                    val name = c.getString(1)
                    require(segments(name).size == 1) { "Invalid child name" }
                    visit(DocumentsContract.buildDocumentUriUsingTree(root, c.getString(0)), parts + name, c.getString(2).orEmpty(), false)
                } }
            } else if (mime.substringBefore('/') in setOf("audio", "video", "image") || mime == "application/ogg") {
                items[path] = Item(target, file, path, mime)
                require(items.size <= 256) { "More than 256 media files; split this scan" }
            } else require(!explicit) { "Not an audio, video or image file: $path" }
        }
        // Resolve and validate the full set first: a bad later input must not start a partial scan.
        requested.forEach { parts ->
            val target = resolve(root, parts)
            visit(target, parts, resolver.getType(target) ?: error("Unknown document type"), true)
        }
        val results = JSONArray()
        if (items.isEmpty()) return JSONObject().put("files", results)
        val callbacks = items.values.associate { it.file.path to CompletableFuture<Uri?>() }
        MediaScannerConnection.scanFile(context, callbacks.keys.toTypedArray(), items.values.map { it.mime }.toTypedArray()) { path, uri -> callbacks[path]?.complete(uri) }
        CompletableFuture.allOf(*callbacks.values.toTypedArray()).get(60, TimeUnit.SECONDS)
        for (item in items.values) {
            val scanned = callbacks.getValue(item.file.path).get() ?: error("MediaStore did not index ${item.path}")
            results.put(mediaRow(item.target, scanned).put("path", item.path))
        }
        return if (!batch && !directoryInput) results.getJSONObject(0) else JSONObject().put("files", results)
    }

    private fun mediaRow(target: Uri, scanned: Uri): JSONObject {
        // Map the original SAF permission to its equivalent MediaStore item. No READ_MEDIA_* permission.
        val media = MediaStore.getMediaUri(context, target) ?: error("No MediaStore row for the scanned document")
        check(scanned.authority == MediaStore.AUTHORITY && ContentUris.parseId(media) == ContentUris.parseId(scanned)) { "Scan returned a different item" }
        val fields = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE)
        return resolver.query(media, fields, null, null, null)?.use { row ->
            check(row.moveToFirst()) { "MediaStore row is no longer available" }
            JSONObject().put("uri", media.toString()).put("name", row.getString(0)).put("mime", row.getString(1))
        } ?: error("Cannot read scanned MediaStore row")
    }

    @Synchronized fun mkdir(args: JSONObject): JSONObject {
        val g = grant(args); require(g.isWritePermission) { "Read-only grant" }
        val parts = segments(args); require(parts.isNotEmpty()) { "Cannot replace a grant root" }
        val parent = resolve(g.uri, parts.dropLast(1)); require(child(parent, parts.last()) == null) { "Already exists" }
        val created = DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, parts.last()) ?: error("Creation failed")
        return JSONObject().put("name", label(created))
    }

    @Synchronized fun remove(args: JSONObject): JSONObject {
        val g = grant(args); require(g.isWritePermission) { "Read-only grant" }
        val parts = segments(args); require(parts.isNotEmpty()) { "Cannot delete a grant root" }
        val uri = resolve(g.uri, parts)
        if (resolver.getType(uri) == Document.MIME_TYPE_DIR) children(uri).use { require(!it.moveToFirst()) { "Folder is not empty" } }
        check(DocumentsContract.deleteDocument(resolver, uri)) { "Deletion failed" }; return JSONObject().put("deleted", true)
    }
}
