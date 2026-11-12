package ai.opencode.android

import android.Manifest.permission.RECORD_AUDIO
import android.app.Activity
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** One private port per packaged top-level document. No global audio mode or communication-device calls. */
internal class ShellAudio(private val activity: Activity, private val web: WebView, private val origin: Uri) {
    companion object { const val PERMISSION = 1902 }
    private val main = Handler(Looper.getMainLooper())
    private var port: WebMessagePort? = null
    private var sounds: ShellSounds? = null
    private var id: String? = null
    private var pending = false
    var askingPermission = false
        private set
    private var cues = false
    private var recorder: MediaRecorder? = null
    private var file: RandomAccessFile? = null
    private val routeError = "Phone microphone is unavailable; recording discarded"
    private val routeListener = AudioRouting.OnRoutingChangedListener { r ->
        if (r === recorder && r.routedDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) fail(routeError)
    }

    fun attach() {
        if (port != null) return
        val pair = web.createWebMessageChannel()
        port = pair[0]
        pair[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(channel: WebMessagePort, message: WebMessage) {
                if (port !== pair[0] || activity.isFinishing || activity.isDestroyed) return
                val data = message.data ?: return
                if (data.length > 512) return
                val msg = runCatching { JSONObject(data) }.getOrNull() ?: return
                val key = msg.optString("id")
                if (key.length != 36) return
                try {
                    when (msg.optString("op")) {
                        "play" -> play(key, msg.optString("sound"))
                        "quiet" -> sounds?.stop(key)
                        "record" -> {
                            if (!activity.hasWindowFocus() || id != null || askingPermission) {
                                send(key, "error", "Open the app and finish the current recording first"); return
                            }
                            id = key; cues = msg.optBoolean("cues")
                            if (activity.checkSelfPermission(RECORD_AUDIO) == PERMISSION_GRANTED) start()
                            else {
                                pending = true; askingPermission = true
                                activity.requestPermissions(arrayOf(RECORD_AUDIO), PERMISSION)
                            }
                        }
                        "finish" -> if (id == key && recorder != null) finish(msg.optBoolean("cues"))
                        "cancel" -> if (id == key) clear()
                    }
                } catch (e: Exception) {
                    if (key == id) fail(e.message ?: "Microphone unavailable")
                    else android.util.Log.w("OpenCodeAudio", "Audio action failed", e)
                }
            }
        })
        web.postWebMessage(WebMessage("opencode:audio", arrayOf(pair[1])), origin)
    }

    private fun play(key: String, sound: String) {
        (sounds ?: ShellSounds(activity).also { sounds = it }).play(key, sound)
    }

    fun permissionResult() {
        askingPermission = false
        if (!pending) return
        pending = false
        if (activity.isFinishing || activity.isDestroyed || web.windowVisibility != View.VISIBLE ||
            activity.checkSelfPermission(RECORD_AUDIO) != PERMISSION_GRANTED) { fail("Microphone permission required"); return }
        try { start() } catch (e: Exception) { fail(e.message ?: "Cannot open phone microphone") }
    }

    private fun start() {
        val key = checkNotNull(id)
        val mic = activity.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?: error(routeError)
        val temp = File.createTempFile("composer-", ".ogg", activity.cacheDir)
        file = RandomAccessFile(temp, "rw")
        check(temp.delete()) // Keep only a private descriptor; abnormal process exit leaves no take on disk.
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(activity) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = r
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        check(r.setPreferredDevice(mic)) { routeError }
        r.setOutputFormat(MediaRecorder.OutputFormat.OGG); r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        r.setAudioSamplingRate(48000); r.setAudioChannels(1); r.setAudioEncodingBitRate(48000)
        r.setOutputFile(file!!.fd); r.setMaxFileSize(16L * 1024 * 1024)
        r.setOnInfoListener { src, what, _ -> if (src === recorder && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) fail("Recording too large") }
        r.setOnErrorListener { src, _, _ -> if (src === recorder) fail("Recording failed") }
        r.prepare(); r.start()
        check(r.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) { routeError }
        r.addOnRoutingChangedListener(routeListener, main)
        send(key, "ready")
        if (cues) play(key, "cue-start")
        main.post(object : Runnable {
            override fun run() {
                if (recorder !== r) return
                if (r.routedDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) { fail(routeError); return }
                send(key, "level", runCatching { r.maxAmplitude }.getOrDefault(0))
                main.postDelayed(this, 100)
            }
        })
    }

    private fun finish(endCue: Boolean) {
        val key = checkNotNull(id); val r = checkNotNull(recorder)
        check(r.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) { routeError }
        r.stop(); releaseRecorder()
        if (endCue) play(key, "cue-end")
        val f = checkNotNull(file); check(f.length() > 0) { "Empty recording" }; f.seek(0)
        val buffer = ByteArray(24 * 1024)
        main.post(object : Runnable {
            override fun run() {
                if (file !== f) return
                try {
                    val n = f.read(buffer)
                    if (n < 0) { send(key, "done"); clear(); return }
                    send(key, "chunk", Base64.encodeToString(buffer, 0, n, Base64.NO_WRAP)); main.post(this)
                } catch (e: Exception) { fail("Cannot read recording") }
            }
        })
    }

    private fun send(key: String, event: String, value: Any? = null) {
        port?.postMessage(WebMessage(JSONObject().put("id", key).put("event", event).put("value", value).toString()))
    }

    private fun releaseRecorder() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.removeOnRoutingChangedListener(routeListener) }; r.release()
    }
    private fun clear() {
        id = null; pending = false; main.removeCallbacksAndMessages(null)
        releaseRecorder(); file?.close(); file = null
    }
    private fun fail(message: String) { val key = id; clear(); if (key != null) send(key, "error", message) }
    fun pause() { if (recorder != null) fail("Recording interrupted") }
    fun hidden() { if (id != null) fail("Recording interrupted") }
    fun detach() { clear(); port?.close(); port = null; sounds?.close(); sounds = null }
}
