package ai.opencode.android

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.location.LocationManager
import android.location.Location
import android.location.LocationListener
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

/** MCP calls wait on a worker thread. Prompts, permission callbacks and completion are serialized on the UI thread. */
internal class LocationRequest(private val activity: Activity) {
    enum class Mode(val wire: String) { ONCE("once"), LAST("last"), UPDATES("updates") }
    data class Query(val mode: Mode, val operation: String, val subscriptionId: String?) {
        companion object {
            fun parse(args: JSONObject): Query {
                val mode = Mode.entries.firstOrNull { it.wire == args.optString("mode", "once") } ?: error("Expected once, last or updates")
                val op = args.optString("operation", "start")
                require(mode == Mode.UPDATES || (!args.has("operation") && !args.has("subscriptionId"))) { "operation and subscriptionId are only for updates" }
                require(op in setOf("start", "read", "stop")) { "Expected start, read or stop" }
                val id = if (op == "start") { require(!args.has("subscriptionId")); null } else args.getString("subscriptionId").also { require(it.isNotBlank()) }
                return Query(mode, op, id)
            }
        }
    }
    private class Pending(val mode: Mode, val answer: CompletableFuture<JSONObject>)
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Pending? = null
    private var dialog: AlertDialog? = null
    private var cancellation: CancellationSignal? = null
    private var requestCode = 2100
    private class Subscription(val provider: String, val listener: LocationListener, val indicator: AlertDialog) {
        val id = java.util.UUID.randomUUID().toString()
        val fixes = ArrayDeque<JSONObject>()
        var dropped = 0
    }
    private var active: Subscription? = null

    fun read(query: Query): JSONObject {
        val answer = CompletableFuture<JSONObject>()
        activity.runOnUiThread {
            val mode = query.mode
            if (mode == Mode.UPDATES && query.operation != "start") {
                val sub = active
                if (sub != null && sub.id != query.subscriptionId) {
                    answer.completeExceptionally(IllegalStateException("Unknown subscription")); return@runOnUiThread
                }
                if (sub == null || query.operation == "stop" || !permitted(sub.provider) || !sub.indicator.isShowing) {
                    stopUpdates()
                    answer.complete(JSONObject().put("state", "stopped").put("updates", JSONArray())); return@runOnUiThread
                }
                val batch = JSONArray(sub.fixes.toList()); sub.fixes.clear()
                answer.complete(JSONObject().put("state", "active").put("updates", batch).put("dropped", sub.dropped)); sub.dropped = 0
                return@runOnUiThread
            }
            if (pending != null || active != null || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                answer.completeExceptionally(IllegalStateException("Open the local app and retry when no other location request is pending"))
                return@runOnUiThread
            }
            val request = Pending(mode, answer); pending = request
            handler.postDelayed({ if (pending === request) cancel("Location request timed out") }, 60000)
            dialog = AlertDialog.Builder(activity).setTitle(R.string.location_title)
                .setMessage(when (mode) { Mode.LAST -> R.string.location_last_notice; Mode.UPDATES -> R.string.location_updates_notice; Mode.ONCE -> R.string.location_notice })
                .setNegativeButton(android.R.string.cancel) { _, _ -> cancel("Location sharing declined") }
                .setOnCancelListener { cancel("Location sharing declined") }
                .setPositiveButton(if (mode == Mode.UPDATES) R.string.location_start_updates else R.string.share_once) { _, _ ->
                    if (allowed()) locate() else activity.requestPermissions(arrayOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION), ++requestCode)
                }.show()
        }
        return answer.get()
    }

    private fun allowed() = activity.checkSelfPermission(ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED ||
        activity.checkSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED

    private fun permitted(provider: String) = allowed() && (provider != LocationManager.GPS_PROVIDER || activity.checkSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED)

    fun permissionResult(code: Int) {
        if (code != requestCode || pending == null) return
        if (allowed()) locate() else cancel("Android location permission denied")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun locate() {
        val request = pending ?: return
        val manager = activity.getSystemService(LocationManager::class.java)
        val fine = activity.checkSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED
        val providers = if (fine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER)
        fun deliver(fix: Location) {
            if (pending !== request) return
            if (!allowed()) return cancel("Location permission revoked")
            pending = null; clear()
            request.answer.complete(JSONObject().put("latitude", fix.latitude).put("longitude", fix.longitude)
                .put("accuracyMeters", fix.accuracy.toDouble()).put("timestamp", fix.time).apply {
                    if (request.mode == Mode.LAST) put("mode", "last").put("provider", fix.provider)
                })
        }
        try {
            if (request.mode == Mode.LAST) {
                val cached = providers.filter(manager::isProviderEnabled).mapNotNull(manager::getLastKnownLocation)
                    .maxByOrNull { it.elapsedRealtimeNanos } ?: return cancel("No cached location available")
                return deliver(cached)
            }
            val provider = providers.firstOrNull { manager.isProviderEnabled(it) }
                ?: return cancel("No location provider available with the granted permission")
            if (request.mode == Mode.UPDATES) {
                val since = SystemClock.elapsedRealtimeNanos()
                val listener = object : LocationListener {
                    override fun onLocationChanged(fix: Location) {
                        val sub = active ?: return
                        if (sub.listener !== this) return
                        if (!permitted(provider) || !sub.indicator.isShowing) { stopUpdates(); return }
                        if (fix.elapsedRealtimeNanos < since) return
                        if (sub.fixes.size == 16) { sub.fixes.removeFirst(); sub.dropped++ }
                        sub.fixes.addLast(JSONObject().put("latitude", fix.latitude).put("longitude", fix.longitude)
                            .put("accuracyMeters", fix.accuracy.toDouble()).put("timestamp", fix.time).put("provider", provider))
                    }
                    override fun onProviderDisabled(disabled: String) { if (active?.listener === this) stopUpdates() }
                }
                val indicator = AlertDialog.Builder(activity).setTitle(R.string.location_updates_active).setMessage(R.string.location_updates_notice)
                    .setPositiveButton(R.string.location_stop_updates) { _, _ -> stopUpdates() }.setCancelable(false).create()
                val sub = Subscription(provider, listener, indicator)
                try {
                    clear(); active = sub; indicator.show()
                    manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                    pending = null
                    handler.postDelayed({ if (active === sub) stopUpdates() }, 180000)
                    request.answer.complete(JSONObject().put("state", "active").put("subscriptionId", sub.id).put("provider", provider).put("limitSeconds", 180))
                } catch (e: Exception) { cancel(e.message ?: "Cannot subscribe to location updates") }
                return
            }
            val signal = CancellationSignal().also { cancellation = it }
            manager.getCurrentLocation(provider, signal, activity.mainExecutor) { fix ->
                if (request !== pending) return@getCurrentLocation
                if (fix == null) cancel("Location unavailable or permission revoked") else deliver(fix)
            }
        } catch (_: SecurityException) { cancel("Location permission revoked") }
    }

    fun cancel(reason: String) {
        stopUpdates()
        val request = pending ?: return
        pending = null
        clear()
        request.answer.completeExceptionally(IllegalStateException(reason))
    }

    fun stopUpdates() {
        val sub = active ?: return
        active = null; sub.fixes.clear(); handler.removeCallbacksAndMessages(null)
        try { activity.getSystemService(LocationManager::class.java).removeUpdates(sub.listener) } finally { sub.indicator.dismiss() }
    }

    private fun clear() {
        handler.removeCallbacksAndMessages(null)
        cancellation?.cancel(); cancellation = null
        dialog?.dismiss(); dialog = null
    }
}
