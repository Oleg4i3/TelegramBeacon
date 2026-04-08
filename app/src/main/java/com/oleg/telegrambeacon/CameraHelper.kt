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
 * Two-phase capture:
 *   Phase 1 — setRepeatingRequest (STILL_CAPTURE template, NO image listener yet)
 *             for 1s so AE/AWB converges. Images produced here are DISCARDED.
 *   Phase 2 — stopRepeating, THEN set image listener, THEN single capture().
 *
 * This avoids the bug where the listener fires on a warmup frame (overexposed/
 * front camera error) instead of the final well-exposed capture.
 *
 * TEMPLATE_STILL_CAPTURE is used for both phases — it is the only template
 * compatible with a JPEG ImageReader on all camera facing directions.
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

        Log.d("Camera", "Photo: ${photoSize.width}x${photoSize.height} cameraId=$cameraId")

        val file        = File(context.cacheDir, "beacon_${System.currentTimeMillis()}.jpg")
        // maxImages=1: only one image slot — forces acquireLatestImage to always give the newest
        val imageReader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 1)

        val handlerThread = HandlerThread("CameraThread").also { it.start() }
        val handler = Handler(handlerThread.looper)

        fun fail(msg: String, camera: CameraDevice?) {
            Log.e("Camera", msg)
            try { imageReader.close() } catch (_: Exception) {}
            try { camera?.close()    } catch (_: Exception) {}
            handlerThread.quitSafely()
            callback(null)
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                try {
                    camera.createCaptureSession(
                        listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    // ── Phase 1: warmup (NO listener — discard frames) ──────
                                    val warmup = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_STILL_CAPTURE
                                    ).apply {
                                        addTarget(imageReader.surface)
                                        set(CaptureRequest.CONTROL_MODE,     CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AE_MODE,  CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(CaptureRequest.CONTROL_AF_MODE,  CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                        set(CaptureRequest.JPEG_QUALITY,     80.toByte())
                                    }.build()

                                    // Drain any images produced during warmup
                                    imageReader.setOnImageAvailableListener({ reader ->
                                        reader.acquireLatestImage()?.close()
                                    }, handler)

                                    session.setRepeatingRequest(warmup, null, handler)

                                    // ── Phase 2: after 1s, stop warmup and capture ──────────
                                    handler.postDelayed({
                                        try { session.stopRepeating() } catch (_: Exception) {}

                                        // NOW set the real listener that saves the image
                                        imageReader.setOnImageAvailableListener({ reader ->
                                            val image = reader.acquireLatestImage() ?: run {
                                                fail("acquireLatestImage returned null", camera); return@setOnImageAvailableListener
                                            }
                                            try {
                                                val buffer = image.planes[0].buffer
                                                val bytes  = ByteArray(buffer.remaining())
                                                buffer.get(bytes)
                                                file.writeBytes(bytes)
                                                Log.d("Camera", "Photo saved: ${bytes.size} bytes")
                                                callback(file)
                                            } catch (e: Exception) {
                                                Log.e("Camera", "Save failed", e); callback(null)
                                            } finally {
                                                image.close()
                                                imageReader.close()
                                                camera.close()
                                                handlerThread.quitSafely()
                                            }
                                        }, handler)

                                        val still = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_STILL_CAPTURE
                                        ).apply {
                                            addTarget(imageReader.surface)
                                            set(CaptureRequest.JPEG_QUALITY,     92.toByte())
                                            set(CaptureRequest.CONTROL_MODE,     CaptureRequest.CONTROL_MODE_AUTO)
                                            set(CaptureRequest.CONTROL_AE_MODE,  CaptureRequest.CONTROL_AE_MODE_ON)
                                            set(CaptureRequest.CONTROL_AF_MODE,  CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                        }.build()

                                        session.capture(still, object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                                                fail("Capture failed: ${f.reason}", camera)
                                            }
                                        }, handler)

                                    }, 1000L)

                                } catch (e: Exception) { fail("Request setup: ${e.message}", camera) }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) =
                                fail("Session configure failed", camera)
                        }, handler
                    )
                } catch (e: Exception) { fail("createCaptureSession: ${e.message}", camera) }
            }
            override fun onDisconnected(camera: CameraDevice) = fail("Camera disconnected", camera)
            override fun onError(camera: CameraDevice, error: Int)  = fail("Camera error $error", camera)
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
