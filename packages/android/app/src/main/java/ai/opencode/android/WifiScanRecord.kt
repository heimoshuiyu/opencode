package ai.opencode.android

import android.net.wifi.ScanResult
import org.json.JSONArray
import org.json.JSONObject

internal fun wifiScanNetworks(scans: List<ScanResult>): JSONArray = JSONArray().apply {
    for (scan in scans) put(wifiScanRecord(scan))
}

internal fun wifiScanRecord(scan: ScanResult): JSONObject = JSONObject()
    .put("ssid", scan.SSID).put("bssid", scan.BSSID).put("frequencyMHz", scan.frequency)
    .put("levelDbm", scan.level).put("timestampUs", scan.timestamp)
    .put("channelBandwidthMHz", when (scan.channelWidth) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> "20"
        ScanResult.CHANNEL_WIDTH_40MHZ -> "40"
        ScanResult.CHANNEL_WIDTH_80MHZ -> "80"
        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80"
        ScanResult.CHANNEL_WIDTH_160MHZ -> "160"
        else -> "???"
    })
    .put("capabilities", scan.capabilities?.takeIf { it.isNotEmpty() })
    .apply {
        if (!scan.operatorFriendlyName.isNullOrEmpty()) put("operatorName", scan.operatorFriendlyName.toString())
        if (!scan.venueName.isNullOrEmpty()) put("venueName", scan.venueName.toString())
        if (scan.channelWidth != ScanResult.CHANNEL_WIDTH_20MHZ) put("centerFreq0MHz", scan.centerFreq0)
        if (scan.channelWidth == ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ) put("centerFreq1MHz", scan.centerFreq1)
    }
