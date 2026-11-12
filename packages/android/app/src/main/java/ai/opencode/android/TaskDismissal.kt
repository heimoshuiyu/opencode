package ai.opencode.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/** The explicit delete intent only records the most recent opted-in dismissal. */
class TaskDismissal : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.identifier ?: return
        val receipt = JSONObject().put("id", id).put("dismissedAt", System.currentTimeMillis())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", receipt.toString()).apply()
    }

    companion object {
        private const val ACTION = "ai.opencode.android.RECORD_TASK_DISMISSAL"
        private const val PREFS = "task-dismissals"
        internal fun pending(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, TaskDismissal::class.java).setAction(ACTION).setIdentifier(id),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        internal fun read(context: Context) = JSONObject().put("last", context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("last", null)?.let(::JSONObject) ?: JSONObject.NULL)
    }
}
