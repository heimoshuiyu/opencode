package ai.opencode.android

import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import org.json.JSONArray
import org.json.JSONObject

internal fun cellInfoResult(cells: List<CellInfo>, args: JSONObject): JSONObject {
    val full = args.optString("view") == "full"
    val records = if (full) cells else cells.take(64)
    val rows = JSONArray()
    for (cell in records) rows.put(cellRecord(cell, full))
    return JSONObject().put("cells", rows)
}

/** Only the explicitly approved detailed view includes location-sensitive identity fields. */
@Suppress("DEPRECATION") // v0.53.0 writes integer MCC/MNC for GSM, WCDMA and LTE, strings for NR.
internal fun cellRecord(cell: CellInfo, full: Boolean): JSONObject {
    if (!full) {
        val signal = cell.cellSignalStrength
        return JSONObject().put("type", cell.javaClass.simpleName).put("registered", cell.isRegistered)
            .put("dbm", signal.dbm).put("level", signal.level)
    }
    val type = when (cell) {
        is CellInfoWcdma -> "wcdma"
        is CellInfoGsm -> "gsm"
        is CellInfoCdma -> "cdma"
        is CellInfoLte -> "lte"
        is CellInfoNr -> "nr"
        else -> return JSONObject() // v0.53.0 emits an empty object, not a missing array element.
    }
    val signal = cell.cellSignalStrength
    val row = JSONObject().put("type", type).put("registered", cell.isRegistered)
    fun known(key: String, value: Int) { if (value != CellInfo.UNAVAILABLE) row.put(key, value) }
    if (cell is CellInfoWcdma) {
        val id = cell.cellIdentity
        row.put("asu", signal.asuLevel).put("level", signal.level)
        known("dbm", signal.dbm)
        known("cid", id.cid); known("lac", id.lac)
        known("mcc", id.mcc); known("mnc", id.mnc); known("psc", id.psc)
        return row
    }
    if (cell is CellInfoGsm) {
        val id = cell.cellIdentity
        row.put("asu", signal.asuLevel).put("level", signal.level)
        known("dbm", signal.dbm)
        known("cid", id.cid); known("lac", id.lac)
        known("mcc", id.mcc); known("mnc", id.mnc)
        return row
    }
    row.put("dbm", signal.dbm)
    if (cell is CellInfoCdma) {
        val id = cell.cellIdentity
        val cdma = cell.cellSignalStrength
        // v0.53.0 writes every CDMA getter verbatim, including unavailable integers.
        return row.put("asu", cdma.asuLevel).put("level", cdma.level)
            .put("cdma_dbm", cdma.cdmaDbm).put("cdma_ecio", cdma.cdmaEcio).put("cdma_level", cdma.cdmaLevel)
            .put("evdo_dbm", cdma.evdoDbm).put("evdo_ecio", cdma.evdoEcio).put("evdo_level", cdma.evdoLevel).put("evdo_snr", cdma.evdoSnr)
            .put("basestation", id.basestationId).put("latitude", id.latitude).put("longitude", id.longitude)
            .put("network", id.networkId).put("system", id.systemId)
    }
    row.put("asu", signal.asuLevel)
    known("level", signal.level)
    if (cell is CellInfoLte) {
        val id = cell.cellIdentity
        val lte = cell.cellSignalStrength
        known("timing_advance", lte.timingAdvance)
        known("ci", id.ci); known("pci", id.pci); known("tac", id.tac)
        known("mcc", id.mcc); known("mnc", id.mnc)
        known("rsrp", lte.rsrp); known("rsrq", lte.rsrq); known("rssi", lte.rssi)
        return row.put("bands", JSONArray(id.bands.toList()))
    }
    val id = cell.cellIdentity as CellIdentityNr
    row.put("mcc", id.mccString ?: JSONObject.NULL).put("mnc", id.mncString ?: JSONObject.NULL)
    if (id.nci != CellInfo.UNAVAILABLE_LONG) row.put("nci", id.nci)
    known("pci", id.pci); known("tac", id.tac)
    if (signal is CellSignalStrengthNr) {
        known("csi_rsrp", signal.csiRsrp); known("csi_rsrq", signal.csiRsrq); known("csi_sinr", signal.csiSinr)
        known("ss_rsrp", signal.ssRsrp); known("ss_rsrq", signal.ssRsrq); known("ss_sinr", signal.ssSinr)
    }
    row.put("bands", JSONArray(id.bands.toList()))
    return row
}
