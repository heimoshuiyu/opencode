package ai.opencode.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import java.util.UUID

/** Run on an emulator with am instrument. No user keys are touched. */
class KeySmoke : Instrumentation() {
    private var scenario: String? = null
    private var tagRun: String? = null
    private var channelRun: String? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); scenario = arguments?.getString("scenario"); tagRun = arguments?.getString("tagRun"); channelRun = arguments?.getString("channelRun"); start() }
    override fun onStart() {
        val output = Bundle()
        val result = runCatching {
            if (scenario == "cellinfo-unmatched-serializer") { output.putString(scenario, unmatchedCellSmoke()); return@runCatching }
            if (scenario == "cellinfo-full-count") { output.putString(scenario, cellCountSmoke()); return@runCatching }
            if (scenario in setOf("cellinfo-capture", "cellinfo-full", "cellinfo-full-lte", "cellinfo-full-gsm", "cellinfo-full-cdma", "cellinfo-full-wcdma")) {
                val radio = when (scenario) { "cellinfo-capture" -> null; "cellinfo-full" -> "nr"; else -> scenario!!.removePrefix("cellinfo-full-") }
                output.putString(scenario, cellInfoCapture(this, radio)); return@runCatching
            }
            if (scenario == "telephony-deviceinfo") { output.putString(scenario, phoneInfoSmoke(this)); return@runCatching }
            if (scenario in setOf("notification-marker-callback", "notification-marker-consent")) { output.putString(scenario, notificationSmoke(this, scenario!!, UUID.fromString(requireNotNull(tagRun)))); return@runCatching }
            if (scenario in setOf("reply-system-ui", "reply-post-send", "reply-post-cancel", "reply-verify")) { output.putString(scenario, notificationReplySmoke(this, scenario!!, UUID.fromString(requireNotNull(tagRun)))); return@runCatching }
            if (scenario in setOf("notification-media", "notification-media-skip")) { output.putString(scenario, notificationMediaSmoke(this, scenario == "notification-media-skip")); return@runCatching }
            if (scenario == "notification-delete-action") { output.putString(scenario, notificationDeleteSmoke(this)); return@runCatching }
            if (scenario == "composer-audio") { output.putString(scenario, composerAudioSmoke(this)); return@runCatching }
            if (scenario == "notification-button" || scenario == "notification-body") { output.putString(scenario, notificationButtonSmoke(this, scenario == "notification-body")); return@runCatching }
            if (scenario in setOf("channel-restore-prepare", "channel-restore-verify")) { output.putString(scenario, notificationSmoke(this, scenario!!, UUID.fromString(requireNotNull(channelRun)))); return@runCatching }
            if (scenario in setOf("notifications", "notification-group", "notification-alert-once", "notification-ongoing", "notification-channel", "notification-picture", "notification-icon", "notification-priority", "notification-vibrate", "notification-sound", "notification-led")) { output.putString(scenario, notificationSmoke(this, scenario!!)); return@runCatching }
            if (scenario == "clipboard") { output.putString("clipboard", clipboardSmoke(this)); return@runCatching }
            if (scenario == "volume") { output.putString("volume", volumeSmoke(this)); return@runCatching }
            if (scenario == "wifi-settings-panel") { output.putString("wifi-settings-panel", wifiSettingsPanelSmoke(this)); return@runCatching }
            if (scenario == "wifi-name-serializer") { output.putString("wifi-name-serializer", wifiNameSerializerSmoke()); return@runCatching }
            if (scenario == "wifi-wide-serializer") { output.putString("wifi-wide-serializer", wifiWideSerializerSmoke()); return@runCatching }
            if (scenario == "wifi-scan-count") { output.putString(scenario, wifiScanCountSmoke()); return@runCatching }
            if (scenario in setOf("wifi-ip", "wifi-mac", "wifi-visible", "wifi-hidden", "wifi-scan-capabilities", "wifi-scan-width", "wifi-scan-centers", "wifi-scan-names", "wifi-scan-empty", "wifi-scan-many", "wifi-stamp-snapshot", "wifi-stamp-capture")) {
                output.putString(scenario, wifiSmoke(this, scenario!!)); return@runCatching
            }
            if (scenario == "vibrate-long-exit") { output.putString(scenario, vibrateLongExitSmoke(this)); return@runCatching }
            if (scenario in setOf("vibrate-normal", "vibrate-silent", "vibrate-forced")) {
                output.putString(scenario, vibrateSmoke(this, scenario!!)); return@runCatching
            }
            if (scenario == "download-metadata" || scenario == "download-path") { output.putString(scenario, downloadMetadataSmoke(this, scenario == "download-path")); return@runCatching }
            if (scenario == "camera-id" || scenario == "camera-output") { output.putString(scenario, cameraIdSmoke(this, scenario == "camera-output")); return@runCatching }
            if (scenario == "job-reboot-prepare" || scenario == "job-reboot-verify") {
                output.putString(scenario, jobRebootSmoke(this, scenario == "job-reboot-prepare")); return@runCatching
            }
            if (scenario in setOf("microphone-unlimited", "microphone-duration-lifecycle")) {
                output.putString(scenario, microphoneDurationSmoke(this, scenario == "microphone-unlimited")); return@runCatching
            }
            if (scenario == "toast-style") { output.putString("toast-style", toastStyleSmoke(this)); return@runCatching }
            if (scenario in setOf("tts-prosody", "tts-stream", "tts-engine")) {
                output.putString(scenario, ttsProsodySmoke(this, scenario == "tts-stream", scenario == "tts-engine")); return@runCatching
            }
            if (scenario in setOf("microphone-codecs", "microphone-options", "microphone-filename", "microphone-subdir")) {
                output.putString(scenario, microphoneCodecSmoke(this, scenario == "microphone-options", scenario == "microphone-filename", scenario == "microphone-subdir")); return@runCatching
            }
            if (scenario in setOf("speech-start-cancel", "speech-review-cancel", "speech-share", "speech-picker-cancel")) {
                output.putString(scenario, speechDialogSmoke(this, scenario!!)); return@runCatching
            }
            if (scenario == "dialog-widgets") {
                output.putString("dialog-widgets", dialogSmoke(this)); return@runCatching
            }
            if (scenario == "audio-info") {
                output.putString("audio-info", audioInfoSmoke(targetContext)); return@runCatching
            }
            if (scenario == "battery-status") {
                output.putString("battery-status", batterySmoke(targetContext)); return@runCatching
            }
            if (scenario == "job-id-collision") {
                val c = targetContext; val os = c.getSystemService(android.app.job.JobScheduler::class.java)
                val id = generateSequence { kotlin.random.Random.nextInt(1, Int.MAX_VALUE) }.first { os.getPendingJob(it) == null }
                val file = java.io.File.createTempFile("job-id-", ".sh", java.io.File(c.filesDir,"home/projects").apply { mkdirs() })
                try {
                    file.writeText(":\n")
                    val nonce = UUID.randomUUID().toString()
                    val occupant = android.app.job.JobInfo.Builder(id, android.content.ComponentName(c, WorkspaceJobService::class.java))
                        .setMinimumLatency(600000).setExtras(android.os.PersistableBundle().apply { putString("nonce", nonce) }).build()
                    check(os.schedule(occupant) == android.app.job.JobScheduler.RESULT_SUCCESS)
                    val err = runCatching { WorkspaceJobs.call(c, JSONObject().put("operation", "schedule").put("id", id).put("path", file.name)) { error("Unexpected approval prompt") } }.exceptionOrNull()
                    check(err?.message?.contains("not a pending job approved") == true) { "Expected unowned ID rejection: $err" }
                    check(os.getPendingJob(id)?.extras?.getString("nonce") == nonce)
                    output.putString("job-id-collision", "Unapproved same-component job $id rejected before consent; original OS job unchanged")
                } finally { os.cancel(id); file.delete() }
                return@runCatching
            }
            if (scenario == "nfc") {
                output.putString("nfc", nfcSmoke(targetContext)); return@runCatching
            }
            if (scenario in setOf("nfc-cvd-info", "nfc-cvd-write", "nfc-cvd-read", "nfc-cvd-decline")) {
                output.putString(scenario, nfcCvdSmoke(this, scenario!!, tagRun)); return@runCatching
            }
            if (scenario == "media-scan") {
                output.putString("media-scan", mediaScanSmoke(targetContext)); return@runCatching
            }
            if (scenario == "media-player") {
                output.putString("media-player", mediaPlayerSmoke(targetContext)); return@runCatching
            }
            for (alg in listOf("RSA", "EC")) {
                val name = "smoke-" + UUID.randomUUID()
                val a = JSONObject().put("alias", name).put("algorithm", alg)
                fun run(op: String) = DeviceKeys.run(a.put("operation", op)) { check(it.getString("operation") == op) }
                check(run("generate").getString("publicKeySpki").isNotBlank())
                try {
                    check(run("list").getJSONArray("keys").toString().contains(name))
                    a.put("dataBase64", "YWJj")
                    val signature = run("sign").getString("signature"); a.put("signature", signature)
                    check(run("verify").getBoolean("valid")); a.put("dataBase64", "YWJk"); check(!run("verify").getBoolean("valid"))
                } finally { check(run("delete").getBoolean("deleted")) }
                check(!run("list").getJSONArray("keys").toString().contains(name))
                output.putString(alg, "create/list/sign/verify/tamper/delete passed")
            }
        }
        result.exceptionOrNull()?.let { output.putString("failure", it.toString()) }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, output)
    }
}
