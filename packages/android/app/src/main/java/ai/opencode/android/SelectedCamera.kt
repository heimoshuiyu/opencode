package ai.opencode.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** One foreground Camera2 session. No capture request for a JPEG before the shutter is pressed,
 * and no file until the user explicitly accepts the review. All callbacks use the UI thread. */
internal class SelectedCamera(private val activity: Activity, private val id: String, private val done: (Result<ByteArray>) -> Unit) {
    private val manager = activity.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val preview = TextureView(activity)
    private val review = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
    private var dialog: AlertDialog? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var reader: ImageReader? = null
    private var jpeg: ByteArray? = null
    private var closed = false
    private var capturing = false

    fun show() {
        val area = FrameLayout(activity).apply {
            addView(preview); addView(review)
            minimumHeight = (240 * resources.displayMetrics.density).toInt()
        }
        val d = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.photo_camera_id, id)).setView(area)
            .setPositiveButton(R.string.photo_shutter, null).setNegativeButton(android.R.string.cancel) { _, _ -> fail("Device request declined") }
            .setOnCancelListener { fail("Device request declined") }.create()
        dialog = d
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) = guarded { open(texture) }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { if (!closed && jpeg == null) fail("Camera preview closed"); return true }
        }
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = false
            setOnClickListener { guarded {
                val bytes = jpeg
                if (bytes != null) done(Result.success(bytes)) else capture()
            } }
        }
    }

    private fun guarded(action: () -> Unit) {
        if (!closed) try { action() } catch (e: Exception) { fail(e.message ?: "Camera unavailable") }
    }
    private fun fail(reason: String) { if (!closed) done(Result.failure(IllegalStateException(reason))) }

    @SuppressLint("MissingPermission") // Checked by DeviceInteraction after the user approves this ID.
    @Suppress("DEPRECATION")
    private fun open(texture: SurfaceTexture) {
        val map = manager.getCameraCharacteristics(id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: error("No camera outputs")
        val size = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height } ?: error("Camera does not provide JPEG")
        val previews = map.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val live = previews.filter { it.width <= 1280 && it.height <= 960 }.maxByOrNull { it.width.toLong() * it.height }
            ?: previews.minByOrNull { it.width.toLong() * it.height } ?: error("No preview for this camera")
        texture.setDefaultBufferSize(live.width, live.height)
        val target = Surface(texture); surface = target
        val images = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1); reader = images
        images.setOnImageAvailableListener({ source -> if (!closed && reader === source && capturing) guarded {
            val bytes = source.acquireNextImage()?.use { image -> val buffer = image.planes.single().buffer
                require(buffer.remaining() in 1..32*1024*1024) { "JPEG too large or empty" }; ByteArray(buffer.remaining()).also { buffer.get(it) }
            } ?: return@guarded
            val options = BitmapFactory.Options().apply { inSampleSize = 1; while (maxOf(size.width, size.height) / inSampleSize > 1280) inSampleSize *= 2 }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Invalid JPEG preview")
            jpeg = bytes; releaseCamera(); review.setImageBitmap(bitmap); review.visibility = View.VISIBLE; preview.visibility = View.GONE
            dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).apply { setText(R.string.photo_accept); isEnabled = true }
        } }, handler)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                if (closed) { cam.close(); return }; device = cam
                guarded {
                    check(cam.id == id) { "Unexpected camera ID" }
                    cam.createCaptureSession(listOf(target, images.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (closed || device !== cam) { s.close(); return }; session = s
                            guarded { s.setRepeatingRequest(cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(target) }.build(), null, handler)
                                dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); fail("Cannot configure camera $id") }
                    }, handler)
                }
            }
            override fun onDisconnected(cam: CameraDevice) { cam.close(); if (jpeg == null) fail("Camera disconnected") }
            override fun onError(cam: CameraDevice, error: Int) { cam.close(); if (jpeg == null) fail("Camera $id unavailable ($error)") }
        }, handler)
    }

    private fun capture() {
        if (capturing) return
        capturing = true; dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        val c = manager.getCameraCharacteristics(id); val front = c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
        val rotation = (activity.display?.rotation ?: Surface.ROTATION_0) * 90
        val request = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader!!.surface); set(CaptureRequest.JPEG_ORIENTATION, ((c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0) + (if (front) rotation else -rotation) + 360) % 360)
        }.build()
        session!!.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) { if (jpeg == null) fail("Camera capture failed") }
        }, handler)
    }

    private fun releaseCamera() {
        session?.close(); session = null; device?.close(); device = null; reader?.close(); reader = null; surface?.release(); surface = null
    }
    fun close() {
        closed = true; releaseCamera(); jpeg = null; review.setImageDrawable(null); dialog?.dismiss(); dialog = null
    }
}
