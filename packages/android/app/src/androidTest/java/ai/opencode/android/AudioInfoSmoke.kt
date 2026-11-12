package ai.opencode.android

import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import org.json.JSONArray
import org.json.JSONObject

@Suppress("DEPRECATION")
internal fun audioInfoSmoke(context: Context): String {
    val server = DeviceMcp(context, { error("Unexpected location request") }, { _, _ -> error("Unexpected UI request") })
    try {
        server.start(10000, true)
        val manager = context.getSystemService(AudioManager::class.java)
        val devices = JSONArray(server.smokeCall("audio_info"))
        check((0 until devices.length()).map { devices.getJSONObject(it).getInt("id") }.toSet() == manager.getDevices(AudioManager.GET_DEVICES_ALL).map { it.id }.toSet())
        val data = JSONObject(server.smokeCall("audio_info", JSONObject().put("view", "capabilities")))
        for ((field, key) in mapOf("outputSampleRateHz" to AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE, "outputFramesPerBuffer" to AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)) {
            val expected = manager.getProperty(key)?.toIntOrNull()
            check(data.has(field) && if (expected == null) data.isNull(field) else data.getInt(field) == expected) { field }
        }
        check(data.getBoolean("bluetoothA2dpOn") == manager.isBluetoothA2dpOn && data.getBoolean("wiredHeadsetConnected") == manager.isWiredHeadsetOn)
        for ((profile, mode) in mapOf("default" to AudioTrack.PERFORMANCE_MODE_NONE, "lowLatency" to AudioTrack.PERFORMANCE_MODE_LOW_LATENCY, "powerSaving" to AudioTrack.PERFORMANCE_MODE_POWER_SAVING)) {
            val actual = data.getJSONObject("tracks").getJSONObject(profile)
            val builder = AudioTrack.Builder().setBufferSizeInBytes(4)
            if (profile != "default") builder.setPerformanceMode(mode)
            val reference = builder.build()
            try {
                check(actual.getInt("sampleRateHz") == reference.sampleRate && actual.getInt("bufferFrames") == reference.bufferSizeInFrames &&
                    actual.getInt("actualPerformanceMode") == reference.performanceMode) { "$profile: $actual" }
            } finally { reference.release() }
        }
        return "inventory=${devices.length()} $data"
    } finally { server.stop() }
}
