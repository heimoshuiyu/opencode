package ai.opencode.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.speech.RecognizerIntent
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture

/** Only the local, focused Activity can approve these requests. Android pickers/sharesheets, not
 * the model, select contacts, take photos and send messages. Late results cannot satisfy a new call. */
internal class DeviceInteraction(private val activity: Activity) {
    private data class Request(val code: Int, val name: String, val args: JSONObject, val answer: CompletableFuture<JSONObject>)
    private var pending: Request? = null
    private var prompt: AlertDialog? = null
    private var sequence = 9000
    private var speechPicker: Int? = null
    private var tts: android.speech.tts.TextToSpeech? = null
    private var biometric: android.os.CancellationSignal? = null
    private val timer = Handler(Looper.getMainLooper())
    private val microphone = DeviceMicrophone(activity)
    private val camera = CameraPhoto(activity)
    private var selectedCamera: SelectedCamera? = null
    private var nfc: DeviceNfc? = null
    private var usb: UsbHandoff? = null
    private var historyRead: android.os.CancellationSignal? = null
    private var unregisterSensor: (() -> Unit)? = null
    private val sensorSubscription = SensorSubscription(activity)
    private var longVibrationUntil = 0L

    fun pauseVibration() {
        if (longVibrationUntil > android.os.SystemClock.elapsedRealtime()) activity.getSystemService(android.os.Vibrator::class.java).cancel()
        longVibrationUntil = 0L
        if (pending?.name == "vibrate") cancel("Vibration requires the foreground local screen")
    }

    fun stopMicrophone() { microphone.stop("left foreground") }
    fun pauseCamera() { if (selectedCamera != null) cancel("Camera requires the foreground local screen") }
    fun pauseNfc() { if (pending?.name == "nfc") cancel("NFC requires the foreground local screen") }
    fun pauseSensor() { sensorSubscription.close(); if (pending?.name == "sensor") cancel("Sensor collection requires the foreground local screen") }
    fun hidden() {
        stopMicrophone()
        // The foreground recognizer may cover this Activity. No result is released on its return.
        if (speechPicker != null) return
        if (pending?.name in setOf("dialog", "microphone_record", "sms_list", "sms_inbox", "call_log", "contact_list", "share")) cancel("Local screen is no longer visible")
    }

