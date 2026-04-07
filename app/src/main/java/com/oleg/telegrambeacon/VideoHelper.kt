package com.oleg.telegrambeacon

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import android.util.Size
import java.io.File

class VideoHelper(private val context: Context) {

    companion object {
        private const val TAG = "VideoHelper"
        const val DEFAULT_DURATION_SEC = 5
    }

    @SuppressLint("MissingPermission")
    fun recordVideo(
        durationSec: Int       = DEFAULT_DURATION_SEC,
        useBackCamera: Boolean = true,
        callback: (File?) -> Unit
    ) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = selectCamera(cameraManager, useBackCamera) ?: run {
            Log.e(TAG, "No camera found"); callback(null); return
        }

        val file     = File(context.cacheDir, "beacon_video_${System.currentTimeMillis()}.mp4")
        val recorder = buildRecorder(file, selectSize(cameraManager, cameraId))

        val handlerThread = HandlerThread("VideoThread").also { it.start() }
        val handler = Handler(handlerThread.looper)

        try {
            recorder.prepare()
        } catch (e: Exception) {
            Log.e(TAG, "recorder.prepare() failed", e)
            recorder.release(); handlerThread.quitSafely(); callback(null); return
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                try {
                    camera.createCaptureSession(
                        listOf(recorder.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val request = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_RECORD
                                    ).apply {
                                        addTarget(recorder.surface)
                                        set(CaptureRequest.CONTROL_MODE,   CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    }
                                    // Start repeating so AE settles, then start recording
                                    session.setRepeatingRequest(request.build(), null, handler)
                                    handler.postDelayed({
                                        try {
                                            recorder.start()
                                            Log.d(TAG, "Recording started, ${durationSec}s…")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "recorder.start() failed", e)
                                            cleanup(recorder, camera, handlerThread, null, callback); return@postDelayed
                                        }
                                        handler.postDelayed({
                                            try { recorder.stop() } catch (e: Exception) { Log.e(TAG, "stop failed", e) }
                                            try { session.stopRepeating() } catch (_: Exception) {}
                                            recorder.release()
                                            camera.close()
                                            handlerThread.quitSafely()
                                            val ok = file.exists() && file.length() > 0
                                            Log.d(TAG, "Video done: ${file.length()} bytes")
                                            callback(if (ok) file else null)
                                        }, durationSec * 1000L)
                                    }, 600L) // 600ms AE warm-up

                                } catch (e: Exception) {
                                    Log.e(TAG, "Capture request failed", e)
                                    cleanup(recorder, camera, handlerThread, null, callback)
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Session configure failed")
                                cleanup(recorder, camera, handlerThread, null, callback)
                            }
                        }, handler
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "createCaptureSession failed", e)
                    cleanup(recorder, camera, handlerThread, null, callback)
                }
            }
            override fun onDisconnected(camera: CameraDevice) {
                cleanup(recorder, camera, handlerThread, null, callback)
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                cleanup(recorder, camera, handlerThread, null, callback)
            }
        }, handler)
    }

    // -------------------------------------------------------------------------

    private fun buildRecorder(file: File, size: Size): MediaRecorder {
        // NOTE: .apply{} must be on the variable, not chained on if/else —
        // otherwise on API 31+ the apply block is only attached to the else branch.
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context)
        else
            @Suppress("DEPRECATION") MediaRecorder()

        rec.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(size.width, size.height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(2_000_000)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44100)
            setOutputFile(file.absolutePath)
        }
        return rec
    }

    private fun selectSize(manager: CameraManager, cameraId: String): Size {
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(MediaRecorder::class.java)
        return sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.filter { it.width <= 1280 }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes[0]
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

    private fun cleanup(
        recorder: MediaRecorder, camera: CameraDevice,
        thread: HandlerThread, file: File?, callback: (File?) -> Unit
    ) {
        try { recorder.stop()    } catch (_: Exception) {}
        try { recorder.release() } catch (_: Exception) {}
        try { camera.close()     } catch (_: Exception) {}
        thread.quitSafely()
        callback(file)
    }
}
