package ai.opencode.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import androidx.core.content.ContextCompat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

public class NativeMicRecorder {
    private final Context context;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private ByteArrayOutputStream pcm;
    private volatile boolean recording;
    private int sampleRate = 16000;
    private int bufferSize;

    public NativeMicRecorder(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public synchronized String start() {
        if (recording) return success().toString();

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return failure("Microphone permission is not granted").toString();
        }

        int size = chooseBufferSize();
        if (size <= 0) return failure("No supported audio input format found").toString();

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.stopBluetoothSco();
        audioManager.setBluetoothScoOn(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice();

        AudioRecord record = new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .build();

        AudioDeviceInfo builtInMic = builtInMic(audioManager);
        if (builtInMic != null) {
            record.setPreferredDevice(builtInMic);
        } else {
            Log.w("OpenCode", "Native recorder could not find built-in mic");
        }

        audioRecord = record;
        bufferSize = size;
        pcm = new ByteArrayOutputStream();
        recording = true;
        recordingThread = new Thread(() -> recordAudio(record, pcm, size), "OpenCodeNativeMicRecorder");
        recordingThread.start();

        return success().toString();
    }

    @JavascriptInterface
    public synchronized String stop() {
        if (!recording && pcm == null) return failure("Native recorder is not active").toString();

        recording = false;
        AudioRecord record = audioRecord;
        if (record != null) {
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
            } catch (Exception e) {
                Log.w("OpenCode", "Native recorder stop failed", e);
            }
        }

        Thread thread = recordingThread;
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (record != null) {
            record.release();
        }

        byte[] pcmBytes = pcm == null ? new byte[0] : pcm.toByteArray();
        audioRecord = null;
        recordingThread = null;
        pcm = null;

        if (pcmBytes.length == 0) return failure("No audio captured").toString();

        JSONObject result = success();
        put(result, "audio", Base64.encodeToString(wavBytes(pcmBytes), Base64.NO_WRAP));
        return result.toString();
    }

    public synchronized void release() {
        if (recording || audioRecord != null) stop();
    }

    private void recordAudio(AudioRecord record, ByteArrayOutputStream output, int size) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        byte[] buffer = new byte[size];
        try {
            record.startRecording();
            while (recording) {
                int read = record.read(buffer, 0, buffer.length);
                if (read > 0) output.write(buffer, 0, read);
            }
        } catch (Exception e) {
            Log.e("OpenCode", "Native recorder read failed", e);
            recording = false;
        }
    }

    private int chooseBufferSize() {
        int[] sampleRates = {16000, 44100, 48000};
        for (int rate : sampleRates) {
            int min = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            );
            if (min > 0) {
                sampleRate = rate;
                return Math.max(min * 2, rate / 10 * 2);
            }
        }
        return -1;
    }

    private AudioDeviceInfo builtInMic(AudioManager audioManager) {
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return device;
        }
        return null;
    }

    private JSONObject success() {
        JSONObject result = new JSONObject();
        put(result, "ok", true);
        put(result, "mime", "audio/wav");
        put(result, "sampleRate", sampleRate);
        put(result, "bufferSize", bufferSize);
        return result;
    }

    private JSONObject failure(String message) {
        JSONObject result = new JSONObject();
        put(result, "ok", false);
        put(result, "error", message);
        return result;
    }

    private void put(JSONObject object, String name, Object value) {
        try {
            object.put(name, value);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] wavBytes(byte[] pcmBytes) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(44 + pcmBytes.length);
            writeAscii(output, "RIFF");
            writeIntLE(output, 36 + pcmBytes.length);
            writeAscii(output, "WAVE");
            writeAscii(output, "fmt ");
            writeIntLE(output, 16);
            writeShortLE(output, 1);
            writeShortLE(output, 1);
            writeIntLE(output, sampleRate);
            writeIntLE(output, sampleRate * 2);
            writeShortLE(output, 2);
            writeShortLE(output, 16);
            writeAscii(output, "data");
            writeIntLE(output, pcmBytes.length);
            output.write(pcmBytes);
            return output.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes("US-ASCII"));
    }

    private void writeIntLE(ByteArrayOutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
        output.write((value >> 16) & 0xff);
        output.write((value >> 24) & 0xff);
    }

    private void writeShortLE(ByteArrayOutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
    }
}