    fun invoke(name: String, args: JSONObject): JSONObject {
        val answer = CompletableFuture<JSONObject>()
        activity.runOnUiThread {
            if (name == "sensor" && args.optString("operation") in setOf("read", "stop")) {
                try { answer.complete(sensorSubscription.control(args)) } catch (e: Exception) { answer.completeExceptionally(e) }
                return@runOnUiThread
            }
            if (name == "microphone_record") {
                when (args.optString("operation")) {
                    "info" -> { answer.complete(microphone.info()); return@runOnUiThread }
                    "stop" -> {
                        if (pending?.name == name) cancel("Recording request stopped")
                        val value = microphone.stop("mcp"); if (value.has("error")) answer.completeExceptionally(IllegalStateException(value.getString("error"))) else answer.complete(value)
                        return@runOnUiThread
                    }
                    "start" -> Unit // Duration has already been normalized by DeviceMicrophone.request.
                    else -> { answer.completeExceptionally(IllegalArgumentException("Expected start, info, or stop")); return@runOnUiThread }
                }
            }
            if (pending != null || sensorSubscription.active || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                answer.completeExceptionally(IllegalStateException("Open the local app and retry when no device request is pending")); return@runOnUiThread
            }
            sequence = if (sequence >= 60000) 9000 else sequence + 1
            val request = Request(sequence, name, args, answer); pending = request
            timer.postDelayed({ if (pending === request) cancel("Device request timed out") }, 120000)
            if (name == "dialog" && args.optString("widget") == "speech") {
                prompt = DeviceDialogs.builder(activity, args, activity.getString(R.string.speech_start_title))
                    .setMessage(activity.getString(R.string.speech_start_notice, args.getString("prompt").take(500)))
                    .setNegativeButton(android.R.string.cancel) { _, _ -> if (pending === request) cancel("Device request declined") }
                    .setOnCancelListener { if (pending === request) cancel("Device request declined") }
                    .setPositiveButton(R.string.speech_start) { _, _ -> if (pending === request) {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        if (args.has("hint")) intent.putExtra(RecognizerIntent.EXTRA_PROMPT, args.getString("hint"))
                        try { activity.startActivityForResult(intent, request.code); speechPicker = request.code }
                        catch (_: android.content.ActivityNotFoundException) { cancel("No Android speech recognizer installed") }
                        catch (_: SecurityException) { cancel("Android denied speech recognition") }
                    } }.show()
                return@runOnUiThread
            }
            if (name == "dialog") {
                try { prompt = DeviceDialogs.show(activity, args, { if (pending === request) finish(it) },
                    { if (pending === request) cancel("Device request declined") }) }
                catch (e: Exception) { cancel(e.message ?: "Cannot show dialog") }
                return@runOnUiThread
            }
            val builder = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.device_request, name))
                .setMessage(activity.getString(R.string.device_request_detail, args.toString().take(700)))
                .setNegativeButton(android.R.string.cancel) { _, _ -> cancel("Device request declined") }
                .setOnCancelListener { cancel("Device request declined") }
                .setPositiveButton(android.R.string.ok) { _, _ -> if (pending === request) timer.post {
                    if (pending !== request) return@post
                    try {
                        val permissions = permissions(request.name)
                        if (permissions.any { activity.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED })
                            activity.requestPermissions(permissions, request.code)
                        else perform(request)
                    } catch (e: Exception) { cancel(e.message ?: "Device action failed") }
                } }
            if (name == "wifi_settings" && args.has("enabled")) builder.setMessage(if (args.getBoolean("enabled")) R.string.wifi_panel_enable else R.string.wifi_panel_disable)
            if (name == "microphone_record") {
                val seconds = args.getInt("seconds")
                val notice = if (seconds == 0) activity.getString(R.string.mic_consent_no_limit, args.getString("encoder"), args.getInt("sampleRateHz"), args.getInt("channels"), args.getInt("bitrateBps"))
                    else activity.getString(R.string.mic_consent, seconds, args.getString("encoder"), args.getInt("sampleRateHz"), args.getInt("channels"), args.getInt("bitrateBps"))
                builder.setTitle(R.string.mic_request).setMessage(if (args.has("fileName")) activity.getString(R.string.mic_destination, notice, args.getString("fileName")) else notice)
            }
            if (name == "download" && (args.has("path") || args.has("title") || args.has("description"))) builder.setMessage(buildString {
                if (args.has("path")) append(activity.getString(R.string.download_destination, args.getString("path"))).append("\n\n")
                append(activity.getString(R.string.download_source, args.getString("url")))
                if (args.has("title")) append("\n\n").append(activity.getString(R.string.download_title, args.getString("title")))
                if (args.has("description")) append("\n").append(activity.getString(R.string.download_description, args.getString("description")))
            })
            if (name == "vibrate") builder.setMessage(activity.getString(if (args.getBoolean("force")) R.string.vibrate_long_force else R.string.vibrate_long, args.getInt("milliseconds")))
            if (name == "tts_speak") {
                val message = if (args.has("stream")) activity.getString(R.string.tts_stream_notice, args.getString("stream"), args.getString("text")) else args.getString("text")
                if (args.has("engine")) builder.setMessage(activity.getString(R.string.tts_engine_notice, args.getString("engine"), message))
                else if (args.has("stream")) builder.setMessage(message)
            }
            if (name == "wifi_connectioninfo") builder.setMessage(R.string.wifi_connection_notice)
            if (name == "telephony_deviceinfo") builder.setMessage(R.string.phone_state_notice)
            if (name == "telephony_cellinfo" && args.optString("view") == "full") builder.setMessage(R.string.cell_identity_notice)
            if (name == "wifi_scaninfo") builder.setMessage(if (args.optBoolean("refresh")) R.string.wifi_scan_refresh_notice else R.string.wifi_scan_notice)
            if (name == "sensor") builder.setTitle(R.string.sensor_title).setMessage(if (args.optString("operation") == "start")
                activity.getString(R.string.sensor_stream_notice, args.getString("name"), args.getInt("intervalMs")) else
                activity.getString(R.string.sensor_consent, args.getString("name"), args.getInt("count"), args.getInt("intervalMs")))
            if (name == "camera_photo") {
                val notice = if (args.has("camera")) activity.getString(R.string.photo_selected_notice, args.getString("camera")) else activity.getString(R.string.photo_notice)
                builder.setTitle(R.string.photo_title).setMessage(if (args.has("path")) activity.getString(R.string.photo_destination, notice, args.getString("path")) else notice)
            }
            if (name == "brightness" && args.getString("scope") == "system") builder.setTitle(R.string.system_brightness_title)
                .setMessage(if (args.getString("mode") == "auto") activity.getString(R.string.system_brightness_auto)
                    else activity.getString(R.string.system_brightness_manual, args.getInt("value")))
            if (name == "share" && args.has("workspacePath")) builder.setTitle(R.string.share_file_title)
                .setMessage(activity.getString(R.string.share_file_notice, args.getString("workspacePath")))
            if (name == "call_log") builder.setTitle(R.string.call_log_title)
                .setMessage(activity.getString(R.string.call_log_notice, args.getInt("offset"), args.getInt("limit")))
            if (name == "contact_list") builder.setTitle(R.string.contacts_title)
                .setMessage(activity.getString(R.string.contacts_notice, args.getInt("offset"), args.getInt("limit")))
            if (name == "sms_inbox" || name == "sms_list") {
                val box = args.getString("type"); val address = args.optString("address", activity.getString(R.string.sms_all_addresses))
                val direction = activity.getString(if (args.getString("messageSortOrder") == SmsMessages.DATE_ASC) R.string.sms_oldest_first else R.string.sms_newest_first)
                val notice = if (args.has("conversationLimit"))
                    activity.getString(R.string.sms_threads_notice, box, address, args.getInt("conversationOffset"), args.getInt("conversationLimit"), args.getInt("offset"), args.getInt("limit"), direction,
                        activity.getString(if (args.getString("conversationSortOrder") == SmsMessages.DATE_ASC) R.string.sms_oldest_first else R.string.sms_newest_first))
                    else activity.getString(R.string.sms_read_notice, box, address, args.getInt("offset"), args.getInt("limit"), direction)
                val scoped = if (args.has("threadId")) activity.getString(R.string.sms_thread_filter_notice, args.getLong("threadId"), notice) else notice
                builder.setTitle(R.string.sms_read_title).setMessage(if (args.has("bodyPrefix"))
                    activity.getString(R.string.sms_prefix_notice, args.getString("bodyPrefix"), scoped) else scoped)
            }
            if (name == "usb_open") builder.setTitle(R.string.usb_title).setMessage(activity.getString(R.string.usb_notice,
                args.getString("address"), "%04x:%04x".format(args.getInt("vendor"), args.getInt("product")), args.getString("receiver")))
            if (name == "job_scheduler") {
                val period = args.getLong("periodMs")
                val schedule = if (period == 0L) activity.getString(R.string.job_once, args.getLong("delayMs")) else activity.getString(R.string.job_period, period)
                val power = activity.getString(if (args.getBoolean("requiresCharging")) R.string.job_charging_only else R.string.job_any_power)
                val battery = activity.getString(if (args.getBoolean("requiresBatteryNotLow")) R.string.job_battery_not_low else R.string.job_any_battery)
                val storage = activity.getString(if (args.getBoolean("requiresStorageNotLow")) R.string.job_storage_not_low else R.string.job_any_storage)
                val network = WorkspaceJobs.Network.parse(args.getString("network"))
                val net = activity.getString(R.string.job_network, activity.getString(network.label), network.wire)
                val target = when {
                    args.has("previousPath") -> activity.getString(R.string.job_replace_target, args.getInt("id"), args.getString("previousPath"), args.getString("path"))
                    args.has("id") -> "ID ${args.getInt("id")}\n${args.getString("path")}"
                    else -> args.getString("path")
                }
                val retained = args.optBoolean("persisted")
                val constraints = "$schedule\n$power\n$battery\n$storage\n$net"
                builder.setTitle(when { args.optBoolean("resume") -> R.string.job_resume_title; args.has("previousPath") -> R.string.job_replace_title; retained -> R.string.job_retained_title; else -> R.string.job_title })
                    .setMessage(activity.getString(R.string.job_consent, target, if (retained) activity.getString(R.string.job_retained_notice) + "\n" + constraints else constraints, args.getString("script")))
            }
            if (name == "nfc" && args.optString("operation") == "write") builder.setTitle(R.string.nfc_write_title)
                .setMessage(activity.getString(R.string.nfc_write_prompt, args.getString("text")))
            prompt = builder.show()
        }
        return answer.get()
    }
    private fun readWifiAfterReturn(r: Request) {
        if (pending !== r) return
        try {
            val requested = r.args.getBoolean("enabled")
            val actual = when (activity.applicationContext.getSystemService(WifiManager::class.java).wifiState) {
                WifiManager.WIFI_STATE_ENABLED -> true
                WifiManager.WIFI_STATE_DISABLED -> false
                WifiManager.WIFI_STATE_ENABLING, WifiManager.WIFI_STATE_DISABLING -> { timer.postDelayed({ readWifiAfterReturn(r) }, 150); return }
                else -> error("Wi-Fi state unavailable")
            }
            finish(JSONObject().put("opened", true).put("requestedEnabled", requested).put("actualEnabled", actual).put("achieved", actual == requested))
        } catch (e: Exception) { cancel(e.message ?: "Cannot read Wi-Fi state") }
    }
    private fun finish(value: JSONObject) { val r = clear() ?: return; r.answer.complete(value) }
    fun cancel(reason: String) { val r = clear() ?: return; r.answer.completeExceptionally(IllegalStateException(reason)) }
    private fun clear(): Request? {
        val r = pending ?: return null; pending = null; timer.removeCallbacksAndMessages(null); prompt?.dismiss(); prompt = null
        speechPicker?.let { activity.finishActivity(it) }; speechPicker = null
        historyRead?.cancel(); historyRead = null
        unregisterSensor?.invoke(); unregisterSensor = null
        camera.clear()
        selectedCamera?.close(); selectedCamera = null
        tts?.shutdown(); tts = null; biometric?.cancel(); biometric = null; nfc?.close(); nfc = null; usb?.close(); usb = null; return r
    }
    private fun permissions(name: String): Array<String> = when(name) {
        "sms_list", "sms_inbox" -> arrayOf(android.Manifest.permission.READ_SMS)
        "call_log" -> arrayOf(android.Manifest.permission.READ_CALL_LOG)
        "telephony_deviceinfo" -> arrayOf(android.Manifest.permission.READ_PHONE_STATE)
        "contact_list" -> arrayOf(android.Manifest.permission.READ_CONTACTS)
        "microphone_record" -> arrayOf(android.Manifest.permission.RECORD_AUDIO)
        "torch", "camera_photo" -> arrayOf(android.Manifest.permission.CAMERA)
        "wifi_scaninfo", "wifi_connectioninfo", "telephony_cellinfo" -> arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }
    fun permissionsResult(code: Int): Boolean {
        val r = pending ?: return false; if(r.code != code) return false
        if (permissions(r.name).any { activity.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) cancel("Required Android permission denied")
        else try { perform(r) } catch(e:Exception) { cancel(e.message ?: "Device action failed") }
        return true
    }
    private fun perform(r: Request) {
        check(!activity.isFinishing && !activity.isDestroyed)
        val a = r.args
        fun text() = a.getString("text").also { require(it.length <= 12000) { "Text too long" } }
        fun number() = a.getString("number").also { require(it.matches(Regex("[+0-9 ()-]{1,32}"))) { "Invalid number" } }
        fun launch(i: Intent) { activity.startActivity(i); finish(JSONObject().put("opened", true)) }
        when (r.name) {
            "telephony_deviceinfo" -> finish(phoneDeviceInfo(activity))
            "vibrate" -> {
                val ms = a.getInt("milliseconds"); val result = AndroidActions.vibrate(activity, ms, a.getBoolean("force"))
                if (result.getBoolean("submitted")) longVibrationUntil = android.os.SystemClock.elapsedRealtime() + ms
                finish(result)
            }
            "sensor" -> if (a.optString("operation") == "start") finish(sensorSubscription.start(a)) else collectSensor(r)
            "sms_list", "sms_inbox", "call_log", "contact_list" -> {
                val signal = android.os.CancellationSignal(); historyRead = signal
                val permission = permissions(r.name).single()
                val kind = when (r.name) { "call_log" -> "call log"; "contact_list" -> "contacts"; else -> "SMS" }
                kotlin.concurrent.thread(name = "opencode-history-read") {
                    val result = runCatching {
                        when (r.name) {
                            "call_log" -> CallHistory.page(activity, a, signal)
                            "contact_list" -> PhoneContacts.page(activity, a, signal)
                            else -> SmsMessages.page(activity, a, signal)
                        }
                    }
                    activity.runOnUiThread {
                        if (pending !== r) return@runOnUiThread
                        if (activity.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) cancel("Required Android permission denied")
                        else result.fold(::finish) { cancel(if (it is SecurityException) "Android denied $kind provider access" else "Could not read $kind page; try a smaller limit") }
                    }
                }
            }
            "usb_open" -> {
                val handoff = UsbHandoff(activity); usb = handoff
                handoff.open(a) { outcome -> if (pending === r) outcome.fold(::finish) { cancel(it.message ?: "USB handoff failed") } }
            }
            "nfc" -> {
                val adapter = android.nfc.NfcAdapter.getDefaultAdapter(activity) ?: error("No NFC adapter")
                check(adapter.isEnabled) { "NFC is disabled" }
                val message = if (a.getString("operation") == "write") DeviceNfc.textMessage(a.getString("text")) else null
                prompt = AlertDialog.Builder(activity).setMessage(R.string.nfc_tap)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> cancel("NFC cancelled") }
                    .setOnCancelListener { cancel("NFC cancelled") }.show()
                nfc = DeviceNfc(activity, adapter).also { it.start(message, a.optString("detail") == "full") { result ->
                    if (pending === r) result.fold(::finish) { e -> cancel(e.message ?: "NFC operation failed") }
                } }
            }
            "microphone_record" -> finish(microphone.start(a))
            "keystore", "job_scheduler" -> finish(JSONObject().put("approved", true))
            "torch" -> {
                val cm = activity.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val id = if(a.has("camera")) a.getString("camera") else cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it)[android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE] == true } ?: error("No flashlight")
                cm.setTorchMode(id,a.getBoolean("enabled")); finish(JSONObject().put("id",id).put("enabled",a.getBoolean("enabled")))
            }
            "wifi_connectioninfo", "wifi_scaninfo" -> {
                val wifi = activity.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                if(r.name == "wifi_connectioninfo") {
                    val w = wifi.connectionInfo ?: error("No Wi-Fi connection")
                    val enabled = wifi.isWifiEnabled
                    val state = w.supplicantState
                    // Read the IPv4 of this Wi-Fi connection, not an address from the default route (possibly cellular/VPN).
                    val address = w.ipAddress
                    val ip = if (enabled && state == android.net.wifi.SupplicantState.COMPLETED && address != 0)
                        android.text.format.Formatter.formatIpAddress(address) else null
                    finish(JSONObject().put("enabled",enabled).put("ssid",w.ssid).put("bssid",w.bssid).put("macAddress",w.macAddress ?: JSONObject.NULL).put("rssi",w.rssi)
                        .put("frequencyMHz",w.frequency).put("linkMbps",w.linkSpeed).put("supplicantState", state.name)
                        .put("networkId", w.networkId).put("ssidHidden", w.getHiddenSSID()).put("ip", ip ?: JSONObject.NULL))
                } else {
                    val started = a.optBoolean("refresh") && wifi.startScan()
                    val scans = wifi.scanResults ?: error("Failed getting scan results")
                    if (scans.isEmpty() && !activity.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        error("Location needs to be enabled on the device")
                    }
                    finish(JSONObject().put("refreshStarted",started).put("cached",true).put("networks",wifiScanNetworks(scans)))
                }
            }
            "telephony_cellinfo" -> {
                val tel = activity.getSystemService(android.telephony.TelephonyManager::class.java)
                finish(cellInfoResult(tel.allCellInfo.orEmpty(), a))
            }
            "storage_get", "wallpaper" -> activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (r.name == "wallpaper") "image/*" else "*/*"),r.code)
            "tts_speak" -> {
                val message = text(); require(message.length in 1..android.speech.tts.TextToSpeech.getMaxSpeechInputLength())
                tts = android.speech.tts.TextToSpeech(activity.applicationContext, { status -> timer.post {
                    if (pending !== r) return@post
                    val engine = tts
                    if(engine == null || status != android.speech.tts.TextToSpeech.SUCCESS) return@post cancel("TTS initialization failed")
                    if (a.has("stream")) {
                        val attributes = android.media.AudioAttributes.Builder().setLegacyStreamType(AndroidActions.streams.getValue(a.getString("stream"))).build()
                        if (engine.setAudioAttributes(attributes) != android.speech.tts.TextToSpeech.SUCCESS) return@post cancel("TTS stream rejected")
                    }
                    if(a.has("language") && engine.setLanguage(java.util.Locale.forLanguageTag(a.getString("language"))) < 0) return@post cancel("TTS language unavailable")
                    if (a.has("pitch") && engine.setPitch(a.getDouble("pitch").toFloat()) != android.speech.tts.TextToSpeech.SUCCESS) return@post cancel("TTS pitch rejected")
                    if (a.has("rate") && engine.setSpeechRate(a.getDouble("rate").toFloat()) != android.speech.tts.TextToSpeech.SUCCESS) return@post cancel("TTS rate rejected")
                    engine.setOnUtteranceProgressListener(object: android.speech.tts.UtteranceProgressListener(){
                        override fun onStart(id:String) = Unit
                        override fun onDone(id:String) { timer.post { if(pending === r) finish(JSONObject().put("spoken",true)) } }
                        override fun onError(id:String) { timer.post { if(pending === r) cancel("TTS synthesis failed") } }
                    });if(engine.speak(message,android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"device-${r.code}") != android.speech.tts.TextToSpeech.SUCCESS) cancel("TTS rejected request")
                 } }, if (a.has("engine")) a.getString("engine") else null)
            }
            "fingerprint" -> {
                val signal=android.os.CancellationSignal().also { biometric=it }
                android.hardware.biometrics.BiometricPrompt.Builder(activity).setTitle("OpenCode").setNegativeButton(activity.getString(android.R.string.cancel),activity.mainExecutor) { _, _ -> if(pending===r)cancel("Authentication declined") }
                    .build().authenticate(signal,activity.mainExecutor,object:android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
                        override fun onAuthenticationSucceeded(result:android.hardware.biometrics.BiometricPrompt.AuthenticationResult) { if(pending===r)finish(JSONObject().put("authenticated",true)) }
                        override fun onAuthenticationError(code:Int,msg:CharSequence) { if(pending===r)cancel(msg.toString()) }
                    })
            }
            "saf_remove" -> finish(SharedDocuments(activity).remove(a))
            "saf_manage" -> launch(Intent(activity, FileAccessActivity::class.java))
            "download" -> {
                val uri = Uri.parse(a.getString("url")); require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Use a public HTTPS URL without credentials" }
                val request = android.app.DownloadManager.Request(uri).setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (a.has("title")) request.setTitle(a.getString("title"))
                if (a.has("description")) request.setDescription(a.getString("description"))
                if (a.has("path")) WorkspaceOutput(activity.filesDir, a.getString("path"), "Download").checkNew()
                val id = activity.getSystemService(android.app.DownloadManager::class.java).enqueue(request)
                finish(JSONObject().put("downloadId", id))
            }
            "infrared_transmit" -> {
                val ir = activity.getSystemService(android.hardware.ConsumerIrManager::class.java); check(ir?.hasIrEmitter()==true) { "No infrared emitter" }
                val hz = a.getInt("hz");val pattern = a.getString("pattern").split(',').map { it.trim().toInt() }
                require(hz > 0 && pattern.size in 1..512 && pattern.all { it in 1..1000000 } && pattern.sumOf { it.toLong() } <= 1000000)
                ir.transmit(hz,pattern.toIntArray());finish(JSONObject().put("transmitted",true))
            }
            "toast" -> { showDeviceToast(activity, text().take(200), a); finish(JSONObject().put("shown", true)) }
            "clipboard_get" -> {
                val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip
                val value = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: error("Clipboard empty, not plain text or access denied")
                require(value.length <= 12000) { "Clipboard text too long" }; finish(JSONObject().put("text", value))
            }
            "clipboard_set" -> { activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenCode", text())); finish(JSONObject().put("written", true)) }
            "volume" -> {
                val audio = activity.getSystemService(AudioManager::class.java); val id = AndroidActions.streams[a.getString("stream")] ?: error("Unknown stream")
                val level = a.getInt("level"); require(level in audio.getStreamMinVolume(id)..audio.getStreamMaxVolume(id)); audio.setStreamVolume(id, level, AudioManager.FLAG_SHOW_UI)
                finish(JSONObject().put("level", audio.getStreamVolume(id)))
            }
            "brightness" -> if (a.getString("scope") == "window") {
                val n = a.getInt("value"); activity.window.attributes = activity.window.attributes.apply { screenBrightness = if (n == -1) -1f else n / 255f }
                finish(JSONObject().put("value", n))
            } else if (Settings.System.canWrite(activity)) setSystemBrightness(a)
            else activity.startActivityForResult(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", activity.packageName, null)), r.code)
            "share" -> if (!a.has("workspacePath")) launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text()), null))
            else kotlin.concurrent.thread(name = "opencode-share-file") {
                val prepared = runCatching { FileShare.prepare(activity.applicationContext, a.getString("workspacePath")) }
                activity.runOnUiThread {
                    if (pending !== r) { prepared.getOrNull()?.first?.delete(); return@runOnUiThread }
                    prepared.fold({ (file, chooser) ->
                        try { launch(chooser) } catch (e: Exception) { file.delete(); cancel(e.message ?: "Cannot open sharesheet") }
                    }, { cancel(it.message ?: "Cannot prepare file") })
                }
            }
            "sms_send" -> launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number(), null)).putExtra("sms_body", text()))
            "telephony_call" -> launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number(), null)))
            // The Internet panel can delegate to SystemUI and finish before its switch is even visible.
            // For a state receipt, launch the actual Wi-Fi Settings activity for result instead.
            "wifi_settings" -> if (r.args.has("enabled")) activity.startActivityForResult(Intent(Settings.ACTION_WIFI_SETTINGS), r.code)
                else launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            "speech_to_text" -> activity.startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), r.code)
            "contact_pick" -> activity.startActivityForResult(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI), r.code)
            "camera_photo" -> {
                camera.prepare(a)
                if (!a.has("camera")) { camera.launch(r.code); return }
                val id = a.getString("camera")
                selectedCamera = SelectedCamera(activity, id) { result -> if (pending === r) {
                    result.mapCatching { camera.saveSelected(it, id) }.fold(::finish) { cancel(it.message ?: "Camera unavailable") }
                } }
                selectedCamera!!.show()
            }
            else -> error("Unknown foreground operation")
        }
    }
    private fun collectSensor(r: Request) {
        val sm = activity.getSystemService(SensorManager::class.java)
        val count = r.args.getInt("count"); val interval = r.args.getInt("intervalMs")
        class Stream(val sensor: Sensor) {
            val samples = org.json.JSONArray()
            var next = android.os.SystemClock.elapsedRealtimeNanos()
            fun result() = JSONObject().put("type", sensor.type).put("name", sensor.name).put("samples", samples)
        }
        val streams = linkedMapOf<Sensor, Stream>()
        var total = count * r.args.getJSONArray("selected").length()
        lateinit var report: JSONObject
        var collected = 0
        val indicator = AlertDialog.Builder(activity).setTitle(R.string.sensor_active)
            .setMessage(activity.getString(R.string.sensor_progress, 0, total))
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancel("Sensor collection cancelled") }
            .setOnCancelListener { cancel("Sensor collection cancelled") }.show()
        prompt = indicator
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(s: Sensor, accuracy: Int) = Unit
            override fun onSensorChanged(e: SensorEvent) {
                val stream = streams[e.sensor] ?: return
                if (pending !== r || e.timestamp < stream.next || stream.samples.length() == count) return
                if (e.values.any { !it.isFinite() }) return cancel("Non-finite sensor reading")
                stream.next = e.timestamp + interval * 1_000_000L
                stream.samples.put(JSONObject().put("timestampNanos", e.timestamp).put("values", org.json.JSONArray(e.values.map { it.toDouble() })))
                collected++
                if (collected == total) {
                    val result = if (r.args.getBoolean("multi")) report.put("sensors", org.json.JSONArray(streams.values.map { it.result() })) else stream.result()
                    finish(result.put("intervalMs", interval))
                } else {
                    if (stream.samples.length() == count) sm.unregisterListener(this, stream.sensor)
                    indicator.setMessage(activity.getString(R.string.sensor_progress, collected, total))
                }
            }
        }
        unregisterSensor = { sm.unregisterListener(listener) }
        report = registerSelectedSensors(sm, r.args, listener, timer) { streams[it] = Stream(it) }
        total = count * streams.size
        if (streams.isEmpty()) finish(report.put("state", "unavailable").put("sensors", org.json.JSONArray()))
        else indicator.setMessage(activity.getString(R.string.sensor_progress, 0, total))
    }

    private fun setSystemBrightness(args: JSONObject) {
        check(Settings.System.canWrite(activity)) { "Modify system settings access denied" }
        val resolver = activity.contentResolver
        val modeKey = Settings.System.SCREEN_BRIGHTNESS_MODE; val levelKey = Settings.System.SCREEN_BRIGHTNESS
        val previous = Settings.System.getInt(resolver, modeKey)
        val mode = if (args.getString("mode") == "auto") Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        if (mode != previous) check(Settings.System.putInt(resolver, modeKey, mode)) { "Brightness mode write rejected" }
        if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            try { check(Settings.System.putInt(resolver, levelKey, args.getInt("value"))) { "Brightness write rejected" } }
            catch (e: Exception) { if (mode != previous) runCatching { Settings.System.putInt(resolver, modeKey, previous) }; throw e }
        }
        check(Settings.System.getInt(resolver, modeKey) == mode) { "Android did not apply the requested brightness mode" }
        finish(JSONObject().put("scope", "system").put("mode", args.getString("mode")).put("value", Settings.System.getInt(resolver, levelKey)))
    }

    fun result(code: Int, status: Int, data: Intent?) {
        val r = pending ?: return; if (r.code != code) return
        // Settings does not return the switch value. Back without changing it is a valid outcome.
        if (r.name == "wifi_settings" && r.args.has("enabled")) return readWifiAfterReturn(r)
        if (r.name == "dialog" && r.args.optString("widget") == "speech") {
            if (speechPicker != code) return
            speechPicker = null
            if (status != Activity.RESULT_OK) return cancel("System action cancelled")
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (text.isNullOrBlank()) return cancel("No speech recognized")
            if (text.length > 8192) return cancel("Speech result too long")
            if (activity.isFinishing || activity.isDestroyed) return cancel("Local screen closed")
            prompt = DeviceDialogs.builder(activity, r.args, activity.getString(R.string.speech_review_title))
                .setMessage(activity.getString(R.string.speech_review_notice, text))
                .setNegativeButton(android.R.string.cancel) { _, _ -> if (pending === r) cancel("Device request declined") }
                .setOnCancelListener { if (pending === r) cancel("Device request declined") }
                .setPositiveButton(R.string.speech_share) { _, _ -> if (pending === r) finish(JSONObject().put("text", text)) }.show()
            return
        }
        // The special-access Settings screen does not return a runtime permission result.
        if (r.name == "brightness" && r.args.getString("scope") == "system") {
            try { setSystemBrightness(r.args) } catch (e: Exception) { cancel(e.message ?: "Cannot change system brightness") }; return
        }
        if (status != Activity.RESULT_OK) return cancel("System action cancelled")
        try {
            when (r.name) {
                "wallpaper" -> {
                    val uri=data?.data ?: error("No image selected"); require(uri.scheme=="content")
                    activity.startActivity(activity.getSystemService(android.app.WallpaperManager::class.java).getCropAndSetWallpaperIntent(uri));finish(JSONObject().put("opened",true))
                }
                "storage_get" -> {
                    val uri=data?.data ?: error("No document selected");require(uri.scheme=="content")
                    kotlin.concurrent.thread(name="opencode-file-import") {
                        var out:File?=null
                        try {
                            val dir=File(activity.filesDir,"home/projects").canonicalFile;check(dir.path.startsWith(activity.filesDir.canonicalPath+File.separator));val f=File.createTempFile("import-", ".bin",dir);out=f
                            val bytes=activity.contentResolver.openInputStream(uri)?.use { src -> f.outputStream().use { dest ->
                                var total=0L;val buf=ByteArray(32768);while(true){val n=src.read(buf);if(n<0)break;total+=n;require(total<=32*1024*1024){"Import exceeds 32 MiB"};dest.write(buf,0,n)};total
                            } } ?: error("Cannot read document")
                            activity.runOnUiThread { if(pending===r)finish(JSONObject().put("path",f.path).put("bytes",bytes)) else f.delete() }
                        }catch(e:Exception){out?.delete();activity.runOnUiThread { if(pending===r)cancel(e.message?:"Import failed") }}
                    }
                }
                "camera_photo" -> finish(camera.save())
                "speech_to_text" -> finish(JSONObject().put("text",data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: error("No speech recognized")))
                "contact_pick" -> {
                    val uri = data?.data ?: error("No contact selected"); require(uri.scheme == "content" && uri.authority == "com.android.contacts")
                    activity.contentResolver.query(uri,arrayOf(Phone.DISPLAY_NAME,Phone.NUMBER),null,null,null)?.use {
                        check(it.moveToFirst()); finish(JSONObject().put("name",it.getString(0)).put("number",it.getString(1)))
                    } ?: error("Contact not available")
                }
            }
        } catch(e:Exception) { cancel(e.message ?: "Invalid system result") }
    }
}
