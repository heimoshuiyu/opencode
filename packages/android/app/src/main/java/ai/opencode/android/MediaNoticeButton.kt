package ai.opencode.android

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.session.MediaController
import android.media.session.MediaSession

/** For surfaces using notification actions instead of the MediaSession transport bar. */
class MediaNoticeButton : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION") val token = intent.getParcelableExtra<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) ?: return
        val controller = MediaController(context, token)
        if (controller.packageName != context.packageName) return
        when (intent.action) {
            "previous" -> controller.transportControls.skipToPrevious()
            "next" -> controller.transportControls.skipToNext()
            "pause" -> controller.transportControls.pause()
            "play" -> controller.transportControls.play()
        }
    }

    companion object {
        internal fun add(context: Context, builder: Notification.Builder, id: String, token: MediaSession.Token, multi: Boolean) {
            val controls = buildList {
                if (multi) add(Triple("previous", R.string.media_previous, android.R.drawable.ic_media_previous))
                add(Triple("pause", R.string.media_pause, android.R.drawable.ic_media_pause))
                add(Triple("play", R.string.media_play, android.R.drawable.ic_media_play))
                if (multi) add(Triple("next", R.string.media_next, android.R.drawable.ic_media_next))
            }
            for ((op, label, icon) in controls) {
                val intent = Intent(context, MediaNoticeButton::class.java).setAction(op).setIdentifier(id).putExtra(Notification.EXTRA_MEDIA_SESSION, token)
                val click = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(Notification.Action.Builder(Icon.createWithResource("android", icon), context.getString(label), click).build())
            }
        }
    }
}
