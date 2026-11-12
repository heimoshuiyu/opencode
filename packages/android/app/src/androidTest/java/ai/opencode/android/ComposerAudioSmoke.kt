package ai.opencode.android

import android.app.Instrumentation
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** No server or credentials needed. Run on a disposable AVD with RECORD_AUDIO granted. */
internal fun composerAudioSmoke(test: Instrumentation): String {
    val c = test.targetContext
    val a = test.startActivitySync(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun find(view: View): WebView? = if (view is WebView) view else (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
    }
    fun js(script: String): String {
        val response = CompletableFuture<String>()
        test.runOnMainSync { find(a.window.decorView)!!.evaluateJavascript(script) { response.complete(it) } }
        return response.get(5, TimeUnit.SECONDS)
    }
    fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 20000
        while (!predicate()) { check(SystemClock.elapsedRealtime() < until) { "Timeout: $label" }; SystemClock.sleep(30) }
    }
    val manager = c.getSystemService(AudioManager::class.java)
    try {
        await("packaged shell") { js("!!window.openCodeAudioPort") == "true" }
        js("""window.openCodeAudioPort.then(p => {window.audioSmoke = {id:crypto.randomUUID(),events:[],data:''}; const s=audioSmoke;
          s.send = (op, sound) => p.postMessage(JSON.stringify({op,sound,id:s.id}));
          p.onmessage = e => {const m=JSON.parse(e.data); if(m.id!==s.id)return;
            if(m.event==='chunk')s.data+=atob(m.value); else s.events.push(m.event==='error'?m.value:m.event)};});void 0""")
        await("origin-bound port") { js("!!window.audioSmoke?.send") == "true" }
        js("audioSmoke.send('play','alert-01')")
        // SoundPool is not reliably listed in a normal app's activePlaybackConfigurations.
        await("media usage") {
            val log = android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("dumpsys audio")).bufferedReader().use { it.readText() }
            val player = Regex("new player piid:(\\d+) uid/pid:\\d+/${android.os.Process.myPid()} package:ai\\.opencode\\.android type:android\\.media\\.SoundPool .*?usage=USAGE_MEDIA").find(log)
            player != null && log.contains("player piid:${player.groupValues[1]} event:started")
        }
        check(manager.mode == AudioManager.MODE_NORMAL && manager.activeRecordingConfigurations.isEmpty())
        js("audioSmoke.send('record')")
        await("ready") { val events = js("audioSmoke.events.join(',')"); check(events == "\"\"" || events.contains("ready")) { events }; events.contains("ready") }
        check(manager.activeRecordingConfigurations.any { it.audioDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC })
        SystemClock.sleep(650)
        js("audioSmoke.send('finish')")
        await("Ogg/Opus") { js("audioSmoke.events.includes('done') && audioSmoke.data.startsWith('OggS') && audioSmoke.data.includes('OpusHead')") == "true" }
        check(manager.mode == AudioManager.MODE_NORMAL && manager.activeRecordingConfigurations.isEmpty())
        return "Packaged WebView port; preview USAGE_MEDIA, no active capture; built-in input; Ogg/Opus returned and recorder released."
    } finally { test.runOnMainSync { a.finish() } }
}
