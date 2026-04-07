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
 * Runs a short AE pre-capture (repeating request for ~800ms) so the
 * auto-exposure has time to converge before the actual still capture.
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
            .filter  { it.width <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: map.getOutputSizes(ImageFormat.JPEG)[0]

        Log.d("Camera", "Photo size: ${photoSize.width}x${photoSize.height}")

        val file        = File(context.cacheDir, "beacon_${System.currentTimeMillis()}.jpg")
        val imageReader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2)

        val handlerThread = HandlerThread("CameraThread").also { it.start() }
        val handler = Handler(handlerThread.looper)

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes  = ByteArray(buffer.remaining())
                buffer.get(bytes)
                file.writeBytes(bytes)
                Log.d("Camera", "Photo saved: ${bytes.size} bytes")
                callback(file)
            } catch (e: Exception) {
                Log.e("Camera", "Image save failed", e)
                callback(null)
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
                                    // --- Phase 1: repeating preview to let AE converge ---
                                    val previewRequest = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_PREVIEW
                                    ).apply {
                                        addTarget(imageReader.surface)
                                        set(CaptureRequest.CONTROL_MODE,    CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                    }
                                    session.setRepeatingRequest(previewRequest.build(), null, handler)

                                    // --- Phase 2: after 800ms fire the actual still capture ---
                                    handler.postDelayed({
                                        try {
                                            session.stopRepeating()
                                        } catch (_: Exception) {}

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
                                                override fun onCaptureCompleted(
                                                    s: CameraCaptureSession,
                                                    r: CaptureRequest,
                                                    result: TotalCaptureResult
                                                ) { camera.close() }

                                                override fun onCaptureFailed(
                                                    s: CameraCaptureSession,
                                                    r: CaptureRequest,
                                                    failure: CaptureFailure
                                                ) {
                                                    Log.e("Camera", "Capture failed: ${failure.reason}")
                                                    callback(null); camera.close()
                                                    handlerThread.quitSafely()
                                                }
                                            }, handler)
                                    }, 800L)  // AE warm-up delay

                                } catch (e: Exception) {
                                    Log.e("Camera", "Request failed", e)
                                    callback(null); camera.close()
                                    handlerThread.quitSafely()
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e("Camera", "Session configure failed")
                                callback(null); camera.close()
                                handlerThread.quitSafely()
                            }
                        }, handler
                    )
                } catch (e: Exception) {
                    Log.e("Camera", "createCaptureSession failed", e)
                    callback(null); camera.close()
                    handlerThread.quitSafely()
                }
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close(); handlerThread.quitSafely()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("Camera", "Camera error: $error")
                callback(null); camera.close()
                handlerThread.quitSafely()
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
