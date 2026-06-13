package com.opencode.app.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileInputStream

class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false

    val mime: String = "audio/aac"

    fun start() {
        if (isRecording) return
        val file = File(context.cacheDir, "voice_recording_${System.currentTimeMillis()}.aac")
        outputFile = file

        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            // AAC ADTS is a streaming format that doesn't need finalization
            // More reliable than MPEG4 container which can be corrupted if stopped abruptly
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(file.absolutePath)
        }

        try {
            rec.prepare()
            rec.start()
            recorder = rec
            isRecording = true
            Log.d("VoiceRecorder", "Recording started: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "Failed to start recording", e)
            rec.release()
            outputFile?.delete()
            outputFile = null
            throw e
        }
    }

    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false

        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "Failed to stop recording", e)
        }
        recorder?.release()
        recorder = null

        val file = outputFile
        outputFile = null
        Log.d("VoiceRecorder", "Recording stopped: ${file?.absolutePath} (${file?.length()} bytes)")
        return file
    }

    fun cancel() {
        if (isRecording) {
            isRecording = false
            try { recorder?.stop() } catch (_: Exception) {}
            recorder?.release()
            recorder = null
        }
        outputFile?.delete()
        outputFile = null
    }

    companion object {
        fun fileToBase64(file: File): String {
            val bytes = ByteArray(file.length().toInt())
            FileInputStream(file).use { it.read(bytes) }
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }
}
