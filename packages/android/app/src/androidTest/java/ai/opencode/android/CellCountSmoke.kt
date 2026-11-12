package ai.opencode.android

import android.os.Build
import android.os.Parcel
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import org.json.JSONObject

/** Synthetic boundary test for the production serializer; no modem read or MCP call. */
internal fun cellCountSmoke(): String {
    check(Build.VERSION.SDK_INT == 36) { "The GSM Parcelable fixture targets API 36" }
    val expected = List(65) { 7000 + (it * 17 % 65) } // Distinct, deliberately not sorted.
    val cells = expected.map { cid ->
        // AOSP android-16.0.0_r1: CellInfoGsm, CellIdentityGsm, CellSignalStrengthGsm.
        val p = Parcel.obtain()
        try {
            p.writeInt(1); p.writeInt(1); p.writeLong(123L); p.writeInt(CellInfo.CONNECTION_PRIMARY_SERVING)
            p.writeInt(1); p.writeString("001"); p.writeString("01"); p.writeString("Test"); p.writeString("Test")
            p.writeInt(123); p.writeInt(cid); p.writeInt(42); p.writeInt(3); p.writeInt(0) // LAC, CID, ARFCN, BSIC, empty PLMN set
            p.writeInt(-75); p.writeInt(0); p.writeInt(1); p.writeInt(3) // RSSI, BER, timing advance, level
            p.setDataPosition(0)
            (CellInfo.CREATOR.createFromParcel(p) as CellInfoGsm).also { check(p.dataAvail() == 0 && it.cellIdentity.cid == cid) }
        } finally { p.recycle() }
    }
    val rows = cellInfoResult(cells, JSONObject().put("view", "full")).getJSONArray("cells")
    val actual = (0 until rows.length()).map { rows.getJSONObject(it).get("cid") }
    val report = JSONObject().put("fixture", "synthetic CellInfoGsm").put("inputCount", cells.size)
        .put("returnedCount", rows.length()).put("expectedLastCid", expected.last()).put("returnedLastCid", actual.lastOrNull())
        .put("orderMatches", actual == expected)
    check(actual == expected) { "Full cell list mismatch: $report" }
    for (view in listOf("default", "basic")) {
        val args = JSONObject().apply { if (view == "basic") put("view", view) }
        val summary = cellInfoResult(cells, args).getJSONArray("cells")
        check(summary.length() == 64 && (0 until summary.length()).all { summary.getJSONObject(it).keys().asSequence().toSet() == setOf("type", "registered", "dbm", "level") })
        report.put("${view}Count", summary.length())
    }
    return report.toString()
}
