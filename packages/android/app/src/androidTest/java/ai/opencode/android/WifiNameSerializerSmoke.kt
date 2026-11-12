package ai.opencode.android

import android.net.wifi.ScanResult
import org.json.JSONObject

/** Synthetic ScanResults only: exercise the production serializer without scanning or invoking MCP. */
internal fun wifiNameSerializerSmoke(): String {
    val operator = " 运营商 A&B "
    val venue = "二层 · Hall B"
    val operatorOnly = wifiScanRecord(ScanResult().apply { operatorFriendlyName = StringBuilder(operator) })
    val venueOnly = wifiScanRecord(ScanResult().apply { venueName = StringBuilder(venue) })
    check(operatorOnly.get("operatorName") == operator && !operatorOnly.has("venueName")) { "operator only: $operatorOnly" }
    check(venueOnly.get("venueName") == venue && !venueOnly.has("operatorName")) { "venue only: $venueOnly" }
    return JSONObject().put("source", "synthetic ScanResult").put("operatorOnly", operatorOnly).put("venueOnly", venueOnly).toString()
}
