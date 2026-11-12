package ai.opencode.android

import android.net.wifi.ScanResult
import org.json.JSONObject

/** Serializer branch test; the synthetic APs are not advertised or injected into Android's scan cache. */
internal fun wifiScanCountSmoke(): String {
    val expected = List(101) { "02:00:00:00:00:" + (it * 17 % 101).toString(16).padStart(2, '0') }
    check(expected.distinct().size == 101)
    val scans = expected.map { bssid -> ScanResult().apply { BSSID = bssid } }
    val rows = wifiScanNetworks(scans)
    val actual = (0 until rows.length()).map { rows.getJSONObject(it).getString("bssid") }
    val report = JSONObject().put("fixture", "synthetic ScanResult").put("inputCount", scans.size)
        .put("returnedCount", rows.length()).put("expectedLastBssid", expected.last()).put("returnedLastBssid", actual.lastOrNull())
        .put("orderMatches", actual == expected)
    check(actual == expected) { "Wi-Fi scan list mismatch: $report" }
    return report.toString()
}
