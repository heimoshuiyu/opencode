package ai.opencode.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.http.SslError;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;
import com.getcapacitor.BridgeWebViewClient;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {
    private NativeMicRecorder nativeMicRecorder;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Trust all SSL certificates (including self-signed) before any network call.
        TrustAllSsl.install();

        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, 1001);
        }

        WebView webView = getBridge().getWebView();
        if (webView != null) {
            nativeMicRecorder = new NativeMicRecorder(this);
            webView.addJavascriptInterface(nativeMicRecorder, "OpenCodeNativeMic");
            scheduleBuiltInMicShimInstall(webView, 0);

            getBridge().addWebViewListener(new WebViewListener() {
                @Override
                public void onPageStarted(WebView webView) {
                    scheduleBuiltInMicShimInstall(webView, 0);
                }

                @Override
                public void onPageCommitVisible(WebView webView, String url) {
                    scheduleBuiltInMicShimInstall(webView, 0);
                }

                @Override
                public void onPageLoaded(WebView webView) {
                    scheduleBuiltInMicShimInstall(webView, 0);
                }
            });

            webView.setWebChromeClient(new BridgeWebChromeClient(getBridge()) {
                @Override
                public void onPermissionRequest(PermissionRequest request) {
                    for (String resource : request.getResources()) {
                        if ("android.webkit.resource.AUDIO_CAPTURE".equals(resource)
                                && ContextCompat.checkSelfPermission(
                                    MainActivity.this, Manifest.permission.RECORD_AUDIO)
                                    == PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.getResources());
                            return;
                        }
                        if ("android.webkit.resource.VIDEO_CAPTURE".equals(resource)
                                && ContextCompat.checkSelfPermission(
                                    MainActivity.this, Manifest.permission.CAMERA)
                                    == PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.getResources());
                            return;
                        }
                    }
                    super.onPermissionRequest(request);
                }
            });

            // Trust self-signed certificates in the WebView
            getBridge().setWebViewClient(new BridgeWebViewClient(getBridge()) {
                @Override
                public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    handler.proceed();
                }
            });
        }

        // Start the foreground service to keep SSE/WebSocket connections
        // alive when the app is in the background.
        ForegroundService.start(this);
    }

    @Override
    public void onDestroy() {
        if (nativeMicRecorder != null) nativeMicRecorder.release();
        ForegroundService.stop(this);
        super.onDestroy();
    }

    private void scheduleBuiltInMicShimInstall(WebView webView, int attempt) {
        if (attempt > 30) return;
        webView.postDelayed(
            () -> installBuiltInMicShim(webView, attempt),
            attempt == 0 ? 0 : 1000
        );
    }

    private void installBuiltInMicShim(WebView webView, int attempt) {
        webView.evaluateJavascript(String.join("\n",
            "(() => {",
            "  try {",
            "  if (window.__opencodeAndroidBuiltInMicShim && window.MediaRecorder?.name === 'NativeMicMediaRecorder') return 'already-installed';",
            "  if (!window.OpenCodeNativeMic) return 'missing-native-bridge';",
            "  window.__opencodeAndroidBuiltInMicShim = true;",
            "  const originalGetUserMedia = navigator.mediaDevices?.getUserMedia?.bind(navigator.mediaDevices);",
            "  if (!navigator.mediaDevices) Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: {} });",
            "  const eventWith = (type, props = {}) => {",
            "    const event = new Event(type);",
            "    for (const [key, value] of Object.entries(props)) Object.defineProperty(event, key, { value });",
            "    return event;",
            "  };",
            "  const blobFromBase64 = (base64, mime) => {",
            "    const binary = atob(base64);",
            "    const bytes = new Uint8Array(binary.length);",
            "    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);",
            "    return new Blob([bytes], { type: mime || 'audio/wav' });",
            "  };",
            "  class NativeMicMediaRecorder extends EventTarget {",
            "    static isTypeSupported(type) {",
            "      return !type || String(type).startsWith('audio/');",
            "    }",
            "    constructor(stream, options = {}) {",
            "      super();",
            "      this.stream = stream;",
            "      this.mimeType = 'audio/wav';",
            "      this.state = 'inactive';",
            "      this.ondataavailable = null;",
            "      this.onstop = null;",
            "      this.onstart = null;",
            "      this.onerror = null;",
            "    }",
            "    start() {",
            "      if (this.state !== 'inactive') throw new DOMException('Recorder is already active', 'InvalidStateError');",
            "      const result = JSON.parse(window.OpenCodeNativeMic.start());",
            "      if (!result.ok) throw new DOMException(result.error || 'Native microphone failed to start', 'NotReadableError');",
            "      this.state = 'recording';",
            "      const event = new Event('start');",
            "      this.dispatchEvent(event);",
            "      this.onstart?.(event);",
            "    }",
            "    stop() {",
            "      if (this.state === 'inactive') return;",
            "      this.state = 'inactive';",
            "      const result = JSON.parse(window.OpenCodeNativeMic.stop());",
            "      if (result.ok && result.audio) {",
            "        const dataEvent = eventWith('dataavailable', { data: blobFromBase64(result.audio, result.mime || this.mimeType) });",
            "        this.dispatchEvent(dataEvent);",
            "        this.ondataavailable?.(dataEvent);",
            "      } else {",
            "        const errorEvent = eventWith('error', { error: new DOMException(result.error || 'Native microphone failed to stop', 'NotReadableError') });",
            "        this.dispatchEvent(errorEvent);",
            "        this.onerror?.(errorEvent);",
            "      }",
            "      const stopEvent = new Event('stop');",
            "      this.dispatchEvent(stopEvent);",
            "      this.onstop?.(stopEvent);",
            "    }",
            "    pause() { if (this.state === 'recording') this.state = 'paused'; }",
            "    resume() { if (this.state === 'paused') this.state = 'recording'; }",
            "    requestData() {}",
            "  }",
            "  Object.defineProperty(window, 'MediaRecorder', { configurable: true, writable: true, value: NativeMicMediaRecorder });",
            "  Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {",
            "    configurable: true,",
            "    value: async (constraints = { audio: true }) => {",
            "      if (constraints?.video && originalGetUserMedia) return originalGetUserMedia(constraints);",
            "      return new MediaStream();",
            "    },",
            "  });",
            "  return 'installed';",
            "  } catch (error) {",
            "    console.warn('[OpenCode Android] native built-in mic shim install failed', error);",
            "    return 'error:' + (error?.message || String(error));",
            "  }",
            "})();"
        ), result -> {
            if (result == null || result.contains("missing-native-bridge") || result.contains("error")) {
                scheduleBuiltInMicShimInstall(webView, attempt + 1);
            }
        });
    }
}
