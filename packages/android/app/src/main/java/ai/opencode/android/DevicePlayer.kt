package ai.opencode.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask

/** One real player per local MCP server. Both commands and native callbacks run on its looper. */
internal class DevicePlayer(private val context: Context, private val documents: SharedDocuments) : AutoCloseable {
    private val thread = HandlerThread("opencode-media").apply { start() }
    private val handler = Handler(thread.looper)
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var mediaNoticeId: String? = null
    private var path: String? = null
    private var queue = emptyList<String>()
    private var queueRoot: String? = null
    private var index = 0
    private var preparing: CompletableFuture<JSONObject>? = null
    private var closed = false
    private val timeout = Runnable { release("Media preparation timed out") }

    private fun release(reason: String, keepSession: Boolean = false) {
        handler.removeCallbacks(timeout)
        if (!keepSession) clearMediaNotice()
        val old = player
        player = null
        path = null
        queue = emptyList(); queueRoot = null; index = 0
        old?.release()
        preparing?.completeExceptionally(IllegalStateException(reason)); preparing = null
    }

    private fun info(): JSONObject {
        val current = player ?: return JSONObject().put("state", "stopped").put("positionMs", 0)
        if (preparing != null) return JSONObject().put("state", "preparing").put("path", path)
        return JSONObject().put("state", if (current.isPlaying) "playing" else "paused").put("path", path)
            .put("positionMs", current.currentPosition).put("durationMs", current.duration)
    }

    fun call(args: JSONObject): JSONObject {
        val result = CompletableFuture<JSONObject>()
        check(handler.post {
            try {
                check(!closed) { "Local player closed" }
                val op = args.getString("operation")
                if (op == "play" && (args.has("path") || args.has("paths"))) {
                    val tracks = if (args.has("paths")) {
                        require(!args.has("path")) { "Specify path or paths, not both" }
                        val items = args.optJSONArray("paths") ?: error("paths must be an array")
                        require(items.length() in 1..32) { "Expected 1–32 track paths" }
                        (0 until items.length()).map { i -> val name = items.opt(i); require(name is String && name.isNotBlank()) { "Expected nonblank track path" }; name }
                    } else listOf(args.getString("path"))
                    load(tracks, args.takeIf { it.has("root") }?.getString("root"), 0, result)
                } else {
                    require(!args.has("paths") && !args.has("path") && !args.has("root")) { "Only play with files accepts paths and root" }
                    when (op) {
                        "info" -> Unit
                        "stop" -> release("Stopped")
                        "pause", "play", "resume" -> {
                            setPlaying(op != "pause")
                        }
                        else -> error("Unknown media operation: $op")
                    }
                    result.complete(info())
                }
            } catch (e: Exception) { result.completeExceptionally(e) }
        }) { "Local player closed" }
        return result.get()
    }

    private fun setPlaying(play: Boolean) {
        val current = checkNotNull(player) { "No file loaded" }; check(preparing == null) { "File still preparing" }
        if (play && !current.isPlaying) current.start()
        if (!play && current.isPlaying) current.pause()
        publishState(current)
    }

