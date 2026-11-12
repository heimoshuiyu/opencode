package ai.opencode.android

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/** Re-open existing parents without following links. The early check is not a reservation:
 * CREATE_NEW at publication also protects names created while the recording or camera UI is open. */
internal class WorkspaceOutput(filesDir: File, relative: String, private val kind: String) {
    companion object {
        private val component = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M} ._-]*")
        fun valid(path: Any?) = path is String && path.toByteArray().size <= 160 && path.split('/').all { it.matches(component) }
    }
    init { require(valid(relative)) { "Expected a relative workspace path" } }
    private val anchor = filesDir.canonicalFile.toPath()
    val file = File(anchor.toFile(), "home/projects/$relative")

    fun checkNew() = withParent { dir, name ->
        try {
            dir.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
            error("$kind destination already exists")
        } catch (_: NoSuchFileException) { Unit }
    }

    fun writeNew(source: InputStream): Long = withParent { dir, name ->
        val mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val channel = try { dir.newByteChannel(name, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS), mode) }
            catch (_: FileAlreadyExistsException) { error("$kind destination already exists") }
        Channels.newOutputStream(channel).use { source.copyTo(it) }
    }

    private fun <T> withParent(action: (SecureDirectoryStream<Path>, Path) -> T): T {
        val parts = anchor.relativize(file.toPath())
        fun walk(dir: SecureDirectoryStream<Path>, i: Int): T {
            val name = parts.getName(i)
            if (i == parts.nameCount - 1) return action(dir, name)
            val child = try { dir.newDirectoryStream(name, NOFOLLOW_LINKS) } catch (_: IOException) {
                error("$kind directory must already exist in the workspace and not be a link")
            }
            return child.use { walk(it, i + 1) }
        }
        return Files.newDirectoryStream(anchor).use { dir ->
            check(dir is SecureDirectoryStream<Path>) { "Secure workspace directory access unavailable" }; walk(dir, 0)
        }
    }
}
