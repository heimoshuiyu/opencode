package ai.opencode.android

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Emulator-only A/B fixture. Never log an identifier even if a restricted getter succeeds. */
@Suppress("DEPRECATION")
internal fun phoneInfoSmoke(test: Instrumentation): String {
    val c = test.targetContext
    check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator required" }
    check(c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
    check(c.checkSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE") == PackageManager.PERMISSION_DENIED)
    val tel = c.getSystemService(TelephonyManager::class.java); check(!tel.hasCarrierPrivileges())
    val a = test.startActivitySync(Intent(c, ServerPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { DeviceInteraction(a) }; val rpc = DeviceMcp(c, { error("Unexpected location") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor()
    fun await(f: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 20000
        while (!main(f)) { check(SystemClock.elapsedRealtime() < until) { "Missing phone consent or foreground" }; SystemClock.sleep(40) }
    }
    fun full(allow: Boolean): String {
        await { a.hasWindowFocus() }
        val call = worker.submit<String> { rpc.smokeCall("telephony_deviceinfo", JSONObject().put("view", "full"), expectError = !allow, timeoutMs = 45000) }
        await {
            check(!call.isDone) { "Detailed call returned before consent" }
            val root = WindowInspector.getGlobalWindowViews().singleOrNull { it.hasWindowFocus() &&
                it.findViewById<TextView>(android.R.id.message)?.text == a.getString(R.string.phone_state_notice) }
            root?.findViewById<Button>(if (allow) android.R.id.button1 else android.R.id.button2)?.performClick() == true
        }; return call.get(10, TimeUnit.SECONDS)
    }
    fun probe(read: () -> String?): String = try { if (read() == null) "null" else "readable (value discarded)" }
        catch (_: SecurityException) { "SecurityException" } catch (_: UnsupportedOperationException) { "UnsupportedOperationException" }
    try {
        rpc.start(10000, true)
        val basic = JSONObject(rpc.smokeCall("telephony_deviceinfo")); check(basic.keys().asSequence().toSet() == setOf("operator", "country", "simState", "phoneType"))
        val declined = full(false); check(declined == "Device request declined")
        val raw = JSONObject(full(true)); check(raw.length() == 18)
        for (k in listOf("device_id", "sim_serial_number", "sim_subscriber_id")) check(raw.has(k) && raw.isNull(k))
        val restricted = JSONObject().put("device_id", probe { if (tel.phoneType == TelephonyManager.PHONE_TYPE_GSM) tel.imei else tel.meid })
            .put("sim_serial_number", probe { tel.simSerialNumber }).put("sim_subscriber_id", probe { tel.subscriberId })
        return JSONObject().put("basic", basic).put("full", raw).put("decline", declined).put("ordinaryPhoneGranted", true)
            .put("privilegedPhoneGranted", false).put("carrierPrivileges", false).put("sameUidGetterOutcomes", restricted).toString()
    } finally { main { ui.cancel("Fixture closed"); a.finish() }; worker.shutdownNow(); rpc.stop() }
}