    private fun publishState(current: MediaPlayer) {
        val playing = current.isPlaying
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        if (index > 0) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (index < queue.lastIndex) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        session?.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, current.currentPosition.toLong(), if (playing) 1f else 0f).build())
    }

    private fun clearMediaNotice() {
        val old = session ?: return
        session = null; old.isActive = false; old.release()
        mediaNoticeId?.let { context.getSystemService(NotificationManager::class.java).cancel(it, 2) }; mediaNoticeId = null
    }

    // Serialize the session and its single notification with native playback/completion callbacks.
    fun postMedia(id: String, title: String, message: String, builder: Notification.Builder) {
        val job = FutureTask {
            val current = checkNotNull(player) { "Load a file with media_player before posting media controls" }
            check(!closed && preparing == null) { "Player not ready" }
            if (mediaNoticeId != id) clearMediaNotice()
            val active = session ?: MediaSession(context, "OpenCode player").also { s ->
                session = s
                s.setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { if (session === s && preparing == null) setPlaying(true) }
                    override fun onPause() { if (session === s && preparing == null) setPlaying(false) }
                    override fun onSkipToPrevious() { if (session === s) skip(-1) }
                    override fun onSkipToNext() { if (session === s) skip(1) }
                }, handler)
            }
            mediaNoticeId = id
            try {
                active.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, message).putLong(MediaMetadata.METADATA_KEY_DURATION, current.duration.toLong()).build())
                publishState(current); active.isActive = true
                val multi = queue.size > 1
                val compact = if (multi) intArrayOf(0, 1, 3) else intArrayOf(0, 1)
                builder.setStyle(Notification.MediaStyle().setMediaSession(active.sessionToken).setShowActionsInCompactView(*compact))
                MediaNoticeButton.add(context, builder, id, active.sessionToken, multi)
                context.getSystemService(NotificationManager::class.java).notify(id, 2, builder.build())
            } catch (e: Exception) { clearMediaNotice(); throw e }
        }
        check(handler.post(job)) { "Local player closed" }; job.get()
    }

    fun detachNotice(id: String) {
        val job = FutureTask { if (mediaNoticeId == id) clearMediaNotice() }
        check(handler.post(job)) { "Local player closed" }; job.get()
    }

    private fun skip(delta: Int) {
        val current = player ?: return
        if (preparing != null || index + delta !in queue.indices) return
        // Reopen through the same workspace/SAF checks. Never execute a caller-provided command.
        try { load(queue, queueRoot, index + delta, CompletableFuture(), keepSession = true, playWhenReady = current.isPlaying) }
        catch (e: Exception) { release(e.message ?: "Could not switch track") }
    }

    private fun load(tracks: List<String>, root: String?, at: Int, result: CompletableFuture<JSONObject>, keepSession: Boolean = false, playWhenReady: Boolean = true) {
        val name = tracks[at]; require(name.isNotBlank()) { "Empty media path" }
        val next = MediaPlayer()
        try {
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            if (root != null) {
                documents.openMedia(JSONObject().put("root", root).put("path", name)).use { descriptor ->
                    if (descriptor.declaredLength >= 0) next.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
                    else { require(descriptor.startOffset == 0L); next.setDataSource(descriptor.fileDescriptor) }
                }
            } else {
                val workspace = File(context.filesDir.canonicalFile, "home/projects")
                check(workspace.canonicalFile == workspace) { "Workspace root must not be a symlink" }
                val file = (if (File(name).isAbsolute) File(name) else File(workspace, name)).canonicalFile
                require(file.toPath().startsWith(workspace.toPath()) && file.isFile) { "Expected a file in the local workspace or a granted folder" }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    require(File(android.system.Os.readlink("/proc/self/fd/${it.fd}")).canonicalFile.toPath().startsWith(workspace.toPath())) { "Media file moved outside workspace" }
                    next.setDataSource(it.fileDescriptor)
                }
            }
            next.setOnCompletionListener { if (player === it) release("Playback finished") }
            next.setOnErrorListener { p, what, extra -> if (player === p) release("Playback failed ($what/$extra)"); true }
            next.setOnPreparedListener { p -> if (player === p) {
                try {
                    if (playWhenReady) p.start()
                    handler.removeCallbacks(timeout); preparing = null
                    session?.let { s -> s.setMetadata(MediaMetadata.Builder(s.controller.metadata).putLong(MediaMetadata.METADATA_KEY_DURATION, p.duration.toLong()).build()) }
                    publishState(p); result.complete(info())
                } catch (e: Exception) { release("Could not start playback"); result.completeExceptionally(e) }
            } }
            release("Replaced by another track", keepSession)
            queue = tracks; queueRoot = root; index = at
            player = next; preparing = result; path = name
            session?.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_BUFFERING, 0L, 0f).build())
            handler.postDelayed(timeout, 15000); next.prepareAsync()
        } catch (e: Exception) {
            if (player === next) release(e.message ?: "Invalid media source") else next.release()
            throw e
        }
    }

    override fun close() { handler.post { closed = true; release("Local server stopped"); thread.quitSafely() } }
}
