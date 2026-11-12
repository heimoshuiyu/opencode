package ai.opencode.android

import android.app.Activity
import android.app.AlertDialog
import android.Manifest.permission.RECORD_AUDIO
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import org.json.JSONObject
import android.window.OnBackInvokedDispatcher
import java.io.FileNotFoundException

// A stable WebView origin preserves saved servers across launches. No socket listens on this port.
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val attachments by lazy { AttachmentChooser(this) { !isFinishing && !isDestroyed && web.url?.let { Uri.parse(it).isShell() } == true } }
    private val shellAudio by lazy { ShellAudio(this, web, Uri.parse("http://$shellAuthority")) }
    private val local by lazy { intent.getBooleanExtra("local", false) }
    // Different origins also separate saved remote credentials, drafts and tabs from the local instance.
    private val shellAuthority get() = if (local) "127.0.0.1:39842" else "127.0.0.1:39841"
    private fun Uri.isShell() = scheme == "http" && encodedAuthority == shellAuthority
    private val locationRequest by lazy { LocationRequest(this) }
    internal val deviceInteraction by lazy { DeviceInteraction(this) }
    private var unbindHost: (() -> Unit)? = null
    private var afterNotificationPermission: (() -> Unit)? = null
    private var microphone: PermissionRequest? = null
    private var askingForMicrophone = false
    @Volatile private var connection: JSONObject? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        web = WebView(this)
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, result: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean = attachments.show(result, params)
            override fun onPermissionRequest(request: PermissionRequest) {
                if (!request.origin.isShell() || web.url?.let { Uri.parse(it).isShell() } != true || !hasWindowFocus() ||
                    !request.resources.contentEquals(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) || askingForMicrophone || shellAudio.askingPermission) {
                    request.deny()
                    return
                }
                if (checkSelfPermission(RECORD_AUDIO) == PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    return
                }
                microphone = request
                askingForMicrophone = true
                requestPermissions(arrayOf(RECORD_AUDIO), 1900)
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (microphone === request) microphone = null
            }
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { attachments.cancel(); shellAudio.detach() }
            override fun onPageFinished(view: WebView, url: String) {
                if (Uri.parse(url).isShell() && view.url?.let { Uri.parse(it).isShell() } == true) shellAudio.attach()
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.url.isShell()) return if (request.isForMainFrame) missing() else null
                if (request.method != "GET") return missing()
                val path = request.url.path.orEmpty().removePrefix("/")
                if (path == "android/connection.json" && !request.isForMainFrame) {
                    val data = connection?.toString() ?: "null"
                    return WebResourceResponse("application/json", "utf-8", 200, "OK",
                        mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), data.byteInputStream())
                }
                if (path.split('/').any { it == ".." || it == "." } || '\\' in path) return missing()
                val file = if (request.isForMainFrame) "index.html" else path
                val stream = try {
                    assets.open("web/$file")
                } catch (_: FileNotFoundException) {
                    return missing()
                }
                val type = when (val ext = file.substringAfterLast('.')) {
                    "js", "mjs" -> "text/javascript"
                    "wasm" -> "application/wasm"
                    "webmanifest" -> "application/manifest+json"
                    "woff2" -> "font/woff2"
                    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                }
                return WebResourceResponse(type, if (type.startsWith("text/")) "utf-8" else null, 200, "OK",
                    mapOf("Cache-Control" to "no-cache", "X-Content-Type-Options" to "nosniff",
                        "Permissions-Policy" to "camera=(), microphone=(self), geolocation=()"), stream)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame || request.url.isShell()) return false
                if (request.hasGesture() && request.url.scheme in setOf("https", "http", "mailto")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        // A device without a browser must still keep the shell on its packaged page.
                    }
                }
                return true
            }
        }
        // WebView does not resize its document for view padding. Inset its container instead so the
        // composer stays above the system keyboard when Android enforces edge-to-edge layout.
        window.setDecorFitsSystemWindows(false)
        val frame = FrameLayout(this)
        frame.addView(web)
        frame.setOnApplyWindowInsetsListener { view, insets ->
            val padding = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(frame)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::back)
        }
        fun open() {
            if (state == null || web.restoreState(state) == null) web.loadUrl("http://$shellAuthority/")
        }
        if (!local) { open(); return }
        val status = TextView(this).apply { setText(R.string.starting); gravity = Gravity.CENTER }
        frame.addView(status)
        val client = object : LocalHostService.Client {
            override fun location(query: LocationRequest.Query) = locationRequest.read(query)
            override fun deviceAction(name: String, args: JSONObject) = deviceInteraction.invoke(name, args)
            override fun changed(state: LocalHostService.State) {
                if (isDestroyed || isFinishing) return
                when (state) {
                    LocalHostService.State.Starting -> Unit
                    is LocalHostService.State.Ready -> { connection = state.endpoint; frame.removeView(status); open() }
                    is LocalHostService.State.Ended -> {
                        deviceInteraction.pauseSensor()
                        connection = null
                        if (state.error == null) finish() else AlertDialog.Builder(this@MainActivity)
                            .setMessage(getString(R.string.failed, state.error)).setPositiveButton(R.string.return_picker) { _, _ -> finish() }
                            .setCancelable(false).show()
                    }
                }
            }
        }
        var host: LocalHostService? = null
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                host = (binder as LocalHostService.Connection).service.also { it.attach(client) }
            }
            override fun onServiceDisconnected(name: ComponentName) { connection = null; host = null; finish() }
        }
        val service = Intent(this, LocalHostService::class.java)
        fun startHost() {
            if (isDestroyed || isFinishing) return
            startForegroundService(service)
            if (bindService(service, binding, BIND_AUTO_CREATE)) unbindHost = { host?.detach(client); unbindService(binding) }
            else finish()
        }
        val prefs = getSharedPreferences("notifications", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked", false) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED) {
            prefs.edit().putBoolean("asked", true).apply()
            // Android doesn't replay a foreground notification posted before this first grant.
            afterNotificationPermission = ::startHost
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1901)
        } else startHost()
    }

    private fun missing() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        web.saveState(state)
    }

    private fun back() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    @Deprecated("Used on Android 11 and 12")
    override fun onBackPressed() = back()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (attachments.permissionResult(requestCode)) return
        if (requestCode == ShellAudio.PERMISSION) { shellAudio.permissionResult(); return }
        if (requestCode == 1901) {
            val start = afterNotificationPermission; afterNotificationPermission = null; start?.invoke(); return
        }
        if (requestCode == 1900) {
            askingForMicrophone = false
            val request = microphone ?: return
            microphone = null
            if (!isFinishing && !isDestroyed && request.origin.isShell() && web.url?.let { Uri.parse(it).isShell() } == true &&
                checkSelfPermission(RECORD_AUDIO) == PERMISSION_GRANTED) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            else request.deny()
            return
        }
        if (local && !deviceInteraction.permissionsResult(requestCode)) locationRequest.permissionResult(requestCode)
    }

    override fun onPause() {
        shellAudio.pause()
        if (local) deviceInteraction.pauseVibration()
        if (local) deviceInteraction.pauseCamera()
        if (local) deviceInteraction.pauseSensor()
        if (local) locationRequest.stopUpdates()
        if (local) deviceInteraction.pauseNfc()
        if (local) deviceInteraction.stopMicrophone()
        super.onPause()
    }

    override fun onStop() {
        shellAudio.hidden()
        if (local) deviceInteraction.hidden()
        if (local) locationRequest.cancel("App is no longer visible")
        super.onStop()
    }

    override fun onDestroy() {
        attachments.cancel()
        shellAudio.detach()
        if (local) deviceInteraction.cancel("Local screen closed")
        microphone?.deny(); microphone = null
        connection = null
        if (local) locationRequest.cancel("Local session closed")
        unbindHost?.invoke(); unbindHost = null
        (web.parent as? FrameLayout)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
    @Deprecated("System picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (attachments.activityResult(requestCode, resultCode, data)) return
        if (local) deviceInteraction.result(requestCode, resultCode, data)
    }
}
