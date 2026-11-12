package ai.opencode.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission
import org.json.JSONObject

/** A single default-subscription snapshot, matching Termux's field names and scalar types. */
@RequiresPermission(Manifest.permission.READ_PHONE_STATE)
@Suppress("DEPRECATION")
internal fun phoneDeviceInfo(context: Context): JSONObject {
    val tel = context.getSystemService(TelephonyManager::class.java) ?: error("Telephony unavailable")
    fun granted() = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    check(granted()) { "Required Android permission denied" }
    val out = JSONObject()
    fun field(key: String, read: () -> Any?) {
        val value = try { read() } catch (_: UnsupportedOperationException) { null } catch (_: SecurityException) { null }
        out.put(key, value ?: JSONObject.NULL)
    }
    field("data_enabled") { tel.isDataEnabled.toString() } // Upstream emits a string, not a JSON boolean.
    field("data_activity") { when (val v = tel.dataActivity) {
        TelephonyManager.DATA_ACTIVITY_NONE -> "none"; TelephonyManager.DATA_ACTIVITY_IN -> "in"
        TelephonyManager.DATA_ACTIVITY_OUT -> "out"; TelephonyManager.DATA_ACTIVITY_INOUT -> "inout"
        TelephonyManager.DATA_ACTIVITY_DORMANT -> "dormant"; else -> v.toString()
    } }
    field("data_state") { when (val v = tel.dataState) {
        TelephonyManager.DATA_DISCONNECTED -> "disconnected"; TelephonyManager.DATA_CONNECTING -> "connecting"
        TelephonyManager.DATA_CONNECTED -> "connected"; TelephonyManager.DATA_SUSPENDED -> "suspended"; else -> v.toString()
    } }
    field("device_software_version") { tel.deviceSoftwareVersion }; field("phone_count") { tel.phoneCount }
    field("phone_type") { when (val v = tel.phoneType) {
        TelephonyManager.PHONE_TYPE_NONE -> "none"; TelephonyManager.PHONE_TYPE_GSM -> "gsm"
        TelephonyManager.PHONE_TYPE_CDMA -> "cdma"; TelephonyManager.PHONE_TYPE_SIP -> "sip"; else -> v.toString()
    } }
    field("network_operator") { tel.networkOperator }; field("network_operator_name") { tel.networkOperatorName }
    field("network_country_iso") { tel.networkCountryIso }
    field("network_type") { when (val v = tel.networkType) {
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown"; TelephonyManager.NETWORK_TYPE_GPRS -> "gprs"
        TelephonyManager.NETWORK_TYPE_EDGE -> "edge"; TelephonyManager.NETWORK_TYPE_UMTS -> "umts"
        TelephonyManager.NETWORK_TYPE_CDMA -> "cdma"; TelephonyManager.NETWORK_TYPE_EVDO_0 -> "evdo_0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "evdo_a"; TelephonyManager.NETWORK_TYPE_1xRTT -> "1xrtt"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "hdspa" // Spelling in Termux:API v0.53.0.
        TelephonyManager.NETWORK_TYPE_HSUPA -> "hsupa"; TelephonyManager.NETWORK_TYPE_HSPA -> "hspa"
        TelephonyManager.NETWORK_TYPE_IDEN -> "iden"; TelephonyManager.NETWORK_TYPE_EVDO_B -> "evdo_b"
        TelephonyManager.NETWORK_TYPE_LTE -> "lte"; TelephonyManager.NETWORK_TYPE_EHRPD -> "ehrpd"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "hspap"; TelephonyManager.NETWORK_TYPE_NR -> "nr"; else -> v.toString()
    } }
    field("network_roaming") { tel.isNetworkRoaming }; field("sim_country_iso") { tel.simCountryIso }
    field("sim_operator") { tel.simOperator }; field("sim_operator_name") { tel.simOperatorName }
    field("sim_state") { when (val v = tel.simState) {
        TelephonyManager.SIM_STATE_UNKNOWN -> "unknown"; TelephonyManager.SIM_STATE_ABSENT -> "absent"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"; TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puk_required"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network_locked"; TelephonyManager.SIM_STATE_READY -> "ready"; else -> v.toString()
    } }
    // Never attempt to obtain persistent identifiers, even if an installer grants additional roles.
    for (key in listOf("device_id", "sim_serial_number", "sim_subscriber_id")) out.put(key, JSONObject.NULL)
    check(granted()) { "Required Android permission denied" }; return out
}
