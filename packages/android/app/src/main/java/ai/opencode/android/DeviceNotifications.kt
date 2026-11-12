package ai.opencode.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.nio.ByteBuffer
import org.json.JSONObject
import org.json.JSONArray

internal class DeviceNotifications(private val context: Context) {
    @Suppress("DEPRECATION")
    enum class Priority(val wire: String, val notificationValue: Int, val channelImportance: Int) {
        MIN("min", Notification.PRIORITY_MIN, NotificationManager.IMPORTANCE_MIN),
        LOW("low", Notification.PRIORITY_LOW, NotificationManager.IMPORTANCE_LOW),
        DEFAULT("default", Notification.PRIORITY_DEFAULT, NotificationManager.IMPORTANCE_DEFAULT),
        HIGH("high", Notification.PRIORITY_HIGH, NotificationManager.IMPORTANCE_HIGH),
        MAX("max", Notification.PRIORITY_MAX, NotificationManager.IMPORTANCE_HIGH)
    }
    companion object {
        val navigationTargets = mapOf("open_app" to ServerPicker::class.java, "open_folders" to FileAccessActivity::class.java,
            "open_remote" to MainActivity::class.java) // MainActivity defaults to remote; no local service is started.
    }
    val smallIcons: Map<String, Int> by lazy {
        val icons = context.resources.obtainTypedArray(R.array.notification_small_icons)
        try {
            buildMap {
                for (i in 0 until icons.length()) {
                    val id = icons.getResourceId(i, 0)
                    val name = context.resources.getResourceEntryName(id).removePrefix("ic_").removeSuffix("_black_24dp")
                    check(put(name, id) == null) { "Duplicate small icon: $name" }
                }
            }
        } finally { icons.recycle() }
    }
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel("server", context.getString(R.string.server_channel), NotificationManager.IMPORTANCE_LOW))
    }
    private fun open() = PendingIntent.getActivity(context, 1,
        Intent(context, MainActivity::class.java).putExtra("local", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
    fun running(): Notification {
        val stop = PendingIntent.getService(context, 2, Intent(context, LocalHostService::class.java).setAction(LocalHostService.STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, "server").setSmallIcon(R.drawable.ic_server)
            .setContentTitle(context.getString(R.string.running)).setContentText(context.getString(R.string.running_detail))
            .setCategory(Notification.CATEGORY_SERVICE).setContentIntent(open()).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.stop_server), stop).build())
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }.build()
    }
    @Suppress("DEPRECATION") // Keep the per-notification priority as well as the Android 8+ channel request.
    fun send(args: JSONObject, player: DevicePlayer): JSONObject {
        val title = args.getString("title"); val message = args.getString("message")
        require(title.isNotBlank() && title.length <= 100 && message.isNotBlank() && message.length <= 2000) { "Use a title of 1–100 and message of 1–2000 characters" }
        fun optionalKey(name: String): String? {
            if (!args.has(name)) return null
            val value = args.opt(name)
            require(value is String && value.isNotBlank() && value.length <= 128) { "$name must be a nonblank string of at most 128 UTF-16 units" }
            return value
        }
        fun flag(name: String): Boolean {
            val value = args.opt(name) ?: false
            require(value is Boolean) { "$name must be a boolean" }
            return value
        }
        val channelId = optionalKey("channel") ?: "tasks"
        val requested = args.opt("priority") ?: "default"
        val priority = Priority.entries.firstOrNull { it.wire == requested } ?: error("priority must be min, low, default, high or max")
        require(channelId != "server") { "The server channel is reserved for the local service" }
        val channel = manager.getNotificationChannel(channelId)
        require(channel != null || channelId == "tasks") { "Unknown OpenCode notification channel" }
        check(manager.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE &&
            channel?.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } != true) {
            "Enable task notifications in Android settings for OpenCode."
        }
        val ongoing = flag("ongoing")
        val explicitId = optionalKey("id")
        require(!ongoing || explicitId != null) { "ongoing:true requires an explicit id" }
        val id = explicitId ?: java.util.UUID.randomUUID().toString()
        val type = optionalKey("type") ?: "default"
        require(type in setOf("default", "media")) { "type must be default or media" }
        fun target(name: String, field: String) = navigationTargets[name] ?: error("Unknown $field; choose ${navigationTargets.keys.joinToString()}")
        val bodyTarget = optionalKey("action")?.let { target(it, "action") }
        val onDelete = optionalKey("onDelete")
        require(onDelete == null || onDelete == "record_dismissal") { "onDelete must be record_dismissal" }
        val buttons = (1..3).mapNotNull { slot ->
            val field = "button$slot"
            val label = optionalKey(field); val action = optionalKey("${field}Action")
            val commandKey = "${field}Command"
            if (action == "shell") {
                require(NotificationCommand.valid(args.opt(commandKey))) { "$commandKey must contain 1–1024 printable ASCII characters" }
            } else require(!args.has(commandKey)) { "$commandKey requires ${field}Action=shell" }
            require((label == null) == (action == null)) { "Supply $field and ${field}Action together" }
            if (label == null) return@mapNotNull null
            Triple(slot, label, when (action) {
                "reply" -> null
                "shell" -> NotificationCommand::class.java
                else -> target(requireNotNull(action), "${field}Action")
            })
        }
        require(type != "media" || buttons.isEmpty()) { "Media controls replace navigation buttons" }
        val group = optionalKey("group")
        val alertOnce = flag("alertOnce")
        val sound = flag("sound")
        val led = optionalKey("ledColor")?.let {
            require(it.matches(Regex("[0-9a-fA-F]{6}"))) { "ledColor must be six hexadecimal digits (RRGGBB)" }
            it.toInt(16) or 0xff000000.toInt()
        }
        fun ledMillis(key: String): Int {
            if (!args.has(key)) return 800
            val n = args.opt(key)
            require(n is Number && n.toDouble() in 0.0..Int.MAX_VALUE.toDouble() && n.toDouble() == n.toInt().toDouble()) { "$key must be a nonnegative 32-bit integer in milliseconds" }
            return n.toInt()
        }
        val onMs = ledMillis("ledOnMs"); val offMs = ledMillis("ledOffMs")
        val vibration = if (args.has("vibrate")) vibrationPattern(args.opt("vibrate")) else null
        val icon = optionalKey("icon")?.let { smallIcons[it] ?: error("Unknown notification icon; use a name from the icon enum") } ?: R.drawable.ic_server
        val bitmap = if (args.has("imagePath")) {
            val path = args.opt("imagePath")
            require(path is String) { "imagePath must be a workspace-relative string" }
            picture(path)
        } else null
        // One stable default channel, not a new channel per priority. Android preserves user settings.
        if (channelId == "tasks") manager.createNotificationChannel(NotificationChannel(channelId, context.getString(R.string.task_channel), priority.channelImportance))
        val builder = Notification.Builder(context, channelId).setSmallIcon(icon).setPriority(priority.notificationValue)
            .setContentTitle(title).setContentText(message)
            .apply {
                if (bitmap != null) setLargeIcon(bitmap)
                if (type != "media") setStyle(if (bitmap == null) Notification.BigTextStyle().bigText(message) else Notification.BigPictureStyle().bigPicture(bitmap).setSummaryText(message))
            }
            .apply { if (group != null) setGroup(group) }
            .apply { if (onDelete != null) setDeleteIntent(TaskDismissal.pending(context, id)) }
            .apply { for ((slot, label, target) in buttons) {
                if (target == null) { addAction(TaskReply.action(context, id, label)); continue }
                val intent = Intent(context, target).setIdentifier("button$slot:$id").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (target == NotificationCommand::class.java) {
                    intent.putExtra("command", args.getString("button${slot}Command"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
                val click = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT)
                addAction(Notification.Action.Builder(null, label, click).build())
            } }
            .apply { if (vibration != null) setVibrate(vibration) }
            .apply { if (sound) setSound(Settings.System.DEFAULT_NOTIFICATION_URI) }
            .apply { if (led != null) setLights(led, onMs, offMs) }
            .setOnlyAlertOnce(alertOnce).setOngoing(ongoing)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(bodyTarget?.let {
                PendingIntent.getActivity(context, 0, Intent(context, it).setIdentifier("body:$id").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            } ?: open()).setAutoCancel(!ongoing)
        if (type == "media") player.postMedia(id, title, message, builder) else {
            player.detachNotice(id)
            manager.notify(id, 2, builder.build())
        }
        return JSONObject().put("posted", true).put("id", id)
    }

    private fun vibrationPattern(value: Any?): LongArray {
        require(value is JSONArray && value.length() in 1..64) { "vibrate must be an array of 1–64 durations in milliseconds" }
        // Android starts with a delay; Termux starts with the first vibration duration.
        val pattern = LongArray(value.length() + 1)
        for (i in 0 until value.length()) {
            val ms = value.opt(i)
            require(ms is Number && ms.toDouble() in 0.0..60_000.0 && ms.toDouble() == ms.toLong().toDouble()) { "vibrate durations must be nonnegative whole milliseconds (at most 60000)" }
            pattern[i + 1] = ms.toLong()
        }
        require(pattern.sum() <= 60_000L) { "vibrate timings must total at most 60000 ms" }
        return pattern
    }

    private fun picture(path: String): Bitmap {
        val file = FileShare.source(context, path)
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        val bytes = try {
            val pfd = ParcelFileDescriptor.dup(fd)
            DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pfd)).use { input ->
                val stat = Os.fstat(fd)
                require(OsConstants.S_ISREG(stat.st_mode) && Os.readlink("/proc/self/fd/${pfd.fd}") == file.path) { "Image source changed" }
                require(stat.st_size in 1L..8L * 1024 * 1024) { "Notification image must be 1 byte to 8 MiB" }
                ByteArray(stat.st_size.toInt()).also { input.readFully(it) }
            }
        } finally { Os.close(fd) }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val size = info.size
            require(size.width in 1..8192 && size.height in 1..8192 && size.width.toLong() * size.height <= 24_000_000) { "Notification image dimensions are too large" }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize((maxOf(size.width, size.height) + 1023) / 1024)
        }
    }

    private fun channelRecord(channel: NotificationChannel) = JSONObject().put("id", channel.id)
        .put("name", channel.name.toString()).put("importance", channel.importance)
    fun channels() = JSONArray(manager.notificationChannels.map(::channelRecord))
    private fun customChannelId(args: JSONObject): String {
        val id = args.opt("id")
        require(id is String && id.isNotBlank() && id.length <= 128) { "id must be a nonblank string of at most 128 UTF-16 units" }
        require(id !in setOf("tasks", "server", "miscellaneous")) { "Reserved notification channel id" }
        return id
    }
    fun createChannel(args: JSONObject): JSONObject {
        val id = customChannelId(args); val name = args.opt("name")
        require(name is String && name.isNotBlank() && name.length <= 128) { "name must be a nonblank string of at most 128 UTF-16 units" }
        val channel = manager.getNotificationChannel(id)?.apply { this.name = name }
            ?: NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        return channelRecord(checkNotNull(manager.getNotificationChannel(id)) { "Notification channel was not registered" })
    }
    fun deleteChannel(args: JSONObject): JSONObject {
        val id = customChannelId(args)
        check(manager.getNotificationChannel(id) != null) { "Unknown OpenCode notification channel" }
        manager.deleteNotificationChannel(id)
        check(manager.getNotificationChannel(id) == null) { "Notification channel is still active" }
        return JSONObject().put("id", id).put("deleted", true)
    }
    fun list() = JSONArray(manager.activeNotifications.filter { it.tag != null && it.id == 2 }.map {
        JSONObject().put("id", it.tag).put("title", it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()).put("postedAt", it.postTime)
    })
    fun remove(id: String): JSONObject {
        check(manager.activeNotifications.any { it.id == 2 && it.tag == id }) { "No such OpenCode task notification" }
        manager.cancel(id, 2); return JSONObject().put("removed", true)
    }
}
