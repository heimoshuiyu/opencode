package ai.opencode.android

import android.app.Activity
import android.app.AlertDialog
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One foreground subscription. Events, reads and teardown are serialized on the Activity looper. */
internal class SensorSubscription(private val activity: Activity) {
    private val manager = activity.getSystemService(SensorManager::class.java)
    private class Stream(val sensor: Sensor) {
        val queue = ArrayDeque<JSONObject>()
        var next = SystemClock.elapsedRealtimeNanos()
        var dropped = 0L
    }
    private class Session(val interval: Int) {
        val streams = linkedMapOf<Sensor, Stream>()
        val id = UUID.randomUUID().toString()
        lateinit var listener: SensorEventListener
        lateinit var indicator: AlertDialog
    }
    private var session: Session? = null
    val active get() = session != null

    fun start(args: JSONObject): JSONObject {
        check(!active) { "A sensor subscription is already active" }
        val current = Session(args.getInt("intervalMs"))
        current.listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            override fun onSensorChanged(e: SensorEvent) {
                if (session !== current) return
                val stream = current.streams[e.sensor] ?: return
                if (e.timestamp < stream.next) return
                if (e.values.any { !it.isFinite() }) { close(); return }
                stream.next = e.timestamp + current.interval * 1_000_000L
                if (stream.queue.size == 64) { stream.queue.removeFirst(); stream.dropped++ }
                stream.queue.addLast(JSONObject().put("timestampNanos", e.timestamp).put("values", JSONArray(e.values.map { it.toDouble() })))
            }
        }
        current.indicator = AlertDialog.Builder(activity).setTitle(R.string.sensor_active)
            .setMessage(activity.getString(R.string.sensor_stream_notice, args.getString("name"), current.interval))
            .setPositiveButton(R.string.sensor_stream_stop) { _, _ -> if (session === current) close() }.setCancelable(false).create()
        session = current
        val report = try {
            current.indicator.show()
            val handler = Handler(Looper.getMainLooper())
            registerSelectedSensors(manager, args, current.listener, handler) { current.streams[it] = Stream(it) }
        } catch (e: Exception) { close(); throw e }
        val sensors = current.streams.keys
        if (sensors.isEmpty()) { close(); return report.put("state", "unavailable") }
        return report.put("state", "active").put("subscriptionId", current.id).put("types", JSONArray(sensors.map { it.type }))
            .put("names", JSONArray(sensors.map { it.name })).put("intervalMs", current.interval)
    }

    fun control(args: JSONObject): JSONObject {
        val current = session
        if (current != null) {
            require(args.getString("subscriptionId") == current.id) { "Unknown sensor subscription" }
            if (args.getString("operation") == "read" && current.indicator.isShowing) {
                val limit = args.getInt("limit")
                val groups = current.streams.values.map { stream ->
                    val samples = JSONArray(); repeat(minOf(limit, stream.queue.size)) { samples.put(stream.queue.removeFirst()) }
                    JSONObject().put("type", stream.sensor.type).put("name", stream.sensor.name).put("samples", samples)
                        .put("remaining", stream.queue.size).put("dropped", stream.dropped).also { stream.dropped = 0L }
                }
                return JSONObject().put("state", "active").put("subscriptionId", current.id).put("sensors", JSONArray(groups))
            }
            close()
        }
        return JSONObject().put("state", "stopped").put("sensors", JSONArray())
    }

    fun close() {
        val current = session ?: return
        session = null
        try { manager.unregisterListener(current.listener) } finally {
            current.streams.values.forEach { it.queue.clear() }; current.indicator.dismiss()
        }
    }
}
