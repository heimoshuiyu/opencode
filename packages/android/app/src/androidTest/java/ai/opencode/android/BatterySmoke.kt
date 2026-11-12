package ai.opencode.android

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

/** Call the real MCP handler over authenticated loopback; compare gauges with the platform API. */
internal fun batterySmoke(context: Context): String {
    val server = DeviceMcp(context, { error("Unexpected location request") }, { _, _ -> error("Unexpected UI request") })
    try {
        server.start(10000, true)
        val data = JSONObject(server.smokeCall("battery_status"))

        val manager = context.getSystemService(BatteryManager::class.java)
        val gauges = mapOf("capacityPercent" to BatteryManager.BATTERY_PROPERTY_CAPACITY,
            "currentNowMicroamps" to BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,
            "currentAverageMicroamps" to BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,
            "chargeCounterMicroampHours" to BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        for ((key, property) in gauges) {
            val sample = manager.getIntProperty(property)
            check(data.has(key) && if (sample == Int.MIN_VALUE) data.isNull(key) else data.getInt(key) == sample) { key }
        }
        val energy = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        check(data.has("energyNanowattHours") && if (energy == Long.MIN_VALUE) data.isNull("energyNanowattHours") else data.getLong("energyNanowattHours") == energy)
        val broadcast = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))!!
        val cycle = if (Build.VERSION.SDK_INT >= 34) broadcast.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) else -1
        check(data.has("cycleCount") && if (cycle < 0) data.isNull("cycleCount") else data.getInt("cycleCount") == cycle)
        return data.toString()
    } finally { server.stop() }
}
