# OpenCode for Android

One APK contains the OpenCode web UI, a Bionic Node runtime for arm64-v8a and x86_64, the OpenCode V2 server, and native Android device tools. No Termux installation, root, or separate frontend download is required.

- **This device / 本机** starts the packaged server in the app sandbox and connects automatically. No pairing or server password is needed. Choose a model/provider as usual; model weights and provider credentials are not bundled. The default project directory is `~/projects`, where `~` is the app's private home. The database, credentials and projects survive app restarts, not an uninstall or Android's “clear storage”.
- **Remote server / 远程服务器** starts only the UI. Paste a pairing link or use the server address and password. Use HTTPS for a remote host; only `127.0.0.1` permits cleartext (for example with `adb reverse`). The remote host supplies API data, not executable frontend code. It is not given the phone's device MCP.
- **Voice input:** tap the microphone in the composer and grant Android microphone access, then tap again to stop. The fork attaches the recording for audio-capable chat models; otherwise it transcribes to draft text using the selected server's `transcription.model`. The local runtime includes an audio-only FFmpeg. Remote mode needs a server with the compatible fork API for transcription.
- Selecting local starts a user-visible foreground service. Home, Back and switching modes leave an already-running local job alone. Tap the running notification to return; stop it from the notification or mode picker. Choosing remote on a fresh launch does not start Node. Android can still stop processes, suspend networking or sleep; there is no wake lock, background location or boot-time autostart. Non-interactive shell tools use `/system/bin/sh`; the optional interactive PTY, desktop file watcher, FFF accelerator and QR scanner are not included.

## Device tools

The APK's Vite config adapts only notification sounds and composer capture. Idle/preview/start/stop sounds use Android media output. Composer recordings require the built-in microphone and are discarded if the active input changes away from it. GPT-Live continues to use WebView WebRTC with the system-selected devices. Nothing sets a global communication device or forces the speaker, and normal browser/desktop builds do not load the Android adapter.

The local server automatically connects to a loopback-only Kotlin Streamable HTTP MCP server with a separate per-launch bearer token. `android_battery_status` reads battery percentage and plugged-in state. `android_get_location` asks for confirmation **on the phone every time**, then checks OS location permission. Rejecting either prompt returns an error, not coordinates. There is no background location permission. If using a cloud model, tool results can be sent to that model.

## Shared files and notifications

Under **Folder access**, choose **Grant read-only folder** or **Grant read-write folder**. The list shows each directory's persisted permission. Select the same directory again to change its mode, or revoke it from its row. Legacy individual document permissions are retired without deleting any files. `android_saf_roots`, `android_saf_list`, `android_saf_read_text` and `android_saf_write_text` operate on directory grants; paths are relative to a selected folder, not Linux paths for shell tools. Text is UTF-8, limited to 48 KiB per document; replacement requires `overwrite=true`. No all-files permission. `android_send_notification` posts a local task notice on a separate channel from the running notification. On Android 13+ allow notifications to see both in the shade; denial does not grant the agent a notification bypass.

## Build

Build in the **fork checkout (`hmsy-v2`)**. Gradle compiles both `../app` and `../cli` from that same working tree, including uncommitted edits, rather than fetching an upstream frontend or server. `assets/opencode-source.properties` in the APK records the branch and base commit, not a claim of a clean checkout.

On Linux install the repository's Bun version, JDK 21, Android SDK Platform 36, NDK `28.2.13676358`, make, Python 3.9+, curl, dpkg-deb and patchelf. Set `ANDROID_HOME` (or `sdk.dir` and `ANDROID_NDK_HOME`). From the repository root:

```sh
bun install --frozen-lockfile
cd packages/android
./gradlew :app:assembleDebug
```

The debug-signed APK is `app/build/outputs/apk/debug/app-debug.apk`. It targets Android 11+ devices with a current System WebView. Native binaries are sourced at **build time** from the Termux package archive, verified against `script/runtime.lock`, repackaged with their shared libraries and notices, and installed in Android's read-only native library directory. Keep the pinned `.deb` files in `build/runtime-cache` for rebuilds if the rolling archive drops an old version, or set `TERMUX_APT_MIRROR` to a mirror with those exact artifacts. Nothing installs or downloads executable code on first launch.

The two modes have separate stable WebView origins resolved from APK assets, not a TCP listener. Native bootstrap credentials are served only to the local-mode WebView and are not put in a URL. Neither mode exposes a general native JavaScript bridge.
### Binary files in a granted folder

Use a `root` from `android_saf_roots` and a `path` relative to that grant. To export a workspace file, call `android_saf_write_binary` with `{"root":"<writable root ID>","path":"archive.zip","workspacePath":"build/archive.zip"}`. `workspacePath` is relative to the local `home/projects` directory. Existing files require `overwrite: true`.

To import it, call `android_saf_read_binary` with `{"root":"<root ID>","path":"archive.zip"}`. The response includes the new `workspacePath`, `bytes` and `sha256`; the bytes are not encoded into the MCP response. A read-only grant permits import, not export. The caller manages the imported workspace copy.
