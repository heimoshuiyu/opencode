package ai.opencode.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import org.json.JSONObject
import java.util.UUID

/** Only an explicitly submitted SystemUI reply becomes data; no text is evaluated as a command. */
class TaskReply : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val generation = intent.identifier ?: return
        val id = intent.getStringExtra("id") ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString() ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        val current = manager.activeNotifications.singleOrNull { it.tag == id && it.id == 2 }?.notification ?: return
        val button = current.actions.orEmpty().singleOrNull { it.extras.getString(GENERATION) == generation } ?: return
        synchronized(TaskReply::class.java) {
            val receipt = JSONObject().put("id", id).put("text", text)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("latest", receipt.toString()).apply()
        }
        if (current.flags and Notification.FLAG_ONGOING_EVENT == 0) manager.cancel(id, 2)
        else manager.notify(id, 2, Notification.Builder.recoverBuilder(context, current)
            .setActions(*current.actions.filterNot { it === button }.toTypedArray()).setOnlyAlertOnce(true).build())
    }

    companion object {
        private const val ACTION = "ai.opencode.android.TASK_REPLY"
        private const val KEY = "opencode.reply.text"
        private const val GENERATION = "opencode.reply.generation"
        private const val PREFS = "task-replies"

        internal fun action(context: Context, id: String, label: String): Notification.Action {
            val generation = UUID.randomUUID().toString()
            val intent = Intent(context, TaskReply::class.java).setAction(ACTION).setIdentifier(generation).putExtra("id", id)
            // SystemUI must fill the RemoteInput results into this explicit broadcast, unlike navigation buttons.
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val send = PendingIntent.getBroadcast(context, 0, intent, mutable or PendingIntent.FLAG_ONE_SHOT)
            val input = RemoteInput.Builder(KEY).setLabel(context.getText(R.string.task_reply_hint)).setAllowFreeFormInput(true).build()
            return Notification.Action.Builder(null, label, send).addRemoteInput(input).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                .setAllowGeneratedReplies(false).addExtras(Bundle().apply { putString(GENERATION, generation) })
                .apply { if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) }.build()
        }

        internal fun take(context: Context, args: JSONObject): JSONObject = synchronized(TaskReply::class.java) {
            val id = args.opt("id"); require(id is String && id.isNotBlank() && id.length <= 128) { "Expected a notification id" }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val reply = prefs.getString("latest", null)?.let(::JSONObject)?.takeIf { it.getString("id") == id }
            if (reply != null) prefs.edit().remove("latest").apply()
            JSONObject().put("reply", reply ?: JSONObject.NULL)
        }
    }
}
