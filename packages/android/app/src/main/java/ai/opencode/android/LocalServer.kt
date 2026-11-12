package ai.opencode.android

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun secret(): String = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) },
    Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

/** Owns one Node child. Closing stdin also shuts it down if Android kills the Java parent. */
internal class LocalServer(private val context: Context, private val mcp: DeviceMcp) : AutoCloseable {
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var closed = false
    private var process: Process? = null

    fun run(ready: (JSONObject) -> Unit) {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val project = File(home, "projects").apply { mkdirs() }
        val bin = File(home, "bin").apply { mkdirs() }
        val native = File(context.applicationInfo.nativeLibraryDir)
        // Reinstalling the APK changes nativeLibraryDir. Refresh links, never copy executable code into HOME.
        for (name in listOf("rg", "ffmpeg")) {
            val link = File(bin, name).toPath()
            java.nio.file.Files.deleteIfExists(link)
            java.nio.file.Files.createSymbolicLink(link, File(native, "liboc_$name.so").toPath())
        }
        val receiver = File(bin, "oc-usb-run").toPath()
        java.nio.file.Files.deleteIfExists(receiver)
        java.nio.file.Files.createSymbolicLink(receiver, File(native, "liboc_usb_run.so").toPath())
        fun unpack(asset: String, target: File) {
            val children = context.assets.list(asset).orEmpty()
            if (children.isEmpty()) {
                context.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
                return
            }
            target.mkdirs()
            children.forEach { unpack("$asset/$it", File(target, it)) }
        }
        val runtime = File(context.codeCacheDir, "runtime")
        unpack("runtime", runtime)
        val script = File(runtime, "opencode.mjs")
        val prefs = context.getSharedPreferences("local", Context.MODE_PRIVATE)
        val password = secret()
        synchronized(this) {
            check(!closed) { "Stopped" }
            mcp.start(10000, true)
        }
        val config = JSONObject().put("mcp", JSONObject().put("servers", JSONObject().put("android", JSONObject()
            .put("type", "remote").put("url", "http://127.0.0.1:${mcp.listeningPort}/mcp").put("oauth", false)
            .put("codemode", false).put("headers", JSONObject().put("Authorization", "Bearer ${mcp.token}")))))
        val diagnostics = File(context.cacheDir, "local-server.log")
        for (port in listOf(prefs.getInt("port", 0), 0).distinct()) {
            val pb = ProcessBuilder(File(native, "liboc_node.so").path, "--use-bundled-ca", script.path,
                "serve", "--stdio", "--hostname", "127.0.0.1", "--port", port.toString())
                .directory(project).redirectError(diagnostics)
            pb.environment().apply {
                put("HOME", home.path); put("TMPDIR", context.cacheDir.path); put("SHELL", "/system/bin/sh")
                put("PATH", "${bin.path}:/system/bin"); put("LD_LIBRARY_PATH", native.path)
                put("OPENCODE_PASSWORD", password); put("OPENCODE_CONFIG_CONTENT", config.toString())
                put("OPENCODE_FILEWATCHER_DISABLE", "1"); put("OPENCODE_DISABLE_FFF", "1")
            }
            val child = synchronized(this) {
                check(!closed) { "Stopped" }
                pb.start().also { process = it }
            }
            val deadline = timer.schedule({ child.destroyForcibly() }, 60, TimeUnit.SECONDS)
            var listening = false
            child.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (!listening && line.startsWith("{\"url\":\"http://127.0.0.1:")) {
                    val url = JSONObject(line).getString("url")
                    val bound = java.net.URI(url).port
                    require(bound in 1..65535 && url == "http://127.0.0.1:$bound")
                    deadline.cancel(false)
                    prefs.edit().putInt("port", bound).apply()
                    listening = true
                    ready(JSONObject().put("url", url).put("password", password))
                }
            } }
            val exit = child.waitFor()
            deadline.cancel(false)
            if (closed) return
            if (!listening && port != 0 && diagnostics.readText().contains("EADDRINUSE")) continue
            error("Node exit $exit (cache/local-server.log)")
        }
    }

    @Synchronized override fun close() {
        closed = true
        process?.outputStream?.close()
        process?.destroy()
        timer.shutdownNow()
        mcp.stop()
    }
}
