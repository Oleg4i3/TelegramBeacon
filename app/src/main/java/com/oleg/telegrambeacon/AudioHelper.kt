package com.oleg.telegrambeacon

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioHelper(private val context: Context) {

    companion object {
        private const val TAG = "AudioHelper"
        const val DEFAULT_DURATION_SEC = 10
    }

    @SuppressLint("MissingPermission")
    fun recordAudio(durationSec: Int = DEFAULT_DURATION_SEC, callback: (File?) -> Unit) {
        val file = File(context.cacheDir, "beacon_audio_${System.currentTimeMillis()}.m4a")

        // NOTE: .apply{} must be on a separate variable — not chained on if/else.
        // Chaining .apply{} on an if/else expression attaches it only to the else branch.
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context)
        else
            @Suppress("DEPRECATION") MediaRecorder()

        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44100)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
        }

        try {
            recorder.prepare()
            recorder.start()
            Log.d(TAG, "Audio recording started, ${durationSec}s…")
            Thread.sleep(durationSec * 1000L)
            recorder.stop()
            recorder.release()
            Log.d(TAG, "Audio done: ${file.length()} bytes")
            callback(if (file.exists() && file.length() > 0) file else null)
        } catch (e: Exception) {
            Log.e(TAG, "Audio recording failed", e)
            try { recorder.release() } catch (_: Exception) {}
            callback(null)
        }
    }
}
