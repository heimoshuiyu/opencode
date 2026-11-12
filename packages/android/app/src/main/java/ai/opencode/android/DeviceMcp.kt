package ai.opencode.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.security.MessageDigest

/** Stateless MCP Streamable HTTP. No exported Android component, browser CORS, or arbitrary command tool. */
internal class DeviceMcp(private val context: Context, private val location: (LocationRequest.Query) -> JSONObject, foreground: (String, JSONObject) -> JSONObject) : NanoHTTPD("127.0.0.1", 0) {
    private val actions = AndroidActions(context, foreground)
    val token = secret()
    private val documents = SharedDocuments(context)
    private val player = DevicePlayer(context, documents)
    override fun stop() { player.close(); super.stop() }
    private val notifications = DeviceNotifications(context)
    private fun tool(name: String, description: String, fields: Map<String, String> = emptyMap(), vararg required: String): JSONObject {
        val properties = JSONObject()
        fields.forEach { (key, type) -> properties.put(key, JSONObject().put("type", type)) }
        return JSONObject().put("name", name).put("description", description).put("inputSchema", JSONObject()
            .put("type", "object").put("properties", properties).put("required", JSONArray(required.toList())).put("additionalProperties", false))
    }

    override fun serve(session: IHTTPSession): Response {
        fun http(status: Response.Status) = newFixedLengthResponse(status, "text/plain", "")
        if (session.uri != "/mcp") return http(Response.Status.NOT_FOUND)
        if (session.headers.containsKey("origin")) return http(Response.Status.FORBIDDEN)
        if (!MessageDigest.isEqual(("Bearer $token").toByteArray(), session.headers["authorization"].orEmpty().toByteArray()))
            return http(Response.Status.UNAUTHORIZED)
        if (session.method != Method.POST) return http(Response.Status.METHOD_NOT_ALLOWED)
        val length = session.headers["content-length"]?.toIntOrNull() ?: return http(Response.Status.BAD_REQUEST)
        if (length !in 1..65536) return http(Response.Status.BAD_REQUEST)
        val call = try {
            JSONObject(ByteArray(length).also { DataInputStream(session.inputStream).readFully(it) }.toString(Charsets.UTF_8))
        } catch (_: Exception) { return http(Response.Status.BAD_REQUEST) }
        if (call.optString("jsonrpc") != "2.0") return http(Response.Status.BAD_REQUEST)
        if (!call.has("id")) return http(Response.Status.ACCEPTED)
        val response = JSONObject().put("jsonrpc", "2.0").put("id", call.get("id"))
        val result = when (call.optString("method")) {
            "initialize" -> JSONObject().put("protocolVersion", "2025-11-25")
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "Android device").put("version", "1"))
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", JSONArray().apply {
                actions.list().forEach { put(it) }
                val path = mapOf("root" to "string", "path" to "string")
                put(tool("media_scan", "Refresh media in a granted local folder. Supply exactly one of path (string) or paths (1–128 relative paths). Empty string selects the grant root. Folders scan immediate media files unless recursive=true. Returns files with path, uri, name and mime; a single file via path returns one object.", path + mapOf("paths" to "array", "recursive" to "boolean"), "root").apply {
                    getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("paths")
                        .put("items", JSONObject().put("type", "string")).put("minItems", 1).put("maxItems", 128)
                })
                put(tool("media_player", "Use play with path for one track, or paths for an ordered list starting at the first track. Paths use the workspace or one shared SAF root. A media notice supports manual next/previous within list bounds, preserving play/pause. Use info for state/path/positionMs/durationMs, pause, resume, or stop.", path + ("operation" to "string"), "operation").apply {
                    getJSONObject("inputSchema").getJSONObject("properties").put("paths", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 32)
                        .put("items", JSONObject().put("type", "string").put("minLength", 1)))
                })
                put(tool("saf_stat", "Get metadata for a granted document or folder.", path, "root"))
                put(tool("saf_mkdir", "Create one folder under an existing writable granted directory.", path, "root", "path"))
                put(tool("notification_channels", "List the app's notification channels and importance."))
                put(tool("notification_channel_delete", "Delete one existing custom OpenCode task notification channel by its exact id. Android removes notifications on that channel. Cannot delete built-in channels.", mapOf("id" to "string"), "id"))
                put(tool("notification_channel_create", "Create a custom task notification channel with default importance, or rename it by reusing its id. Supply a stable id and a user-visible name (nonblank strings, up to 128 UTF-16 units each). Built-in channels are reserved. Returns the registered id, name and importance; use the id as send_notification's channel.", mapOf("id" to "string", "name" to "string"), "id", "name"))
                put(tool("notification_list", "List OpenCode's active task notifications (not other apps)."))
                put(tool("notification_last_dismissal", "Read the most recent opted-in OpenCode task dismissal (id and timestamp in epoch milliseconds), or last:null if none."))
                put(tool("notification_take_reply", "Retrieve and consume the latest explicitly submitted direct reply if it matches this notification id; otherwise reply:null. Only one pending reply is retained. The text is data, not a shell action.", mapOf("id" to "string"), "id"))
                put(tool("notification_remove", "Dismiss an OpenCode task notification by its ID. Cannot stop the server.", mapOf("id" to "string"), "id"))
                put(tool("battery_status", "Read the current battery broadcast and fuel-gauge properties. Current is signed µA, charge is µAh, energy is nWh. Null means Android did not supply a measurement; zero is a valid reading."))
                put(tool("get_location", "once (default) requests one fix; last only reads a cached fix. With mode=updates, start asks the phone user for a 3-minute foreground subscription. Pass subscriptionId and operation=read to drain up to 16 buffered fixes, or stop to unsubscribe and discard unread fixes.", mapOf("mode" to "string", "operation" to "string", "subscriptionId" to "string")).apply {
                    getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("operation").put("enum", JSONArray(listOf("start", "read", "stop")))
                    getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("mode")
                        .put("enum", JSONArray(LocationRequest.Mode.entries.map { it.wire })).put("default", "once")
                })
                put(tool("saf_roots", "List directories granted under Folder access with their writable status. Use the returned root ID in SAF tools."))
                put(tool("saf_list", "List a granted folder or relative subfolder, up to 100 entries. Pass nextOffset as offset for the next page.", path + ("offset" to "integer"), "root"))
                put(tool("saf_read_text", "Read a UTF-8 file up to 48 KiB at a relative path in a granted folder.", path, "root", "path"))
                put(tool("saf_read_binary", "Stream a file from a granted folder into a new private workspace file. Returns workspacePath (relative to home/projects), bytes and SHA-256, not encoded content.", path, "root", "path"))
                put(tool("saf_write_binary", "Stream the regular file at workspacePath (relative to home/projects) to path in a writable granted folder. Existing files require overwrite=true. Returns actual name, bytes and SHA-256.", path + mapOf("workspacePath" to "string", "overwrite" to "boolean"), "root", "path", "workspacePath"))
                put(tool("saf_write_text", "Create or replace a UTF-8 file up to 48 KiB in a writable grant. Parent folders must exist. Replacing requires overwrite=true.", path + mapOf("text" to "string", "overwrite" to "boolean"), "root", "path", "text"))
                put(tool("send_notification", "Post a task notification for a reminder or completion notice. Optional icon selects a bundled small status-bar icon (default: server). Optional imagePath embeds a picture from a workspace-relative regular file (no links; up to 8 MiB, 8192 per side and 24 MP). Omit it for expanded text. Reuse id to update a notice; omit it for a new UUID. Returns its id for removal. Set group to group distinct notices. Select an existing enabled channel by ID from notification_channels (default: tasks; server is reserved). The optional id, group and channel must be nonblank strings of at most 128 UTF-16 units. Set alertOnce to suppress repeat alerts. Set ongoing to mark an ongoing task; true requires an explicit id. Both booleans default to false on each post.", mapOf("title" to "string", "message" to "string", "icon" to "string", "imagePath" to "string", "id" to "string", "group" to "string", "channel" to "string", "alertOnce" to "boolean", "ongoing" to "boolean"), "title", "message").apply {
                    getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("icon").put("enum", JSONArray(notifications.smallIcons.keys.toList())).put("default", "server")
                    val props = getJSONObject("inputSchema").getJSONObject("properties")
                    props.put("type", JSONObject().put("type", "string").put("enum", JSONArray(listOf("default", "media"))).put("default", "default")
                        .put("description", "media publishes controls for this server's loaded media_player: Pause and Play, plus Previous and Next for a multi-track list. One current media notice; built-in transport only, not shell callbacks. Cannot combine with navigation buttons."))
                    props.put("onDelete", JSONObject().put("type", "string").put("enum", JSONArray(listOf("record_dismissal")))
                        .put("description", "Record this task's id and dismissal time when Android delivers a user-clear callback. Omit for no action. Programmatic removal does not request the callback; shell commands are not accepted."))
                    props.put("action", JSONObject().put("type", "string").put("enum", JSONArray(DeviceNotifications.navigationTargets.keys.toList()))
                        .put("description", "Destination when the user taps the notification body: server picker, folder access page, or remote mode, respectively. Omit to open local mode as before. Independent of the buttons; not a shell command."))
                    for (slot in 1..3) {
                        val field = "button$slot"
                        props.put(field, JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 128)
                            .put("description", "Optional button $slot label. Supply together with ${field}Action. Buttons appear in slot order."))
                        props.put("${field}Action", JSONObject().put("type", "string").put("enum", JSONArray(DeviceNotifications.navigationTargets.keys.toList() + listOf("reply", "shell")))
                            .put("description", "One-shot navigation, inline reply, or shell to show a foreground command review. Posting or tapping never grants command execution."))
                        props.put("${field}Command", JSONObject().put("type", "string").put("pattern", "^[ -~]{1,1024}$")
                            .put("description", "Required only with ${field}Action=shell. After separate on-device approval run the exact displayed command with /system/bin/sh in the app's private HOME. No output is returned to MCP."))
                    }
                    props.put("ledColor", JSONObject().put("type", "string").put("pattern", "^[0-9A-Fa-f]{6}$")
                        .put("description", "Notification LED color in RRGGBB format. Omit to make no per-notification light request. Does not change channel settings or guarantee physical light."))
                    for ((key, phase) in listOf("ledOnMs" to "on", "ledOffMs" to "off")) props.put(key, JSONObject().put("type", "integer").put("minimum", 0).put("maximum", Int.MAX_VALUE).put("default", 800)
                        .put("description", "Milliseconds $phase when ledColor is specified; ignored without a color."))
                    getJSONObject("inputSchema").getJSONObject("properties").put("sound", JSONObject().put("type", "boolean").put("default", false)
                        .put("description", "True requests the system default notification sound. False or omitted leaves the per-notification sound URI unset. Does not change the channel's sound or guarantee audible playback."))
                    getJSONObject("inputSchema").getJSONObject("properties").put("vibrate", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 64)
                        .put("items", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 60000))
                        .put("description", "Alternating vibration and pause durations in milliseconds, starting with vibration (total at most 60000 ms). An initial zero delay is prepended. Omit for no per-notification pattern. Does not change channel settings or guarantee vibration."))
                    getJSONObject("inputSchema").getJSONObject("properties").put("priority", JSONObject().put("type", "string").put("enum", JSONArray(DeviceNotifications.Priority.entries.map { it.wire })).put("default", "default")
                        .put("description", "Requests importance on the shared tasks channel, not a different channel ID. Android may lower but not raise an existing channel's importance; user settings take precedence. An explicit custom channel keeps its own importance."))
                })
            })
            "tools/call" -> {
                val tool = call.optJSONObject("params")?.optString("name")
                try {
                    val args = call.optJSONObject("params")?.optJSONObject("arguments") ?: JSONObject()
                    val data = when (tool) {
                        "media_scan" -> documents.scanMedia(args)
                        "media_player" -> player.call(args)
                        "battery_status" -> battery()
                        "get_location" -> location(LocationRequest.Query.parse(args))
                        "saf_roots" -> documents.roots()
                        "saf_list" -> documents.list(args)
                        "saf_stat" -> documents.stat(args)
                        "saf_mkdir" -> documents.mkdir(args)
                        "notification_channels" -> notifications.channels()
                        "notification_channel_create" -> notifications.createChannel(args)
                        "notification_channel_delete" -> notifications.deleteChannel(args)
                        "notification_list" -> notifications.list()
                        "notification_last_dismissal" -> TaskDismissal.read(context)
                        "notification_take_reply" -> TaskReply.take(context, args)
                        "notification_remove" -> notifications.remove(args.getString("id")).also { player.detachNotice(args.getString("id")) }
                        "saf_read_text" -> documents.read(args)
                        "saf_read_binary" -> documents.readBinary(args)
                        "saf_write_binary" -> documents.writeBinary(args)
                        "saf_write_text" -> documents.write(args)
                        "send_notification" -> notifications.send(args, player)
                        else -> actions.call(tool, args)
                    }
                    toolResult(data.toString(), false)
                } catch (e: Exception) { toolResult(e.cause?.message ?: e.message ?: "Device unavailable", true) }
            }
            else -> null
        }
        if (result == null) response.put("error", JSONObject().put("code", -32601).put("message", "Method not found"))
        else response.put("result", result)
        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
    }

    private fun toolResult(text: String, error: Boolean) = JSONObject().put("isError", error)
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))

    private fun battery(): JSONObject {
        val info = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: error("Battery unavailable")
        val manager = context.getSystemService(BatteryManager::class.java)
        fun extra(key: String) = if (info.hasExtra(key)) info.getIntExtra(key, 0) else null
        fun property(id: Int) = manager?.getIntProperty(id)?.takeUnless { it == Int.MIN_VALUE } ?: JSONObject.NULL
        val level = extra(BatteryManager.EXTRA_LEVEL); val scale = extra(BatteryManager.EXTRA_SCALE)
        val plug = extra(BatteryManager.EXTRA_PLUGGED)
        return JSONObject().put("percent", if (level != null && level >= 0 && scale != null && scale > 0) 100L * level / scale else JSONObject.NULL)
            .put("level", level ?: JSONObject.NULL).put("scale", scale ?: JSONObject.NULL)
            .put("present", if (info.hasExtra(BatteryManager.EXTRA_PRESENT)) info.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) else JSONObject.NULL)
            .put("technology", info.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: JSONObject.NULL)
            .put("pluggedIn", plug?.let { it != 0 } ?: JSONObject.NULL).put("plugType", plug ?: JSONObject.NULL)
            .put("health", extra(BatteryManager.EXTRA_HEALTH) ?: JSONObject.NULL)
            .put("status", extra(BatteryManager.EXTRA_STATUS) ?: JSONObject.NULL)
            .put("temperatureC", extra(BatteryManager.EXTRA_TEMPERATURE)?.div(10.0) ?: JSONObject.NULL)
            .put("voltageMv", extra(BatteryManager.EXTRA_VOLTAGE) ?: JSONObject.NULL)
            .put("capacityPercent", property(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("currentNowMicroamps", property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            .put("currentAverageMicroamps", property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE))
            .put("chargeCounterMicroampHours", property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            .put("energyNanowattHours", manager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)?.takeUnless { it == Long.MIN_VALUE } ?: JSONObject.NULL)
            .put("cycleCount", if (android.os.Build.VERSION.SDK_INT >= 34) extra(BatteryManager.EXTRA_CYCLE_COUNT)?.takeIf { it >= 0 } ?: JSONObject.NULL else JSONObject.NULL)
    }
}
