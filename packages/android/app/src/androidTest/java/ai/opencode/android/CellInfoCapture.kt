package ai.opencode.android

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Capture authenticated MCP replies and a separate framework snapshot on either side. */
internal fun cellInfoCapture(test: Instrumentation, radio: String?): String {
    val full = radio != null
    val c = test.targetContext
    check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic modem on an emulator required" }
    for (p in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        check(c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED)
    check(c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_DENIED)
    check(c.getSystemService(LocationManager::class.java).isLocationEnabled)
    val tel = c.getSystemService(TelephonyManager::class.java)
    val a = test.startActivitySync(Intent(c, ServerPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { DeviceInteraction(a) }; val rpc = DeviceMcp(c, { error("Unexpected location fix request") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor()
    fun await(label: String, f: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 20000
        while (!main(f)) { check(SystemClock.elapsedRealtime() < until) { "Timed out: $label" }; SystemClock.sleep(40) }
    }
    fun witness() = JSONArray(checkNotNull(tel.allCellInfo) { "No system snapshot" }.map { cell ->
        JSONObject().put("class", cell.javaClass.simpleName).put("registered", cell.isRegistered).put("dbm", cell.cellSignalStrength.dbm).put("level", cell.cellSignalStrength.level).apply {
            if (cell is CellInfoNr) {
                val id = cell.cellIdentity as CellIdentityNr
                put("mcc", id.mccString); put("mnc", id.mncString)
                if (id.nci != Long.MAX_VALUE) put("nci", id.nci)
                if (id.pci != Int.MAX_VALUE) put("pci", id.pci)
                if (id.tac != Int.MAX_VALUE) put("tac", id.tac)
            }
            if (cell is CellInfoLte) {
                val id = cell.cellIdentity
                put("mccString", id.mccString); put("mncString", id.mncString)
                if (id.ci != Int.MAX_VALUE) put("ci", id.ci)
            }
            if (cell is CellInfoGsm) {
                val id = cell.cellIdentity
                put("mccString", id.mccString); put("mncString", id.mncString)
                if (id.lac != Int.MAX_VALUE) put("lac", id.lac)
                if (id.cid != Int.MAX_VALUE) put("cid", id.cid)
            }
            if (cell is CellInfoCdma) {
                val id = cell.cellIdentity
                put("system", id.systemId); put("network", id.networkId); put("basestation", id.basestationId)
            }
            if (cell is CellInfoWcdma) {
                val id = cell.cellIdentity
                put("mccString", id.mccString); put("mncString", id.mncString)
                if (id.lac != Int.MAX_VALUE) put("lac", id.lac)
                if (id.cid != Int.MAX_VALUE) put("cid", id.cid)
                if (id.psc != Int.MAX_VALUE) put("psc", id.psc)
            }
        }
    })
    fun request(detail: Boolean, allow: Boolean): String {
        await("local foreground") { a.hasWindowFocus() }
        val args = JSONObject().apply { if (detail) put("view", "full") }
        val notice = if (detail) a.getString(R.string.cell_identity_notice) else a.getString(R.string.device_request_detail, "{}")
        val call = worker.submit<String> { rpc.smokeCall("telephony_cellinfo", args, expectError = !allow, timeoutMs = 45000) }
        await("cellinfo confirmation") {
            check(!call.isDone) { "Call returned without confirmation" }
            val root = WindowInspector.getGlobalWindowViews().singleOrNull { it.hasWindowFocus() &&
                it.findViewById<TextView>(android.R.id.message)?.text == notice }
            root?.findViewById<Button>(if (allow) android.R.id.button1 else android.R.id.button2)?.performClick() == true
        }
        return call.get(10, TimeUnit.SECONDS)
    }
    try {
        rpc.start(10000, true); await("local foreground") { a.hasWindowFocus() }
        val before = witness(); val report = JSONObject().put("nativeBefore", before)
        if (full) {
            val declined = request(true, false); check(declined == "Device request declined")
            val basic = JSONObject(request(false, true)); val cells = basic.getJSONArray("cells")
            for (i in 0 until cells.length()) check(cells.getJSONObject(i).keys().asSequence().toSet() == setOf("type", "registered", "dbm", "level"))
            report.put("declined", declined).put("basic", basic)
        }
        val raw = JSONObject(request(full, true)); val cells = raw.getJSONArray("cells")
        if (radio == "nr") check((0 until cells.length()).any { cells.getJSONObject(it).optString("type") == "nr" && cells.getJSONObject(it).has("nci") }) { "No identifiable NR sample; cannot certify field parity" }
        if (radio == "lte") check((0 until cells.length()).any { i ->
            val cell = cells.getJSONObject(i)
            cell.opt("type") == "lte" && listOf("mcc", "mnc", "ci").all { cell.opt(it) is Int && cell.getInt(it) != Int.MAX_VALUE }
        }) { "No identifiable LTE sample; cannot certify field parity: $raw" }
        if (radio == "gsm") check((0 until cells.length()).any { i ->
            val cell = cells.getJSONObject(i)
            cell.opt("type") == "gsm" && listOf("mcc", "mnc", "lac", "cid").all { cell.opt(it) is Int && cell.getInt(it) != Int.MAX_VALUE }
        }) { "No identifiable GSM sample; cannot certify field parity: $raw" }
        report.put("mcp", raw).put("nativeAfter", witness())
        if (radio == "wcdma") check((0 until cells.length()).any { i ->
            val cell = cells.getJSONObject(i)
            cell.opt("type") == "wcdma" && listOf("mcc", "mnc", "lac", "cid").all { cell.opt(it) is Int && cell.getInt(it) in 0 until Int.MAX_VALUE }
        }) { "No identifiable WCDMA sample; cannot certify field parity: $report" }
        if (radio == "cdma") check((0 until cells.length()).any { i ->
            val cell = cells.getJSONObject(i)
            cell.opt("type") == "cdma" && listOf("system", "network", "basestation").all { cell.opt(it) is Int && cell.getInt(it) in 0 until Int.MAX_VALUE }
        }) { "No identifiable CDMA sample; cannot certify field parity: $report" }
        return report.toString()
    } finally { main { ui.cancel("Fixture closed"); a.finish() }; worker.shutdownNow(); rpc.stop() }
}
