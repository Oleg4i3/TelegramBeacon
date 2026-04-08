package com.oleg.telegrambeacon

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File

/**
 * Takes a JPEG photo without any preview surface.
 *
 * AE warm-up strategy: fire STILL_CAPTURE repeating requests for 1s so AE/AWB
 * can converge, then stop repeating and fire the final capture.
 * TEMPLATE_PREVIEW is intentionally avoided — it is incompatible with a JPEG
 * ImageReader on many front-facing cameras and causes session errors.
 */
class CameraHelper(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun takePhoto(useBackCamera: Boolean = true, callback: (File?) -> Unit) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = selectCamera(cameraManager, useBackCamera) ?: run {
            Log.e("Camera", "No camera found"); callback(null); return
        }

        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val photoSize = map.getOutputSizes(ImageFormat.JPEG)
            .filter { it.width <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: map.getOutputSizes(ImageFormat.JPEG)[0]

        Log.d("Camera", "Photo size: ${photoSize.width}x${photoSize.height}, cameraId=$cameraId")

        val file        = File(context.cacheDir, "beacon_${System.currentTimeMillis()}.jpg")
        val imageReader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2)

        val handlerThread = HandlerThread("CameraThread").also { it.start() }
        val handler = Handler(handlerThread.looper)

        var imageSaved = false
        imageReader.setOnImageAvailableListener({ reader ->
            if (imageSaved) return@setOnImageAvailableListener
            imageSaved = true
            val image = reader.acquireLatestImage() ?: run { callback(null); return@setOnImageAvailableListener }
            try {
                val buffer = image.planes[0].buffer
                val bytes  = ByteArray(buffer.remaining())
                buffer.get(bytes)
                file.writeBytes(bytes)
                Log.d("Camera", "Photo saved: ${bytes.size} bytes")
                callback(file)
            } catch (e: Exception) {
                Log.e("Camera", "Image save failed", e); callback(null)
            } finally {
                image.close()
                reader.close()
                handlerThread.quitSafely()
            }
        }, handler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                try {
                    camera.createCaptureSession(
                        listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    // Phase 1 — repeating STILL_CAPTURE to let AE/AWB converge.
                                    // STILL_CAPTURE is the correct template for JPEG ImageReader
                                    // and works on both rear and front cameras.
                                    val warmupRequest = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_STILL_CAPTURE
                                    ).apply {
                                        addTarget(imageReader.surface)
                                        set(CaptureRequest.CONTROL_MODE,    CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                        set(CaptureRequest.JPEG_QUALITY,    80.toByte())
                                    }
                                    // Suppress image delivery during warm-up by NOT setting
                                    // the listener yet — imageSaved guard handles any leakage.
                                    session.setRepeatingRequest(warmupRequest.build(), null, handler)

                                    // Phase 2 — after AE settles, fire final capture
                                    handler.postDelayed({
                                        try { session.stopRepeating() } catch (_: Exception) {}

                                        val stillRequest = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_STILL_CAPTURE
                                        ).apply {
                                            addTarget(imageReader.surface)
                                            set(CaptureRequest.JPEG_QUALITY,   90.toByte())
                                            set(CaptureRequest.CONTROL_MODE,   CaptureRequest.CONTROL_MODE_AUTO)
                                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                        }
                                        session.capture(stillRequest.build(),
                                            object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                                    camera.close()
                                                }
                                                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                                                    Log.e("Camera", "Capture failed: ${failure.reason}")
                                                    if (!imageSaved) { imageSaved = true; callback(null) }
                                                    camera.close(); handlerThread.quitSafely()
                                                }
                                            }, handler)
                                    }, 1000L) // 1s warm-up — enough for front cam AE
                                } catch (e: Exception) {
                                    Log.e("Camera", "Request setup failed", e)
                                    if (!imageSaved) { imageSaved = true; callback(null) }
                                    camera.close(); handlerThread.quitSafely()
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e("Camera", "Session configure failed")
                                if (!imageSaved) { imageSaved = true; callback(null) }
                                camera.close(); handlerThread.quitSafely()
                            }
                        }, handler
                    )
                } catch (e: Exception) {
                    Log.e("Camera", "createCaptureSession failed", e)
                    if (!imageSaved) { imageSaved = true; callback(null) }
                    camera.close(); handlerThread.quitSafely()
                }
            }
            override fun onDisconnected(camera: CameraDevice) {
                if (!imageSaved) { imageSaved = true; callback(null) }
                camera.close(); handlerThread.quitSafely()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("Camera", "Camera error: $error")
                if (!imageSaved) { imageSaved = true; callback(null) }
                camera.close(); handlerThread.quitSafely()
            }
        }, handler)
    }

    private fun selectCamera(manager: CameraManager, preferBack: Boolean): String? {
        val preferred = if (preferBack) CameraCharacteristics.LENS_FACING_BACK
                        else            CameraCharacteristics.LENS_FACING_FRONT
        manager.cameraIdList.forEach { id ->
            if (manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == preferred) return id
        }
        return manager.cameraIdList.firstOrNull()
    }
}
