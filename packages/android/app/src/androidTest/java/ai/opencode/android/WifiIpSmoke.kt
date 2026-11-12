package ai.opencode.android

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Compare foreground MCP results with independent guest interface/Wi-Fi service output. */
internal fun wifiSmoke(test: Instrumentation, scenario: String): String {
    val width = scenario == "wifi-scan-width"
    val centers = scenario == "wifi-scan-centers"
    val names = scenario == "wifi-scan-names"
    val empty = scenario == "wifi-scan-empty"
    val many = scenario == "wifi-scan-many"
    val stamps = scenario == "wifi-stamp-snapshot" || scenario == "wifi-stamp-capture"
    val scan = stamps || many || empty || names || centers || width || scenario == "wifi-scan-capabilities"
    val hidden = when (scenario) { "wifi-visible" -> false; "wifi-hidden" -> true; else -> null }
    fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText().trim() }
    check(shell("getprop ro.boot.qemu") == "1") { "Disposable emulator required" }
    val c = test.targetContext; val wifi = c.applicationContext.getSystemService(WifiManager::class.java)
    check(c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "Grant foreground location through the system UI first" }
    fun wlan(): List<String> {
        val lines = shell("ip -o -4 addr show").lines(); check(lines.any { it.contains(" lo ") && it.contains(" inet ") }) { "Cannot read guest interface addresses" }
        return lines.mapNotNull { Regex("^\\d+:\\s+wlan0\\s+inet\\s+(\\S+)").find(it)?.groupValues?.get(1) }
    }
    check(wifi.isWifiEnabled && (scan || scenario == "wifi-mac" || wlan().isNotEmpty())) { "Test requires virtual Wi-Fi" }
    val a = test.startActivitySync(Intent(c, ServerPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun <T> main(f: () -> T): T = FutureTask<T> { f() }.also { test.runOnMainSync(it) }.get()
    val ui = main { DeviceInteraction(a) }; val rpc = DeviceMcp(c, { error("Unexpected location fix") }, ui::invoke)
    val worker = Executors.newSingleThreadExecutor(); var restore = false
    fun await(label: String, f: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30000
        while (!f()) { check(SystemClock.elapsedRealtime() < end) { "Timed out: $label" }; SystemClock.sleep(100) }
    }
    fun request(yes: Boolean, expectError: Boolean = !yes): String {
        await("local screen") { main { a.hasWindowFocus() } }
        val result = worker.submit<String> { rpc.smokeCall(if (scan) "wifi_scaninfo" else "wifi_connectioninfo", expectError = expectError, timeoutMs = 60000) }
        await("Wi-Fi consent") { main {
            if (result.isDone) error("MCP returned before confirmation: ${result.get()}")
            val notice = if (scan) R.string.wifi_scan_notice else R.string.wifi_connection_notice
            val root = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() && it.findViewById<TextView>(android.R.id.message)?.text == a.getString(notice) } ?: return@main false
            root.findViewById<Button>(if (yes) android.R.id.button1 else android.R.id.button2).performClick()
        } }; return result.get(10, TimeUnit.SECONDS)
    }
    try {
        if (stamps) {
            await("foreground cache reader") { main { a.hasWindowFocus() } }
            fun snapshot() = org.json.JSONArray(wifi.scanResults.map {
                JSONObject().put("bssid", it.BSSID).put("timestampUs", it.timestamp)
            })
            val before = snapshot()
            if (scenario == "wifi-stamp-snapshot") return before.toString()
            rpc.start(10000, true)
            val read = JSONObject(request(true))
            val after = snapshot()
            check(read.getBoolean("cached") && !read.getBoolean("refreshStarted"))
            return JSONObject().put("before", before).put("mcp", read).put("after", after).toString()
        }
        rpc.start(10000, true)
        val denied = request(false); check(denied == "Device request declined")
        if (many) {
            val before = wifi.scanResults.map { it.BSSID }
            check(before.toSet().size >= 101) { "Requires 101 distinct BSSIDs in the Android cache" }
            val approved = JSONObject(request(true))
            val after = wifi.scanResults.map { it.BSSID }
            val rows = approved.getJSONArray("networks")
            val actual = (0 until rows.length()).map { rows.getJSONObject(it).getString("bssid") }
            check(before == after) { "BSSID sequence changed during the call" }
            check(actual == before && approved.getBoolean("cached") && !approved.getBoolean("refreshStarted")) { "MCP did not reproduce the scan cache in order" }
            return JSONObject().put("declined", denied).put("before", org.json.JSONArray(before)).put("mcp", approved)
                .put("after", org.json.JSONArray(after)).toString()
        }
        if (empty) {
            check(shell("settings get global wifi_scan_always_enabled") == "0") { "Requires scan-always disabled" }
            check(shell("cmd location is-location-enabled") == "true") { "Requires initial location on" }
            fun gps(): String = shell("dumpsys location").lines().dropWhile { it.trim() != "gps provider:" }.drop(1)
                .takeWhile { it.isBlank() || it.startsWith("      ") }.map { it.trim() }.first { it == "enabled=true" || it == "enabled=false" }
            check(gps() == "enabled=true"); restore = true
            val report = JSONObject().put("declined", denied)
            try {
                shell("svc wifi disable"); await("empty system scan cache") { wifi.wifiState == WifiManager.WIFI_STATE_DISABLED && shell("cmd wifi list-scan-results") == "No scan results" }
                for (on in listOf(false, true)) {
                    shell("cmd location set-location-enabled $on")
                    await("GPS provider=$on") { shell("cmd location is-location-enabled") == on.toString() && gps() == "enabled=$on" }
                    val cache = shell("cmd wifi list-scan-results"); check(cache == "No scan results")
                    val result = request(true, expectError = !on)
                    val observed = JSONObject().put("systemCache", cache).put("master", shell("cmd location is-location-enabled")).put("gpsProvider", gps())
                    check(observed.getString("master") == on.toString() && observed.getString("gpsProvider") == "enabled=$on" && shell("cmd wifi list-scan-results") == cache)
                    if (on) {
                        val page = JSONObject(result); check(page.getBoolean("cached") && !page.getBoolean("refreshStarted") && page.getJSONArray("networks").length() == 0)
                        observed.put("mcp", page)
                    } else { check(result == "Location needs to be enabled on the device") { result }; observed.put("mcpError", result) }
                    report.put(if (on) "on" else "off", observed)
                }
            } finally { shell("cmd location set-location-enabled true") }
            return report.toString()
        }
        if (scan) {
            val approved = JSONObject(request(true)); check(approved.getBoolean("cached") && !approved.getBoolean("refreshStarted"))
            val fixture = when { names -> "OcNamesFixture"; centers -> "OcCenterFixture"; width -> "OcWidthFixture"; else -> "OcScanFixture" }
            val system = shell("cmd wifi list-scan-results").lineSequence().map { it.trim().split(Regex("\\s+"), limit = 6) }
                .single { it.size == 6 && it[4] == fixture }
            val networks = approved.getJSONArray("networks")
            val match = (0 until networks.length()).map { networks.getJSONObject(it) }.single {
                it.getString("bssid").equals(system[0], ignoreCase = true) && it.getInt("frequencyMHz") == system[1].toInt()
            }
            check(match.getString("ssid") == fixture)
            if (width || centers || names) {
                // The shell list identifies the AP; read optional metadata in a separate framework call.
                val native = wifi.scanResults.single { it.BSSID.equals(system[0], ignoreCase = true) && it.frequency == system[1].toInt() }
                if (names) {
                    val report = JSONObject().put("declined", denied).put("ssid", fixture).put("bssid", native.BSSID).put("frequencyMHz", native.frequency).put("mcpScan", match)
                    for ((key, value) in listOf("operatorName" to native.operatorFriendlyName, "venueName" to native.venueName)) {
                        if (value.isNullOrEmpty()) check(!match.has(key)) { "$key must be omitted, not null or empty" }
                        else check(match.get(key) == value.toString()) { "$key differs from Android" }
                        report.put(key, JSONObject().put("android", value?.toString() ?: JSONObject.NULL).put("mcpHasKey", match.has(key)))
                    }
                    return report.toString()
                }
                if (centers) {
                    val has0 = match.has("centerFreq0MHz"); val has1 = match.has("centerFreq1MHz")
                    check(has0 == (native.channelWidth != android.net.wifi.ScanResult.CHANNEL_WIDTH_20MHZ)) { "Unexpected centerFreq0MHz presence" }
                    check(has1 == (native.channelWidth == android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ)) { "Unexpected centerFreq1MHz presence" }
                    if (has0) check(match.get("centerFreq0MHz") == native.centerFreq0)
                    if (has1) check(match.get("centerFreq1MHz") == native.centerFreq1)
                    return JSONObject().put("declined", denied).put("ssid", fixture).put("bssid", native.BSSID).put("frequencyMHz", native.frequency)
                        .put("androidChannelWidth", native.channelWidth).put("androidCenterFreq0", native.centerFreq0).put("androidCenterFreq1", native.centerFreq1)
                        .put("mcpScan", match).put("mcpHasCenter0", has0).put("mcpHasCenter1", has1).toString()
                }
                val actual = match.get("channelBandwidthMHz")
                check(native.channelWidth == android.net.wifi.ScanResult.CHANNEL_WIDTH_20MHZ && actual == "20") {
                    "20 MHz fixture: Android raw channelWidth=${native.channelWidth}, MCP=$actual"
                }
                return JSONObject().put("declined", denied).put("ssid", fixture).put("bssid", native.BSSID).put("frequencyMHz", native.frequency)
                    .put("androidChannelWidth", native.channelWidth).put("androidWidthEnum", "CHANNEL_WIDTH_20MHZ").put("mcpChannelBandwidthMHz", actual).toString()
            }
            check("PSK" in system[5] && match.get("capabilities") == system[5]) { "Fixture capabilities differ from system flags" }
            return JSONObject().put("declined", denied).put("ssid", fixture).put("bssid", match.getString("bssid")).put("frequencyMHz", match.getInt("frequencyMHz"))
                .put("mcpCapabilities", match.getString("capabilities")).put("systemFlags", system[5]).toString()
        }
        val connected = JSONObject(request(true))
        if (scenario == "wifi-mac") {
            // Use the app UID and normal permissions for the reference, not adb's view of an interface.
            check(android.os.Process.myUid() == c.applicationInfo.uid)
            check(c.checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") == PackageManager.PERMISSION_DENIED)
            val reference = wifi.connectionInfo ?: error("Missing reference connection")
            check(reference.networkId >= 0 && connected.getInt("networkId") == reference.networkId && connected.getString("bssid") == reference.bssid)
            val address = reference.macAddress
            check(connected.has("macAddress") && connected.get("macAddress") == (address ?: JSONObject.NULL)) { "Local Wi-Fi MAC differs from same-permission API" }
            check(address != reference.bssid) { "Fixture cannot distinguish the local MAC from the AP BSSID" }
            return JSONObject().put("declined", denied).put("appUid", android.os.Process.myUid()).put("localMacPermission", false)
                .put("macAddress", connected.get("macAddress")).put("independentApiMac", address ?: JSONObject.NULL)
                .put("bssid", reference.bssid).put("placeholder", address == "02:00:00:00:00:00").toString()
        }
        val connectedWlan = wlan()
        check(connected.getBoolean("enabled") && connected.getString("ip") in connectedWlan.map { it.substringBefore('/') }) { "Connected IP did not match wlan0" }
        val statePattern = Regex("Supplicant state: ([A-Z_]+)")
        val connectedStatus = shell("cmd wifi status")
        val connectedState = statePattern.find(connectedStatus)?.groupValues?.get(1)
        check("Wifi is connected to" in connectedStatus && connectedState == "COMPLETED" && connected.getString("supplicantState") == connectedState) { "Connected supplicant state did not match cmd wifi status" }
        val idPattern = Regex("\\bNet ID: (-?\\d+)\\b")
        val connectedId = connected.get("networkId")
        val systemId = idPattern.find(connectedStatus)?.groupValues?.get(1)?.toInt()
        check(connectedId is Int && connectedId >= 0 && connectedId == systemId) { "Connected networkId=$connectedId did not match Net ID=$systemId" }
        if (hidden != null) {
            val ssid = connected.getString("ssid")
            val rows = shell("dumpsys wifi").substringAfter("WifiConfigManager - Configured networks Begin ----", "")
                .substringBefore("WifiConfigManager - Configured networks End ----").lines()
            val entry = rows.single { it.contains("ID: $connectedId SSID: $ssid ") && "HIDDEN:" in it }
            val saved = Regex("\\bHIDDEN: (true|false)\\b").find(entry)!!.groupValues[1].toBooleanStrict()
            val reference = wifi.connectionInfo
            val bssid = connected.getString("bssid")
            check(reference.networkId == connectedId && reference.bssid.equals(bssid, true) && connectedStatus.contains("BSSID: $bssid,", ignoreCase = true))
            if (hidden) check(ssid == "\"OcHiddenFixture\"" && bssid.equals("02:15:b2:00:00:39", true)) { "Not connected to the capture-verified fixture" }
            val report = JSONObject().put("mcp", connected).put("declined", denied).put("androidHidden", reference.hiddenSSID).put("configuredHidden", saved)
            check(connected.get("ssidHidden") == reference.hiddenSSID && saved == hidden && connected.get("ssidHidden") == hidden) { report.toString() }
            return report.toString()
        }
        restore = true; shell("svc wifi disable"); await("Wi-Fi disabled and IPv4 removed") { wifi.wifiState == WifiManager.WIFI_STATE_DISABLED && wlan().isEmpty() }
        val offline = JSONObject(request(true)); val offlineWlan = wlan()
        check(!offline.getBoolean("enabled") && offline.has("ip") && offline.isNull("ip") && offlineWlan.isEmpty()) { "Old Wi-Fi address survived disconnect: ${offline.opt("ip")}" }
        val offlineStatus = shell("cmd wifi status"); val offlineState = offline.getString("supplicantState")
        val offlineSystemState = statePattern.find(offlineStatus)?.groupValues?.get(1)
        check(offlineStatus.lineSequence().any { it.trim() == "Wifi is disabled" } && offlineState in android.net.wifi.SupplicantState.values().map { it.name } && offlineState != "COMPLETED") { "Stale or invalid supplicant state: $offlineState" }
        if (offlineSystemState != null) check(offlineSystemState == offlineState) { "Disconnected state mismatch: $offlineSystemState vs $offlineState" }
        val offlineId = offline.get("networkId"); val offlineSystemId = idPattern.find(offlineStatus)?.groupValues?.get(1)?.toInt()
        check(offlineId == -1 && offlineId != connectedId && (offlineSystemId == null || offlineId == offlineSystemId)) { "Unexpected disconnected networkId=$offlineId (cmd Net ID=$offlineSystemId)" }
        return JSONObject().put("declined", denied).put("connectedMcpIp", connected.getString("ip")).put("connectedWlan0", connectedWlan.joinToString())
            .put("connectedMcpNetworkId", connectedId).put("connectedCmdNetId", systemId).put("disconnectedMcpNetworkId", offlineId).put("disconnectedCmdNetId", offlineSystemId ?: JSONObject.NULL)
            .put("connectedMcpState", connected.getString("supplicantState")).put("connectedCmd", statePattern.find(connectedStatus)!!.value)
            .put("disconnectedMcpState", offlineState).put("disconnectedCmd", offlineStatus.lineSequence().first { it.trim() == "Wifi is disabled" }).put("disconnectedCmdState", offlineSystemState ?: JSONObject.NULL)
            .put("disconnectedMcpEnabled", offline.getBoolean("enabled")).put("disconnectedMcpIp", offline.get("ip")).put("disconnectedWlan0IPv4Count", offlineWlan.size).toString()
    } finally {
        try { if (restore) { shell("svc wifi enable"); await("virtual Wi-Fi restored") { wifi.isWifiEnabled && wlan().isNotEmpty() } } }
        finally { main { ui.cancel("Fixture closed"); a.finish() }; rpc.stop(); worker.shutdownNow() }
    }
}
