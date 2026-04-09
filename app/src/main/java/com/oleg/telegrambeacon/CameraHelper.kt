package com.oleg.telegrambeacon

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * Headless JPEG capture compatible with both front and rear cameras.
 *
 * Root cause of front camera errors:
 *   TEMPLATE_STILL_CAPTURE as a repeating request is NOT supported on many
 *   front camera HALs — it triggers onError(ERROR_CAMERA_DEVICE).
 *
 * Solution — two-surface session:
 *   Surface A: dummy SurfaceTexture (throwaway preview frames) — used for warmup
 *   Surface B: JPEG ImageReader                                — used for final still
 *
 *   Phase 1: TEMPLATE_PREVIEW repeating → Surface A only.
 *            AE/AWB/AF converge. Surface B never receives anything yet.
 *   Phase 2: stop repeating.
 *            AF cameras: send AF_TRIGGER → wait for FOCUSED_LOCKED → capture.
 *            Fixed-focus cameras (most front): go straight to capture.
 *   Phase 3: single TEMPLATE_STILL_CAPTURE → Surface B only.
 *            ImageReader listener fires, saves JPEG, done.
 *
 * TEMPLATE_PREVIEW on a SurfaceTexture works on 100% of Android cameras.
 * The final STILL_CAPTURE is sent exactly once to the JPEG surface.
 */
class CameraHelper(private val context: Context) {

    companion object { private const val TAG = "CameraHelper" }

    @SuppressLint("MissingPermission")
    fun takePhoto(useBackCamera: Boolean = true, callback: (File?) -> Unit) {
        val mgr      = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = selectCamera(mgr, useBackCamera) ?: run {
            Log.e(TAG, "No camera found"); callback(null); return
        }

        val chars   = mgr.getCameraCharacteristics(cameraId)
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        val hasAF   = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes
                   || CaptureRequest.CONTROL_AF_MODE_AUTO in afModes
        val afMode  = when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CaptureRequest.CONTROL_AF_MODE_AUTO               in afModes -> CaptureRequest.CONTROL_AF_MODE_AUTO
            CaptureRequest.CONTROL_AF_MODE_FIXED              in afModes -> CaptureRequest.CONTROL_AF_MODE_FIXED
            else                                                          -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
        Log.d(TAG, "cameraId=$cameraId hasAF=$hasAF afMode=$afMode afModes=$afModes")

        val map       = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val photoSize = map.getOutputSizes(ImageFormat.JPEG)
            .filter { it.width <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: map.getOutputSizes(ImageFormat.JPEG)[0]
        Log.d(TAG, "Size: ${photoSize.width}x${photoSize.height}")

        // ── surfaces ─────────────────────────────────────────────────────────
        // Dummy SurfaceTexture for warmup preview — Camera2 requires a real Surface,
        // setDefaultBufferSize must match something the camera supports.
        val dummyST = SurfaceTexture(0).apply {
            setDefaultBufferSize(640, 480)
        }
        val dummySurface = Surface(dummyST)

        val file        = File(context.cacheDir, "beacon_${System.currentTimeMillis()}.jpg")
        val imageReader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2)

        val ht      = HandlerThread("CameraThread").also { it.start() }
        val handler = Handler(ht.looper)

        fun cleanup(cam: CameraDevice?) {
            runCatching { imageReader.close() }
            runCatching { cam?.close() }
            runCatching { dummySurface.release() }
            runCatching { dummyST.release() }
            ht.quitSafely()
        }

        fun fail(msg: String, cam: CameraDevice?) {
            Log.e(TAG, "FAIL: $msg")
            cleanup(cam)
            callback(null)
        }

        // Final still capture — sent only to imageReader surface
        fun doCapture(session: CameraCaptureSession, cam: CameraDevice) {
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: run {
                    fail("null image on acquire", cam); return@setOnImageAvailableListener
                }
                try {
                    val buf   = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    file.writeBytes(bytes)
                    Log.d(TAG, "Saved ${bytes.size} bytes")
                    callback(file)
                } catch (e: Exception) {
                    Log.e(TAG, "Save failed", e); callback(null)
                } finally {
                    image.close()
                    cleanup(cam)
                }
            }, handler)

            val stillReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)          // JPEG surface only
                set(CaptureRequest.JPEG_QUALITY,     92.toByte())
                set(CaptureRequest.CONTROL_MODE,     CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE,  CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE,  afMode)
            }.build()

            session.capture(stillReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    fail("still capture failed reason=${f.reason}", cam)
                }
            }, handler)
        }

        // AF trigger + wait for lock, then capture
        fun doAfThenCapture(session: CameraCaptureSession, cam: CameraDevice) {
            var afDone = false

            // Timeout: capture anyway if AF doesn't lock within 3s
            handler.postDelayed({
                if (!afDone) {
                    afDone = true
                    Log.w(TAG, "AF timeout — capturing without lock")
                    doCapture(session, cam)
                }
            }, 3000L)

            val afReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(dummySurface)                 // still preview surface, no JPEG yet
                set(CaptureRequest.CONTROL_MODE,      CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE,   CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE,  CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE,   CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }.build()

            session.capture(afReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    if (afDone) return
                    val state = result.get(CaptureResult.CONTROL_AF_STATE)
                    Log.d(TAG, "AF state: $state")
                    val locked = state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                              || state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    if (locked) {
                        afDone = true
                        doCapture(session, cam)
                    }
                    // else: still converging — wait for next result or timeout
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    if (!afDone) { afDone = true; doCapture(session, cam) }
                }
            }, handler)
        }

        // ── open camera ──────────────────────────────────────────────────────
        mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                try {
                    // Session includes BOTH surfaces so we can switch between them
                    cam.createCaptureSession(
                        listOf(dummySurface, imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    // Phase 1: PREVIEW repeating → dummySurface only
                                    // TEMPLATE_PREVIEW + SurfaceTexture works on ALL cameras
                                    val previewReq = cam.createCaptureRequest(
                                        CameraDevice.TEMPLATE_PREVIEW
                                    ).apply {
                                        addTarget(dummySurface)
                                        set(CaptureRequest.CONTROL_MODE,     CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AE_MODE,  CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AF_MODE,  afMode)
                                    }.build()

                                    session.setRepeatingRequest(previewReq, null, handler)

                                    // Phase 2: after AE warmup, stop and move to capture
                                    handler.postDelayed({
                                        try { session.stopRepeating() } catch (_: Exception) {}
                                        if (hasAF) doAfThenCapture(session, cam)
                                        else       doCapture(session, cam)
                                    }, 1200L)

                                } catch (e: Exception) { fail("session setup: ${e.message}", cam) }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) =
                                fail("configure failed", cam)
                        }, handler
                    )
                } catch (e: Exception) { fail("createCaptureSession: ${e.message}", cam) }
            }
            override fun onDisconnected(cam: CameraDevice) = fail("disconnected", cam)
            override fun onError(cam: CameraDevice, error: Int) = fail("hw error=$error", cam)
        }, handler)
    }

    private fun selectCamera(mgr: CameraManager, preferBack: Boolean): String? {
        val want = if (preferBack) CameraCharacteristics.LENS_FACING_BACK
                   else            CameraCharacteristics.LENS_FACING_FRONT
        mgr.cameraIdList.forEach { id ->
            if (mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == want) return id
        }
        return mgr.cameraIdList.firstOrNull()
    }
}
