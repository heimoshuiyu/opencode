package ai.opencode.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

class ShareOutputProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        require(mode == "r") { "Read-only share" }
        return super.openFile(uri, mode)
    }
}

/** Give the sharesheet a single snapshot, never a grant to the live workspace. */
internal object FileShare {
    private const val MAX = 64L * 1024 * 1024
    private const val TTL = 60 * 60 * 1000L
    fun source(context: Context, path: String): File {
        require(path.length <= 512 && path.split('/').all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it && it.none { c -> c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt() } }) { "Expected a relative workspace file path" }
        val root = File(context.filesDir.canonicalFile, "home/projects")
        return File(root, path).also { require(it.canonicalFile == it && it.isFile) { "Expected a regular workspace file, not a symlink" } }
    }

    fun prepare(context: Context, path: String): Pair<File, Intent> {
        val source = source(context, path)
        val cache = File(context.cacheDir.canonicalFile, "share-output")
        check(cache.canonicalFile == cache && (cache.isDirectory || cache.mkdir()))
        val cutoff = System.currentTimeMillis() - TTL
        cache.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        val extension = source.extension.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,16}")) } ?: "bin"
        val out = File.createTempFile("share-", ".$extension", cache)
        try {
            val fd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                val stat = Os.fstat(fd.fileDescriptor)
                check(Os.readlink("/proc/self/fd/${fd.fd}") == source.path && OsConstants.S_ISREG(stat.st_mode)) { "Share source changed" }
                require(stat.st_size <= MAX) { "Share limit is 64 MiB" }
                out.outputStream().use { stream ->
                    val buf = ByteArray(65536); var bytes = 0L
                    while (true) { val n = input.read(buf); if (n < 0) break; bytes += n; require(bytes <= MAX) { "Share limit is 64 MiB" }; stream.write(buf, 0, n) }
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share-output", out, source.name)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri(source.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Handler(Looper.getMainLooper()).postDelayed({ out.delete() }, TTL)
            return out to Intent.createChooser(send, null)
        } catch (e: Exception) { out.delete(); throw e }
    }
}
