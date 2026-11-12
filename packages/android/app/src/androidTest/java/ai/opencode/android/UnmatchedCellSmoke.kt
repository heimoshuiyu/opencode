package ai.opencode.android

import android.os.Build
import android.os.Parcel
import android.telephony.CellInfo
import android.telephony.CellInfoTdscdma
import org.json.JSONObject

/** Local serializer test only. No modem read, MCP invocation or Termux process. */
internal fun unmatchedCellSmoke(): String {
    // There is no public constructor. This test-only layout follows AOSP android-16.0.0_r1
    // CellInfoTdscdma.writeToParcel and its CellIdentity / CellSignalStrength writers.
    check(Build.VERSION.SDK_INT == 36) { "This Parcelable fixture targets API 36" }
    val p = Parcel.obtain()
    val cell = try {
        p.writeInt(5); p.writeInt(1); p.writeLong(123L); p.writeInt(CellInfo.CONNECTION_PRIMARY_SERVING)
        p.writeInt(5); p.writeString("001"); p.writeString("01"); p.writeString("Test"); p.writeString("Test")
        p.writeInt(123); p.writeInt(4567); p.writeInt(7); p.writeInt(10054) // LAC, CID, CPID, UARFCN
        p.writeInt(0); p.writeParcelable(null, 0) // Empty additional PLMN set; no CSG
        p.writeInt(-80); p.writeInt(0); p.writeInt(-85); p.writeInt(3) // RSSI, BER, RSCP, level
        p.setDataPosition(0)
        (CellInfo.CREATOR.createFromParcel(p) as CellInfoTdscdma).also { check(p.dataAvail() == 0) }
    } finally { p.recycle() }
    check(cell.isRegistered && cell.cellIdentity.cid == 4567 && cell.cellSignalStrength.dbm == -85 && cell.cellSignalStrength.level == 3)
    val report = JSONObject().put("fixture", "synthetic CellInfoTdscdma")
    for (view in listOf("default", "basic", "full")) {
        val args = JSONObject().apply { if (view != "default") put("view", view) }
        val result = cellInfoResult(listOf(cell), args)
        val rows = result.getJSONArray("cells")
        check(rows.length() == 1) { "$view lost the array entry: $result" }
        val row = rows.getJSONObject(0)
        if (view == "full") check(row.length() == 0) { "Expected an empty object: $result" }
        else check(row.length() == 4 && row.get("type") == "CellInfoTdscdma" && row.get("registered") == true &&
            row.get("dbm") == -85 && row.get("level") == 3) { "Basic summary changed: $result" }
        report.put(view, result)
    }
    return report.toString()
}
