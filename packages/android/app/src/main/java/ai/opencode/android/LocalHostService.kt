package ai.opencode.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.concurrent.thread

/** User-started service owns Node. A bound Activity owns only the UI and foreground permission dialogs. */
internal class LocalHostService : Service() {
    companion object {
        const val STOP = "ai.opencode.android.STOP_LOCAL"
        var running = false
            private set
    }
    sealed interface State {
        object Starting : State
        data class Ready(val endpoint: JSONObject) : State
        data class Ended(val error: String?) : State
    }
    interface Client {
        fun changed(state: State)
        fun location(query: LocationRequest.Query): JSONObject
        fun deviceAction(name: String, args: JSONObject): JSONObject
    }
    inner class Connection : Binder() { val service get() = this@LocalHostService }
    private val clients = java.util.concurrent.CopyOnWriteArrayList<Client>()
    private var state: State = State.Starting
    private var runtime: LocalServer? = null
    private val main = Handler(Looper.getMainLooper())
    fun attach(client: Client) { clients.add(client); client.changed(state) }
    fun detach(client: Client) { clients.remove(client) }
    override fun onBind(intent: Intent) = Connection()
    private fun publish(next: State) { state = next; clients.forEach { it.changed(next) } }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { end(null); return START_NOT_STICKY }
        if (runtime != null || state is State.Ended) return START_NOT_STICKY
        val notice = DeviceNotifications(this).running()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notice)
        val host = LocalServer(this, DeviceMcp(this, { mode ->
            clients.lastOrNull()?.location(mode) ?: error("Open the local app to share a location")
        }, { name, args -> clients.lastOrNull()?.deviceAction(name, args) ?: error("Open the local app to approve this action") }))
        running = true
        runtime = host
        thread(name = "opencode-node") {
            try {
                host.run { endpoint -> main.post { if (runtime === host) publish(State.Ready(endpoint)) } }
            } catch (e: Exception) { main.post { if (runtime === host) end(e.message ?: "Startup failed") } }
        }
        return START_NOT_STICKY
    }
    private fun end(reason: String?) {
        if (state !is State.Ended) publish(State.Ended(reason))
        closeHost(revokeRetained = reason == null); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun closeHost(revokeRetained: Boolean = false) {
        running = false
        val host = runtime
        runtime = null
        if (host != null || revokeRetained) WorkspaceJobs.shutdown(this, revokeRetained)
        host?.close()
    }
    override fun onDestroy() { closeHost(); clients.clear(); super.onDestroy() }
}
