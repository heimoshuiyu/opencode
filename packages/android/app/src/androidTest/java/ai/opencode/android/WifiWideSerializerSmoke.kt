package ai.opencode.android

import android.net.wifi.ScanResult
import org.json.JSONObject

/** Synthetic inputs to the shipped scan-record serializer. No Wi-Fi scan, radio, or MCP call. */
internal fun wifiWideSerializerSmoke(): String {
    fun input(width: Int, center0: Int) = ScanResult().apply {
        frequency = 5180
        channelWidth = width
        centerFreq0 = center0
        centerFreq1 = 5775 // Deliberately nonzero even for 160 MHz: omission must depend on width.
    }
    val contiguous = input(ScanResult.CHANNEL_WIDTH_160MHZ, 5250)
    val split = input(ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ, 5210)
    val a = wifiScanRecord(contiguous); val b = wifiScanRecord(split)
    check(a.get("channelBandwidthMHz") == "160" && a.get("centerFreq0MHz") == contiguous.centerFreq0 && !a.has("centerFreq1MHz")) { "160 MHz: $a" }
    check(b.get("channelBandwidthMHz") == "80+80" && b.get("centerFreq0MHz") == split.centerFreq0 && b.get("centerFreq1MHz") == split.centerFreq1) { "80+80 MHz: $b" }
    return JSONObject().put("source", "synthetic ScanResult").put("contiguous", a).put("split", b).toString()
}
