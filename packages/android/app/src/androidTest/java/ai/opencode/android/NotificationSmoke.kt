package ai.opencode.android

import android.app.Instrumentation
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Only the control is posted by shell; the OpenCode notification goes through authenticated MCP. */
internal fun notificationSmoke(test: Instrumentation, scenario: String, channelRun: UUID? = null): String {
    fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    check(shell("getprop ro.boot.qemu") == "1") { "Disposable emulator required" }
    val c = test.targetContext
    val rpc = DeviceMcp(c, { error("Unexpected location") }, { _, _ -> error("Unexpected device interaction") })
    val suffix = UUID.randomUUID().toString(); val control = "oc-control-$suffix"
    val title = "OpenCode 验收 $suffix"; val body = "合成通知：任务已完成。"
    fun keys() = shell("cmd notification list").lineSequence().map { it.trim() }.toList()
    fun rows(): List<JSONObject> = JSONArray(rpc.smokeCall("notification_list")).let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    fun await(label: String, f: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (!f()) { check(SystemClock.elapsedRealtime() < end) { "Timed out: $label" }; SystemClock.sleep(60) }
    }
    try {
        rpc.start(10000, true)
        if (scenario == "notification-marker-consent") {
            val command = "printf %s ${requireNotNull(channelRun)} > ~/oc-button-marker"
            check(!File(c.filesDir, "home/oc-button-marker").exists())
            val args = JSONObject().put("id", "consent-$channelRun").put("title", "Marker consent").put("message", "Marker fixture")
                .put("button1", "Review marker").put("button1Action", "shell")
            check(rpc.smokeCall("send_notification", args, expectError = true).contains("button1Command"))
            val posted = JSONObject(rpc.smokeCall("send_notification", args.put("button1Command", command)))
            check(posted.getBoolean("posted") && !File(c.filesDir, "home/oc-button-marker").exists())
            return JSONObject().put("posted", posted).put("command", command).put("markerAfterPost", false).toString()
        }
        if (scenario == "notification-marker-callback") {
            val id = "oc-marker-${requireNotNull(channelRun)}"
            val action = "printf %s $channelRun > ~/oc-button-marker"
            val tools = rpc.smokeResult("tools/list", JSONObject()).getJSONArray("tools")
            val send = (0 until tools.length()).map { tools.getJSONObject(it) }.single { it.getString("name") == "send_notification" }
            val allowed = send.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("button1Action").getJSONArray("enum")
            val args = JSONObject().put("id", id).put("title", "Callback comparison").put("message", "Marker fixture").put("button1", "Write marker").put("button1Action", action)
            val manager = c.getSystemService(NotificationManager::class.java)
            fun posted() = manager.activeNotifications.any { it.tag == id }
            check(!posted())
            val rejected = rpc.smokeToolResult("send_notification", args, 10000)
            check(rejected.getBoolean("isError") && rejected.getJSONArray("content").getJSONObject(0).getString("text").contains("button1Action") && !posted()) { rejected.toString() }
            try {
                val control = JSONObject(rpc.smokeCall("send_notification", args.put("button1Action", "open_app")))
                await("control notification") { posted() }
                return JSONObject().put("callback", action).put("allowed", allowed).put("callbackResult", rejected)
                    .put("postedOnRejection", false).put("openAppControl", control).toString()
            } finally { if (posted()) rpc.smokeCall("notification_remove", JSONObject().put("id", id)) }
        }
        if (scenario == "notification-led") {
            val manager = c.getSystemService(NotificationManager::class.java)
            val channel = "oc-led-$suffix"; val id = "oc-led-check"; val prefix = "0|${c.packageName}|2|$id|"
            val args = JSONObject().put("channel", channel).put("id", id).put("title", title).put("message", body)
            check(keys().none { it.startsWith(prefix) })
            rpc.smokeCall("notification_channel_create", JSONObject().put("id", channel).put("name", "Synthetic light check"))
            try {
                val initial = manager.getNotificationChannel(channel)
                for ((key, value) in listOf("ledColor" to "zz1122", "ledOnMs" to -1, "ledOffMs" to 1.5)) {
                    check(rpc.smokeCall("send_notification", args.put(key, value), expectError = true).startsWith(key)); args.remove(key)
                }
                check(keys().none { it.startsWith(prefix) })
                val trace = JSONArray(); var systemKey: String? = null
                for (mode in listOf("explicit", "color only", "omitted")) {
                    val colored = mode != "omitted"
                    if (colored) args.put("ledColor", "12aBcD") else args.remove("ledColor")
                    if (mode == "explicit") args.put("ledOnMs", 275).put("ledOffMs", 925) else { args.remove("ledOnMs"); args.remove("ledOffMs") }
                    val message = "LED $mode"
                    val sent = JSONObject(rpc.smokeCall("send_notification", args.put("message", message)))
                    check(sent.get("id") == id && sent.get("posted") == true)
                    await("LED task key") { keys().any { it.startsWith(prefix) } }
                    val current = keys().single { it.startsWith(prefix) }; check(systemKey == null || current == systemKey); systemKey = current
                    await(message) { shell("cmd notification get $current").lineSequence().any { it.trim() == "android.text=String ($message)" } }
                    val n = manager.activeNotifications.single { it.key == current }.notification
                    check(n.channelId == channel && n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT) == message)
                    @Suppress("DEPRECATION") val show = (n.flags and android.app.Notification.FLAG_SHOW_LIGHTS) != 0
                    @Suppress("DEPRECATION") val color = n.ledARGB
                    @Suppress("DEPRECATION") val on = n.ledOnMS
                    @Suppress("DEPRECATION") val off = n.ledOffMS
                    check(color == (if (colored) 0xff12abcd.toInt() else 0) && show == colored)
                    check(on == (if (mode == "explicit") 275 else if (colored) 800 else 0) && off == (if (mode == "explicit") 925 else if (colored) 800 else 0))
                    val config = manager.getNotificationChannel(n.channelId)
                    check(config.shouldShowLights() == initial.shouldShowLights() && config.lightColor == initial.lightColor)
                    trace.put(JSONObject().put("mode", mode).put("ledARGB", "%08X".format(color)).put("onMs", on).put("offMs", off).put("showLightsFlag", show)
                        .put("channelShowLights", config.shouldShowLights()).put("channelLightColor", "%08X".format(config.lightColor)))
                }
                return JSONObject().put("systemKey", systemKey).put("channel", channel).put("updates", trace).toString()
            } finally {
                if (keys().any { it.startsWith(prefix) }) rpc.smokeCall("notification_remove", JSONObject().put("id", id))
                rpc.smokeCall("notification_channel_delete", JSONObject().put("id", channel))
            }
        }
        if (scenario == "notification-sound") {
            val manager = c.getSystemService(NotificationManager::class.java)
            val channel = "oc-sound-$suffix"; val id = "oc-sound-check"; val prefix = "0|${c.packageName}|2|$id|"
            val args = JSONObject().put("channel", channel).put("id", id).put("title", title).put("message", body)
            check(keys().none { it.startsWith(prefix) })
            rpc.smokeCall("notification_channel_create", JSONObject().put("id", channel).put("name", "Synthetic sound check"))
            try {
                val before = manager.getNotificationChannel(channel).sound
                for (bad in listOf("true", 1, JSONObject.NULL)) {
                    check(rpc.smokeCall("send_notification", args.put("sound", bad), expectError = true) == "sound must be a boolean")
                }
                check(keys().none { it.startsWith(prefix) })
                val trace = JSONArray(); var key: String? = null
                for (setting in listOf(true, null, false)) {
                    val message = "Sound ${setting ?: "omitted"}"
                    if (setting == null) args.remove("sound") else args.put("sound", setting)
                    val sent = JSONObject(rpc.smokeCall("send_notification", args.put("message", message)))
                    check(sent.get("id") == id && sent.get("posted") == true)
                    await("task key") { keys().any { it.startsWith(prefix) } }
                    val current = keys().single { it.startsWith(prefix) }; check(key == null || key == current); key = current
                    await(message) { shell("cmd notification get $current").lineSequence().any { it.trim() == "android.text=String ($message)" } }
                    val notification = manager.activeNotifications.single { it.key == current }.notification
                    check(notification.channelId == channel && notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT) == message)
                    @Suppress("DEPRECATION") val observed = notification.sound
                    val channelSound = manager.getNotificationChannel(notification.channelId).sound
                    check(observed == (if (setting == true) android.provider.Settings.System.DEFAULT_NOTIFICATION_URI else null)) { "Notification.sound=$observed" }
                    check(channelSound == before) { "Channel sound changed: $before -> $channelSound" }
                    trace.put(JSONObject().put("sound", setting ?: "omitted").put("notificationSoundUri", observed?.toString() ?: JSONObject.NULL)
                        .put("channelSoundUri", channelSound?.toString() ?: JSONObject.NULL))
                }
                return JSONObject().put("systemKey", key).put("channelId", channel).put("channelSoundBefore", before?.toString() ?: JSONObject.NULL).put("updates", trace).toString()
            } finally {
                if (keys().any { it.startsWith(prefix) }) rpc.smokeCall("notification_remove", JSONObject().put("id", id))
                rpc.smokeCall("notification_channel_delete", JSONObject().put("id", channel))
            }
        }
        if (scenario == "notification-vibrate") {
            val manager = c.getSystemService(NotificationManager::class.java)
            val channel = "oc-vib-$suffix"; val id = "oc-vibration-check"; val prefix = "0|${c.packageName}|2|$id|"
            val args = JSONObject().put("channel", channel).put("id", id).put("title", title).put("message", body)
            check(keys().none { it.startsWith(prefix) })
            rpc.smokeCall("notification_channel_create", JSONObject().put("id", channel).put("name", "Synthetic vibration check"))
            try {
                val initial = manager.getNotificationChannel(channel)
                for (bad in listOf("500,1000,200", JSONArray(), JSONArray("[-1]"), JSONArray("[1.5]"), JSONArray("[60000,1]"))) {
                    check(rpc.smokeCall("send_notification", args.put("vibrate", bad), expectError = true).startsWith("vibrate "))
                }
                check(keys().none { it.startsWith(prefix) })
                val trace = JSONArray(); var key: String? = null
                for (specified in listOf(true, false)) {
                    val message = if (specified) "Pattern set" else "Pattern omitted"
                    if (specified) args.put("vibrate", JSONArray("[500,1000,200]")) else args.remove("vibrate")
                    val sent = JSONObject(rpc.smokeCall("send_notification", args.put("message", message)))
                    check(sent.get("id") == id && sent.get("posted") == true)
                    await("task key") { keys().any { it.startsWith(prefix) } }
                    val current = keys().single { it.startsWith(prefix) }; check(key == null || key == current); key = current
                    await(message) { shell("cmd notification get $current").lineSequence().any { it.trim() == "android.text=String ($message)" } }
                    val notification = manager.activeNotifications.single { it.key == current }.notification
                    check(notification.channelId == channel && notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT) == message)
                    @Suppress("DEPRECATION") val observed = notification.vibrate
                    check(if (specified) observed.contentEquals(longArrayOf(0, 500, 1000, 200)) else observed == null) { "Notification.vibrate=${observed.contentToString()}" }
                    val config = manager.getNotificationChannel(notification.channelId)
                    check(config.shouldVibrate() == initial.shouldVibrate() && config.vibrationPattern.contentEquals(initial.vibrationPattern))
                    trace.put(JSONObject().put("message", message).put("notificationPatternMs", observed?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                        .put("channelVibrates", config.shouldVibrate()).put("channelPatternMs", config.vibrationPattern?.let { JSONArray(it.toList()) } ?: JSONObject.NULL))
                }
                return JSONObject().put("systemKey", key).put("channelId", channel).put("updates", trace).toString()
            } finally {
                if (keys().any { it.startsWith(prefix) }) rpc.smokeCall("notification_remove", JSONObject().put("id", id))
                rpc.smokeCall("notification_channel_delete", JSONObject().put("id", channel))
            }
        }
        if (scenario == "notification-priority") {
            val manager = c.getSystemService(NotificationManager::class.java)
            val initial = manager.getNotificationChannel("tasks")
            check(initial == null || !initial.hasUserSetImportance()) { "Requires a disposable guest with no user importance override" }
            val trace = JSONArray(); val ids = mutableListOf<String>()
            val custom = "oc-priority-control-$suffix"
            try {
                rpc.smokeCall("notification_channel_create", JSONObject().put("id", custom).put("name", "Priority control"))
                for (p in listOf("high", "low", null, "max", "min")) {
                    val channel = if (p == "max") custom else "tasks"
                    val before = manager.getNotificationChannel(channel)?.importance
                    val id = "oc-priority-${p ?: "omitted"}-$suffix"; val args = JSONObject().put("title", title).put("message", body).put("id", id)
                    if (p != null) args.put("priority", p)
                    if (channel != "tasks") args.put("channel", channel)
                    val sent = JSONObject(rpc.smokeCall("send_notification", args)); check(sent.get("id") == id && sent.get("posted") == true); ids.add(id)
                    val prefix = "0|${c.packageName}|2|$id|"
                    await("system key for $id") { keys().any { it.startsWith(prefix) } }
                    val key = keys().single { it.startsWith(prefix) }; val record = shell("cmd notification get $key")
                    val actualChannel = Regex("Notification\\(channel=([^\\s)]+)").find(record)?.groupValues?.get(1)
                    check(actualChannel == channel && record.contains(body)) { "requested=$p systemChannel=$actualChannel" }
                    val actual = manager.getNotificationChannel(channel).importance
                    val requested = when (p) { "high", "max" -> 4; "low" -> 2; "min" -> 1; else -> 3 }
                    val expected = if (channel == custom) 3 else minOf(before ?: requested, requested)
                    @Suppress("DEPRECATION") val legacy = manager.activeNotifications.single { it.key == key }.notification.priority
                    check(actual == expected && legacy == when (p) { "high" -> 1; "low" -> -1; "max" -> 2; "min" -> -2; else -> 0 }) { "requested=$p channel=$channel importance=$actual expected=$expected legacy=$legacy" }
                    trace.put(JSONObject().put("request", p ?: "omitted").put("key", key).put("cmdChannel", actualChannel)
                        .put("importanceBefore", before ?: JSONObject.NULL).put("systemImportance", actual).put("notificationPriority", legacy))
                }
                return trace.toString()
            } finally {
                for (id in ids) rpc.smokeCall("notification_remove", JSONObject().put("id", id))
                if (manager.getNotificationChannel(custom) != null) rpc.smokeCall("notification_channel_delete", JSONObject().put("id", custom))
            }
        }
        if (scenario == "notification-icon") {
            val tools = rpc.smokeResult("tools/list").getJSONArray("tools")
            val schema = (0 until tools.length()).map { tools.getJSONObject(it) }.single { it.getString("name") == "send_notification" }
                .getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("icon")
            val choices = schema.getJSONArray("enum"); val names = (0 until choices.length()).map { choices.getString(it) }.sorted()
            check(names.size == 960 && names.distinct() == names && "server" in names && schema.getString("default") == "server")
            File(c.cacheDir, "smoke-notification-icons.txt").writeText(names.joinToString("\n", postfix = "\n"))
            val upstream = names.filterNot { it == "server" }.joinToString("\n", postfix = "\n")
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(upstream.toByteArray()).joinToString("") { "%02x".format(it) }
            check(digest == "764a50300a9bb3a1ec2713110fb8957c63ecdfa8bcb9d1c617ea9275c77df81c") { digest }
            val legacy = setOf("server", "event_note", "done", "warning")
            val id = "oc-icon-$suffix"; val prefix = "0|${c.packageName}|2|$id|"
            val args = JSONObject().put("id", id).put("title", title).put("message", body)
            val error = rpc.smokeCall("send_notification", args.put("icon", "not-in-the-apk"), expectError = true)
            check(error.startsWith("Unknown notification icon") && keys().none { it.startsWith(prefix) })
            val trace = JSONArray()
            var key: String? = null
            try {
                for ((i, name) in listOf(null, "event_note", "done", "warning", "server", "3d_rotation", "flight_takeoff", "wifi", "zoom_out_map", null).withIndex()) {
                    if (name == null) args.remove("icon") else args.put("icon", name)
                    val message = "Synthetic icon revision $i"
                    val sent = JSONObject(rpc.smokeCall("send_notification", args.put("message", message)))
                    check(sent.get("posted") == true && sent.get("id") == id)
                    await("task icon key") { keys().any { it.startsWith(prefix) } }
                    val current = keys().single { it.startsWith(prefix) }; check(key == null || key == current); key = current
                    var detail = ""
                    await(message) { detail = shell("cmd notification get $current"); detail.lineSequence().any { it.trim() == "android.text=String ($message)" } }
                    val icon = detail.lineSequence().map { it.trim() }.single { it.startsWith("icon=Icon(") }
                    val hex = Regex("\\bid=(0x[0-9a-fA-F]+)\\b").find(icon)!!.groupValues[1]
                    val resource = c.resources.getResourceName(hex.drop(2).toInt(16))
                    val entry = if (name == null || name in legacy) "ic_${name ?: "server"}" else "ic_${name}_black_24dp"
                    check(icon.contains("pkg=${c.packageName} ") && resource == "${c.packageName}:drawable/$entry") { "$icon -> $resource" }
                    trace.put(JSONObject().put("requested", name ?: "omitted").put("systemIcon", icon).put("resource", resource))
                }
                return JSONObject().put("advertisedIcons", names.size).put("upstreamNamesSHA256", digest).put("key", key).put("updates", trace).toString()
            } finally {
                if (keys().any { it.startsWith(prefix) }) rpc.smokeCall("notification_remove", JSONObject().put("id", id))
            }
        }
        if (scenario == "notification-picture") {
            val root = File(c.filesDir, "home/projects").apply { mkdirs() }
            val dir = File(root, "picture-$suffix"); check(dir.mkdir())
            val file = File(dir, "sample.png"); val id = "oc-picture-$suffix"
            val args = JSONObject().put("title", title).put("message", body).put("id", id)
            val prefix = "0|${c.packageName}|2|$id|"
            try {
                val expected = IntArray(64 * 36) { if (it % 64 < 17) 0xffc94420.toInt() else 0xff0088aa.toInt() }
                val bitmap = Bitmap.createBitmap(expected, 64, 36, Bitmap.Config.ARGB_8888)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
                val manager = c.getSystemService(NotificationManager::class.java)
                fun verifyPixels(value: Bitmap): String {
                    check(value.width == 64 && value.height == 36) { "Unexpected dimensions: ${value.width}x${value.height}" }
                    val pixels = IntArray(expected.size)
                    value.getPixels(pixels, 0, 64, 0, 0, 64, 36)
                    check(pixels.contentEquals(expected)) { "System bitmap differs from PNG fixture" }
                    return "64x36; ${pixels.size} pixels match PNG"
                }
                for (bad in listOf(file.absolutePath, "../sample.png")) {
                    val error = rpc.smokeCall("send_notification", args.put("imagePath", bad), expectError = true)
                    check(error == "Expected a relative workspace file path") { error }
                }
                check(keys().none { it.startsWith(prefix) })
                val trace = JSONArray()
                for (picture in listOf(true, false)) {
                    val message = if (picture) "Synthetic PNG" else "Text-only update"
                    if (picture) args.put("imagePath", "${dir.name}/sample.png") else args.remove("imagePath")
                    val sent = JSONObject(rpc.smokeCall("send_notification", args.put("message", message))); check(sent.get("posted") == true && sent.get("id") == id)
                    await("picture fixture key") { keys().any { it.startsWith(prefix) } }; val key = keys().single { it.startsWith(prefix) }
                    var record = ""
                    await("new picture/text record") { record = shell("cmd notification get $key"); record.lineSequence().any { it.trim() == "android.text=String ($message)" } }
                    val extras = record.lineSequence().map { it.trim() }.toList()
                    val style = extras.single { it.startsWith("android.template=") }
                    val image = extras.singleOrNull { it.startsWith("android.picture=") }
                    check(style.contains(if (picture) "\$BigPictureStyle" else "\$BigTextStyle")) { style }
                    if (picture) check(image != null && "Bitmap" in image && "64x36" in image) { "Picture field: $image" }
                    else check(image == null && extras.any { it == "android.bigText=String ($message)" })
                    val active = manager.activeNotifications.single { it.key == key }.notification
                    check(active.extras.getCharSequence(android.app.Notification.EXTRA_TEXT) == message)
                    val large = active.getLargeIcon()
                    @Suppress("DEPRECATION") val expanded = active.extras.getParcelable<Bitmap>(android.app.Notification.EXTRA_PICTURE)
                    check((large != null) == picture && (expanded != null) == picture && active.extras.containsKey(android.app.Notification.EXTRA_PICTURE) == picture)
                    val largePixels = large?.let { verifyPixels((it.loadDrawable(c) as android.graphics.drawable.BitmapDrawable).bitmap) }
                    trace.put(JSONObject().put("key", key).put("style", style).put("picture", image ?: JSONObject.NULL)
                        .put("getLargeIcon", largePixels ?: JSONObject.NULL).put("activePicture", expanded?.let(::verifyPixels) ?: JSONObject.NULL))
                }
                return trace.toString()
            } finally {
                if (keys().any { it.startsWith(prefix) }) rpc.smokeCall("notification_remove", JSONObject().put("id", id))
                file.delete(); dir.delete()
            }
        }
        if (channelRun != null) {
            val manager = c.getSystemService(NotificationManager::class.java)
            val id = "oc-restore-$channelRun"
            val args = JSONObject().put("id", id).put("name", "OpenCode restore check")
            fun create() = JSONObject(rpc.smokeCall("notification_channel_create", args))
            if (scenario == "channel-restore-prepare") {
                check(manager.getNotificationChannel(id) == null)
                val receipt = create(); val initial = checkNotNull(manager.getNotificationChannel(id))
                check(receipt.getInt("importance") == NotificationManager.IMPORTANCE_DEFAULT && initial.importance == receipt.getInt("importance") && !initial.hasUserSetImportance())
                return JSONObject().put("create", receipt).put("systemImportance", initial.importance).put("userSet", initial.hasUserSetImportance()).toString()
            }
            // Between phases use the actual Android channel Settings UI, never an API setter.
            val lowered = checkNotNull(manager.getNotificationChannel(id))
            check(lowered.hasUserSetImportance() && lowered.importance in NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_LOW) { "Change this channel to Silent in Settings first: $lowered" }
            val deleted = JSONObject(rpc.smokeCall("notification_channel_delete", JSONObject().put("id", id)))
            check(deleted.get("deleted") == true && deleted.getString("id") == id && manager.getNotificationChannel(id) == null)
            val receipt = create(); val restored = checkNotNull(manager.getNotificationChannel(id))
            check(restored.hasUserSetImportance() && restored.importance == lowered.importance && receipt.getInt("importance") == restored.importance) { "Before=${lowered.importance}; after=$restored; MCP=$receipt" }
            return JSONObject().put("id", id).put("systemBeforeDelete", lowered.importance).put("userSetBeforeDelete", lowered.hasUserSetImportance()).put("delete", deleted)
                .put("systemAfterDelete", JSONObject.NULL).put("recreate", receipt).put("systemAfterRecreate", restored.importance).put("userSetAfterRecreate", restored.hasUserSetImportance()).toString()
        }
        if (scenario == "notification-channel") {
            val manager = c.getSystemService(NotificationManager::class.java)
            val custom = "oc-builds-$suffix"; val missing = "oc-missing-$suffix"
            fun taskKeys() = keys().filter { it.startsWith("0|${c.packageName}|2|") }
            check(rows().isEmpty() && taskKeys().isEmpty() && manager.getNotificationChannel(custom) == null)
            fun listedChannel(): JSONObject {
                val all = JSONArray(rpc.smokeCall("notification_channels"))
                return (0 until all.length()).map { all.getJSONObject(it) }.single { it.getString("id") == custom }
            }
            try {
                val history = JSONArray()
                for (name in listOf("构建通知", "构建完成提醒")) {
                    val receipt = JSONObject(rpc.smokeCall("notification_channel_create", JSONObject().put("id", custom).put("name", name)))
                    val listed = listedChannel()
                    for (row in listOf(receipt, listed)) check(row.get("id") == custom && row.get("name") == name && row.get("importance") == NotificationManager.IMPORTANCE_DEFAULT) { row.toString() }
                    history.put(listed)
                }
                for (id in listOf("server", "tasks")) {
                    val error = rpc.smokeCall("notification_channel_create", JSONObject().put("id", id).put("name", "替换"), expectError = true)
                    check(error == "Reserved notification channel id") { error }
                }
                val args = JSONObject().put("title", title).put("message", body)
                val error = rpc.smokeCall("send_notification", args.put("channel", missing), expectError = true)
                val count = taskKeys().size
                check(error == "Unknown OpenCode notification channel" && manager.getNotificationChannel(missing) == null && count == 0 && rows().isEmpty())
                val reserved = rpc.smokeCall("send_notification", args.put("channel", "server"), expectError = true)
                check(reserved == "The server channel is reserved for the local service" && taskKeys().isEmpty())
                val trace = JSONArray()
                for (requested in listOf(custom, null)) {
                    if (requested == null) args.remove("channel") else args.put("channel", requested)
                    val sent = JSONObject(rpc.smokeCall("send_notification", args)); check(sent.get("posted") == true)
                    val id = sent.getString("id"); val prefix = "0|${c.packageName}|2|$id|"
                    await("posted task key") { taskKeys().any { it.startsWith(prefix) } }
                    val key = taskKeys().single { it.startsWith(prefix) }
                    val record = shell("cmd notification get $key")
                    val actual = Regex("Notification\\(channel=([^\\s)]+)").find(record)?.groupValues?.get(1)
                    check(actual == (requested ?: "tasks") && record.contains(body)) { "requested=$requested actual=$actual" }
                    trace.put(JSONObject().put("requested", requested ?: "omitted").put("systemKey", key).put("systemChannel", actual))
                    check(JSONObject(rpc.smokeCall("notification_remove", JSONObject().put("id", id))).get("removed") == true)
                    await("task removed") { key !in keys() && rows().isEmpty() }
                }
                fun delete(id: String, expectError: Boolean = false) = rpc.smokeCall("notification_channel_delete", JSONObject().put("id", id), expectError)
                val systemBefore = checkNotNull(manager.getNotificationChannel(custom)).id
                val protected = JSONObject()
                for (id in listOf("tasks", "server", "miscellaneous")) {
                    val refusal = delete(id, expectError = true); check(refusal == "Reserved notification channel id") { refusal }
                    protected.put(id, refusal)
                }
                val deletion = JSONObject(delete(custom)); check(deletion.get("deleted") == true && deletion.get("id") == custom)
                val systemAfter = manager.getNotificationChannel(custom)
                val active = manager.notificationChannels.map { it.id }
                check(systemAfter == null && custom !in active && "tasks" in active && "server" in active)
                val channels = JSONArray(rpc.smokeCall("notification_channels")); check((0 until channels.length()).none { channels.getJSONObject(it).getString("id") == custom })
                check(delete(custom, expectError = true) == "Unknown OpenCode notification channel")
                val after = rpc.smokeCall("send_notification", args.put("channel", custom), expectError = true)
                val tasksAfter = taskKeys().size
                check(after == "Unknown OpenCode notification channel" && tasksAfter == 0 && rows().isEmpty() && manager.getNotificationChannel(custom) == null)
                return JSONObject().put("channelListAfterEachCreate", history).put("posts", trace).put("unknownError", error).put("systemTasksAfterRejection", count).put("reservedError", reserved)
                    .put("systemBeforeDelete", systemBefore).put("delete", deletion).put("systemAfterDelete", systemAfter?.id ?: JSONObject.NULL).put("protectedDeletes", protected)
                    .put("remainingSystemChannels", JSONArray(active)).put("sendAfterDelete", after).put("systemTasksAfterDeletedChannelSend", tasksAfter).toString()
            } finally {
                // Failure cleanup only; never substitute a direct Android deletion for the MCP test.
                if (manager.getNotificationChannel(custom) != null) rpc.smokeCall("notification_channel_delete", JSONObject().put("id", custom))
            }
        }
        if (scenario == "notification-alert-once" || scenario == "notification-ongoing") {
            val field = if (scenario == "notification-ongoing") "ongoing" else "alertOnce"
            val expectedFlag = if (field == "ongoing") "ONGOING_EVENT" else "ONLY_ALERT_ONCE"
            val id = "oc-$field-$suffix"
            val prefix = "0|${c.packageName}|2|$id|"
            val args = JSONObject().put("title", title).put("message", body)
            val report = JSONObject()
            if (field == "ongoing") {
                fun taskKeys() = keys().filter { it.startsWith("0|${c.packageName}|2|") }
                check(taskKeys().isEmpty() && rows().isEmpty())
                val error = rpc.smokeCall("send_notification", args.put(field, true), expectError = true)
                check(error == "ongoing:true requires an explicit id") { error }
                val count = taskKeys().size; check(count == 0 && rows().isEmpty())
                report.put("missingIdError", error).put("tasksAfterRejection", count)
            }
            args.put("id", id)
            for (bad in listOf("true", 1, JSONObject.NULL)) {
                val error = rpc.smokeCall("send_notification", args.put(field, bad), expectError = true)
                check(error == "$field must be a boolean") { error }
            }
            check(keys().none { it.startsWith(prefix) })
            val trace = JSONArray()
            var key: String? = null
            for ((i, setting) in listOf(null, true, null, false).withIndex()) {
                val message = "Synthetic $field revision $i"
                args.put("message", message)
                if (setting == null) args.remove(field) else args.put(field, setting)
                val sent = JSONObject(rpc.smokeCall("send_notification", args))
                check(sent.get("posted") == true && sent.get("id") == id)
                await("system task: $id") { keys().any { it.startsWith(prefix) } }
                val current = keys().single { it.startsWith(prefix) }; check(key == null || key == current); key = current
                var record = ""
                await("system text: $message") {
                    record = shell("cmd notification get $current")
                    record.lineSequence().any { it.trim() == "android.text=String ($message)" }
                }
                val flags = Regex("\\bflags=([^\\s)]+)").find(record.lineSequence().first { it.contains("Notification(channel=tasks") })!!.groupValues[1]
                check((expectedFlag in flags) == (setting == true) && ("AUTO_CANCEL" in flags) == !(field == "ongoing" && setting == true)) { "revision $i: $flags" }
                trace.put(JSONObject().put("revision", i).put(field, setting ?: "omitted").put("systemFlags", flags))
            }
            val removed = JSONObject(rpc.smokeCall("notification_remove", JSONObject().put("id", id)))
            check(removed.get("removed") == true)
            await("flag fixture removed") { key !in keys() && rows().none { it.getString("id") == id } }
            return report.put("systemKey", key).put("updates", trace).put("removal", removed).toString()
        }
        if (scenario == "notification-group") {
            val group = "oc-builds"
            val ids = listOf("oc-group-a", "oc-group-b", "oc-solo")
            check(rows().isEmpty()) { "Requires clean task list" }
            for (bad in listOf(1, JSONObject.NULL, " ", "x".repeat(129))) {
                val result = rpc.smokeCall("send_notification", JSONObject().put("title", title).put("message", body).put("group", bad), expectError = true)
                check(result.startsWith("group must be a nonblank string")) { result }
            }
            for (id in ids) {
                val args = JSONObject().put("id", id).put("title", id).put("message", body)
                if (id != ids.last()) args.put("group", group)
                val sent = JSONObject(rpc.smokeCall("send_notification", args))
                check(sent.get("posted") == true && sent.get("id") == id)
            }
            await("three distinct task notifications") { rows().map { it.getString("id") }.toSet() == ids.toSet() }
            val result = JSONArray()
            for (id in ids) {
                val prefix = "0|${c.packageName}|2|$id|"
                await("system key for $id") { keys().any { it.startsWith(prefix) } }
                val key = keys().single { it.startsWith(prefix) }
                val record = shell("cmd notification get $key")
                val groupKey = record.lineSequence().map { it.trim() }.single { it.startsWith("groupKey=") }.substringAfter('=')
                val expected = if (id == ids.last()) key else "0|${c.packageName}|g:$group"
                check(groupKey == expected) { "$id: system groupKey=$groupKey, expected $expected" }
                result.put(JSONObject().put("id", id).put("systemKey", key).put("systemGroupKey", groupKey))
            }
            for (id in ids) check(JSONObject(rpc.smokeCall("notification_remove", JSONObject().put("id", id))).get("removed") == true)
            await("group fixture removed") { rows().isEmpty() && keys().none { it.startsWith("0|${c.packageName}|2|") } }
            return result.toString()
        }
        shell("cmd notification post -t OcControl $control Synthetic-control")
        fun controlKey() = keys().singleOrNull { it.contains("|com.android.shell|") && it.contains("|$control|") }
        await("foreign control registered") { controlKey() != null }
        val foreign = controlKey()!!
        val before = keys()
        check(rows().none { it.optString("title") == title || it.optString("id") == control })
        val sent = JSONObject(rpc.smokeCall("send_notification", JSONObject().put("title", title).put("message", body)))
        val id = sent.getString("id"); check(sent.get("posted") == true && UUID.fromString(id).toString() == id)
        val prefix = "0|${c.packageName}|2|$id|"
        check(before.none { it.startsWith(prefix) })
        await("task registered") { keys().any { it.startsWith(prefix) } }
        val key = keys().single { it.startsWith(prefix) }
        val detail = shell("cmd notification get $key")
        check(detail.contains(title) && detail.contains(body) && detail.contains("channel=tasks")) { "System record did not match synthetic title, text and channel" }
        val listed = rows(); val row = listed.single { it.getString("id") == id }
        check(row.getString("title") == title && row.getLong("postedAt") > 0)
        check(listed.none { it.optString("id") == control || it.optString("title") == "OcControl" })
        val outOfScope = rpc.smokeCall("notification_remove", JSONObject().put("id", control), expectError = true)
        check(outOfScope == "No such OpenCode task notification" && foreign in keys())
        val removed = JSONObject(rpc.smokeCall("notification_remove", JSONObject().put("id", id)))
        check(removed.get("removed") == true)
        await("task removed") { key !in keys() && rows().none { it.getString("id") == id } }
        check(foreign in keys())
        val stable = "oc-build-42"
        fun taskKeys() = keys().filter { it.startsWith("0|${c.packageName}|2|") }
        val initial = taskKeys(); check(initial.isEmpty() && rows().isEmpty()) { "Expected clean task list" }
        for (bad in listOf(42, JSONObject.NULL, " ", "x".repeat(129))) {
            val error = rpc.smokeCall("send_notification", JSONObject().put("title", title).put("message", body).put("id", bad), expectError = true)
            check(error.startsWith("id must be a nonblank string")) { error }
        }
        fun postAndRead(title: String, message: String): JSONObject {
            val receipt = JSONObject(rpc.smokeCall("send_notification", JSONObject().put("id", stable).put("title", title).put("message", message)))
            check(receipt.get("posted") == true && receipt.get("id") == stable)
            await("listed title: $title") { rows().singleOrNull()?.let { it.getString("id") == stable && it.getString("title") == title } == true }
            await("system task key: $title") { taskKeys().any { it.startsWith("0|${c.packageName}|2|$stable|") } }
            val active = taskKeys(); val systemKey = active.single()
            check(systemKey.startsWith("0|${c.packageName}|2|$stable|"))
            val text = "android.text=String ($message)"
            await("system body: $title") {
                val record = shell("cmd notification get $systemKey")
                record.contains(title) && record.contains("channel=tasks") && record.lineSequence().any { it.trim() == text }
            }
            return JSONObject().put("receipt", receipt).put("taskCount", active.size).put("systemKey", systemKey).put("systemText", text)
        }
        val first = postAndRead("合成构建：进行中", "第一阶段：正在编译。")
        val updated = postAndRead("合成构建：已完成", "第二阶段：编译成功。")
        check(first.getString("systemKey") == updated.getString("systemKey"))
        val cleared = JSONObject(rpc.smokeCall("notification_remove", JSONObject().put("id", stable)))
        check(cleared.get("removed") == true)
        await("updated task removed") { taskKeys().isEmpty() && rows().isEmpty() }; check(foreign in keys())
        return JSONObject().put("sent", sent).put("listed", row).put("systemKey", key).put("systemBody", body)
            .put("systemPresentBefore", false).put("systemPresentAfterPost", true).put("removed", removed)
            .put("systemPresentAfterRemove", false).put("listedAfterRemove", false).put("foreignKey", foreign)
            .put("foreignRemoveError", outOfScope).put("foreignStillActive", true)
            .put("stableIdTest", JSONObject().put("initialCount", initial.size).put("first", first).put("updated", updated)
                .put("removed", cleared).put("finalCount", taskKeys().size)).toString()
    } finally { rpc.stop() }
}
