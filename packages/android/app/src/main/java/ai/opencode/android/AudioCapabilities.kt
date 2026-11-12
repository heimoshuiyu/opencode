package ai.opencode.android

import android.media.AudioManager
import android.media.AudioTrack
import org.json.JSONObject

@Suppress("DEPRECATION") // Match the wired-headset and A2DP indicators used by termux-audio-info.
internal fun audioCapabilities(audio: AudioManager): JSONObject {
    fun measure(mode: Int): JSONObject {
        // Ask for one PCM stereo frame. Android raises the buffer size to what the route supports.
        val track = try { AudioTrack.Builder().setBufferSizeInBytes(4).setPerformanceMode(mode).build() }
        catch (e: UnsupportedOperationException) { return JSONObject().put("unavailable", e.message ?: "Unsupported audio track") }
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) return JSONObject().put("unavailable", "Track not initialized")
            return JSONObject().put("sampleRateHz", track.sampleRate).put("bufferFrames", track.bufferSizeInFrames)
                .put("actualPerformanceMode", track.performanceMode)
        } finally { track.release() }
    }
    return JSONObject().put("outputSampleRateHz", audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: JSONObject.NULL)
        .put("outputFramesPerBuffer", audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: JSONObject.NULL)
        .put("bluetoothA2dpOn", audio.isBluetoothA2dpOn).put("wiredHeadsetConnected", audio.isWiredHeadsetOn)
        .put("tracks", JSONObject().put("default", measure(AudioTrack.PERFORMANCE_MODE_NONE))
            .put("lowLatency", measure(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)).put("powerSaving", measure(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)))
}
