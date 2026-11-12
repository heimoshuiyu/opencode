package ai.opencode.android

import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.SensorEventListener
import android.os.Handler
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Resolve each Termux-style name fragment to one actual catalog entry, not its type's default sensor. */
internal fun selectSensors(sm: SensorManager, args: JSONObject): JSONArray {
    require(listOf("type", "types", "names", "all").count(args::has) == 1) { "Specify exactly one of type, types, names or all:true" }
    val inventory = sm.getSensorList(Sensor.TYPE_ALL)
    val selected = if (args.has("all")) {
        require(args.get("all") == true) { "all must be the boolean true" }
        inventory.also { require(it.isNotEmpty()) { "No app-visible sensors" } }
    } else if (args.has("names")) {
        val names = args.getJSONArray("names"); require(names.length() in 1..8) { "Specify 1–8 sensor name fragments" }
        val sorted = inventory.sortedBy { it.name }
        (0 until names.length()).mapNotNull { i ->
            val value = names.get(i) as? String ?: error("Expected a sensor name string")
            val fragment = value.trim().uppercase(Locale.ROOT); require(fragment.isNotEmpty()) { "Empty sensor name fragment" }
            sorted.filter { it.name.uppercase(Locale.ROOT).contains(fragment) }.minByOrNull { it.name.uppercase(Locale.ROOT).length }
        }.distinct().also { require(it.isNotEmpty()) { "No matching sensors" } }
    } else {
        val types = if (args.has("types")) {
            val ids = args.getJSONArray("types"); require(ids.length() in 1..8) { "Specify 1–8 sensor types" }
            (0 until ids.length()).map { ids.get(it) as? Int ?: error("Expected integer sensor types") }
        } else listOf(args.opt("type") as? Int ?: error("Expected an integer sensor type"))
        require(types.distinct().size == types.size) { "Duplicate sensor types" }
        types.map { sm.getDefaultSensor(it) ?: error("Sensor type $it not available") }
    }
    return JSONArray(selected.map { JSONObject().put("index", inventory.indexOf(it)).put("name", it.name).put("type", it.type) })
}

internal fun approvedSensors(sm: SensorManager, args: JSONObject): List<Sensor> {
    val inventory = sm.getSensorList(Sensor.TYPE_ALL); val selected = args.getJSONArray("selected")
    return (0 until selected.length()).map { i ->
        val entry = selected.getJSONObject(i)
        inventory.getOrNull(entry.getInt("index"))?.takeIf { it.name == entry.getString("name") && it.type == entry.getInt("type") }
            ?: error("Sensor inventory changed; retry selection")
    }
}

/** Register the confirmed inventory on the Activity looper. Only accepted entries become streams. */
internal fun registerSelectedSensors(sm: SensorManager, args: JSONObject, listener: SensorEventListener, handler: Handler, accepted: (Sensor) -> Unit): JSONObject {
    val registered = JSONArray(); val unavailable = JSONArray()
    approvedSensors(sm, args).forEachIndexed { i, sensor ->
        val item = args.getJSONArray("selected").getJSONObject(i)
        val failure = try {
            if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) "Requires a trigger sensor listener"
            else if (sm.registerListener(listener, sensor, args.getInt("intervalMs") * 1000, handler)) null
            else "Android rejected listener registration"
        } catch (e: RuntimeException) { e.message ?: e.javaClass.simpleName }
        if (failure == null) { registered.put(item); accepted(sensor) }
        else {
            check(args.optBoolean("all")) { "Cannot register ${sensor.name}: $failure" }
            unavailable.put(JSONObject(item.toString()).put("reason", failure))
        }
    }
    return JSONObject().put("registered", registered).put("unavailable", unavailable)
}
