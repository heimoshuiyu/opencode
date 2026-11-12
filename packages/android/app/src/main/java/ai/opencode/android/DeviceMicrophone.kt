package ai.opencode.android

import android.Manifest.permission.RECORD_AUDIO
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.MediaRecorder
import android.media.MediaRecorder.AudioEncoder
import android.media.MediaRecorder.OutputFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.View
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Activity-owned, all operations on the main thread. Never handed to the foreground service. */
internal class DeviceMicrophone(private val activity: Activity) {
    enum class Encoding(val encoder: Int, val format: Int, val suffix: String, val mime: String, val rate: Int, val bitrate: Int) {
        AAC(AudioEncoder.AAC, OutputFormat.MPEG_4, "m4a", MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 96000),
        AMR_NB(AudioEncoder.AMR_NB, OutputFormat.AMR_NB, "amr", MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8000, 12200),
        AMR_WB(AudioEncoder.AMR_WB, OutputFormat.AMR_WB, "awb", MediaFormat.MIMETYPE_AUDIO_AMR_WB, 16000, 23850),
        OPUS(AudioEncoder.OPUS, OutputFormat.OGG, "ogg", MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 64000);
        val wire get() = name.lowercase()
    }
    companion object {
        fun request(args: JSONObject): JSONObject {
            if (args.optString("operation") != "start") {
                require(!args.has("fileName")) { "fileName only applies to start" }
                require(listOf("encoder", "sampleRateHz", "bitrateBps", "channels").none(args::has)) { "Recording format only applies to start" }
                return args
            }
            val encoding = Encoding.entries.firstOrNull { it.wire == args.optString("encoder", "aac") } ?: error("Unknown audio encoder")
            val name = args.opt("fileName")
            if (name != null) {
                require(name is String && WorkspaceOutput.valid(name)) { "Expected a relative workspace recording path" }
                require(name.endsWith(".${encoding.suffix}", ignoreCase = true)) { "Expected .${encoding.suffix} extension" }
            }
            fun integer(key: String, default: Int, range: IntRange): Int {
                val n = args.opt(key) ?: return default
                require(n is Int && n in range) { "$key must be an integer in ${range.first}..${range.last}" }; return n
            }
            val rate = integer("sampleRateHz", encoding.rate, 8000..96000); val channels = integer("channels", 1, 1..2)
            if (encoding == Encoding.AMR_NB || encoding == Encoding.AMR_WB) require(channels == 1 && rate == encoding.rate) { "${encoding.wire} requires mono at ${encoding.rate} Hz" }
            return JSONObject().put("operation", "start").put("seconds", integer("seconds", 60, 0..300)).put("encoder", encoding.wire)
                .put("sampleRateHz", rate).put("channels", channels).put("bitrateBps", integer("bitrateBps", encoding.bitrate, 4000..512000))
                .put("fileName", name)
        }
    }
    private data class Active(val recorder: MediaRecorder, val file: File, val destination: WorkspaceOutput?, val since: Long, val indicator: AlertDialog, val encoding: Encoding, val requested: JSONObject)
    private var active: Active? = null
    private var last = JSONObject().put("state", "idle")
    private val timer = Handler(Looper.getMainLooper())

    fun info(): JSONObject = active?.let { JSONObject().put("state", "recording").put("encoder", it.encoding.wire).put("requested", it.requested).put("elapsedMs", SystemClock.elapsedRealtime() - it.since) } ?: last

    fun start(args: JSONObject): JSONObject {
        check(active == null) { "Already recording" }
        val seconds = args.getInt("seconds"); val encoding = Encoding.valueOf(args.getString("encoder").uppercase())
        val rate = args.getInt("sampleRateHz"); val channels = args.getInt("channels"); val bitrate = args.getInt("bitrateBps")
        check(!activity.isFinishing && !activity.isDestroyed && activity.window.decorView.windowVisibility == View.VISIBLE &&
            activity.checkSelfPermission(RECORD_AUDIO) == PERMISSION_GRANTED) { "Foreground microphone permission required" }
        val workspace = File(activity.filesDir.canonicalFile, "home/projects")
        check(workspace.canonicalFile == workspace && workspace.isDirectory) { "Workspace not available" }
        val generated = "recording-${UUID.randomUUID()}.${encoding.suffix}"
        val destination = if (args.has("fileName")) WorkspaceOutput(activity.filesDir, args.getString("fileName"), "Recording").also { it.checkNew() } else null
        val file = File(if (destination == null) workspace else activity.cacheDir, generated)
        val duration = if (seconds == 0) activity.getString(R.string.mic_no_limit) else activity.getString(R.string.mic_limit, seconds)
        val indicator = AlertDialog.Builder(activity).setTitle(R.string.mic_active).setMessage(duration)
            .setPositiveButton(R.string.mic_stop) { _, _ -> stop("user") }.setCancelable(false).create()
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(activity) else @Suppress("DEPRECATION") MediaRecorder()
        var created = false
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(encoding.format)
            recorder.setAudioEncoder(encoding.encoder)
            recorder.setAudioChannels(channels); recorder.setAudioSamplingRate(rate); recorder.setAudioEncodingBitRate(bitrate)
            val fd = Os.open(file.path, OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_WRONLY or OsConstants.O_CLOEXEC, 384)
            created = true
            try { recorder.setOutputFile(fd); recorder.prepare() } finally { Os.close(fd) }
            recorder.setOnErrorListener { source, _, _ -> if (active?.recorder === source) stop("recorder error", true) }
            indicator.show()
            recorder.start(); active = Active(recorder, file, destination, SystemClock.elapsedRealtime(), indicator, encoding,
                JSONObject().put("seconds", seconds).put("sampleRateHz", rate).put("channels", channels).put("bitrateBps", bitrate))
            if (seconds > 0) timer.postDelayed({ if (active?.recorder === recorder) stop("time limit") }, seconds * 1000L)
            return info()
        } catch (e: Exception) {
            indicator.dismiss(); recorder.release(); if (created) file.delete(); throw e
        }
    }

    fun stop(reason: String, failed: Boolean = false): JSONObject {
        val capture = active ?: return last
        active = null; timer.removeCallbacksAndMessages(null)
        last = try {
            capture.recorder.stop()
            check(!failed && capture.file.length() > 0) { "Recording failed" }
            val extractor = MediaExtractor()
            val track = try { extractor.setDataSource(capture.file.path); check(extractor.trackCount == 1) { "Missing audio track" }; extractor.getTrackFormat(0) }
                finally { extractor.release() }
            check(track.getString(MediaFormat.KEY_MIME) == capture.encoding.mime) { "Android did not produce the requested encoding" }
            val result = JSONObject().put("state", "stopped").put("reason", reason).put("path", (capture.destination?.file ?: capture.file).path)
                .put("encoder", capture.encoding.wire).put("mime", capture.encoding.mime)
                .put("requested", capture.requested).put("trackBitrateBps", track.getInteger(MediaFormat.KEY_BIT_RATE, 0).takeIf { it > 0 } ?: JSONObject.NULL)
                .put("sampleRateHz", track.getInteger(MediaFormat.KEY_SAMPLE_RATE)).put("channels", track.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                .put("elapsedMs", SystemClock.elapsedRealtime() - capture.since).put("bytes", capture.file.length())
            capture.destination?.let { destination ->
                capture.file.inputStream().use { destination.writeNew(it) }
                capture.file.delete()
            }
            result
        } catch (e: Exception) {
            capture.file.delete(); JSONObject().put("state", "error").put("error", e.message ?: "No audio recorded")
        } finally { capture.recorder.release(); capture.indicator.dismiss() }
        return last
    }

}
