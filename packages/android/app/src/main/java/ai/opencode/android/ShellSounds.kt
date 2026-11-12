package ai.opencode.android

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

/** Only packaged sound IDs, never a URL or a file supplied by the page. */
internal class ShellSounds(private val context: Context) : AutoCloseable {
    private val dir = "web/media-sounds"
    private val assets by lazy { context.assets.list(dir).orEmpty().associateBy { it.substringBeforeLast('.') } }
    private val samples = mutableMapOf<String, Int>()
    private val loaded = mutableSetOf<Int>()
    private var token: String? = null
    private var wanted = 0
    private var stream = 0
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build().apply {
        setOnLoadCompleteListener { p, sample, status ->
            if (status == 0) {
                loaded.add(sample)
                if (wanted == sample) stream = p.play(sample, 1f, 1f, 1, 0, 1f)
            } else { samples.values.remove(sample); Log.w("OpenCodeAudio", "Could not decode a packaged sound ($status)") }
        }
    }

    fun play(id: String, sound: String) {
        val name = assets[sound] ?: return
        if (stream != 0) pool.stop(stream)
        token = id; stream = 0
        wanted = samples.getOrPut(sound) { context.assets.openFd("$dir/$name").use { pool.load(it, 1) } }
        if (wanted in loaded) stream = pool.play(wanted, 1f, 1f, 1, 0, 1f)
    }

    fun stop(id: String) {
        if (token != id) return
        token = null; wanted = 0
        if (stream != 0) pool.stop(stream)
        stream = 0
    }

    override fun close() { wanted = 0; pool.release() }
}
