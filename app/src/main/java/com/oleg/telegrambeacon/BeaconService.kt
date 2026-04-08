package com.oleg.telegrambeacon

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.sqrt

class BeaconService : Service(), SensorEventListener {

    companion object {
        @Volatile var isRunning = false
        private const val TAG = "BeaconService"
    }

    private lateinit var prefs:           SharedPreferences
    private lateinit var telegram:        TelegramSender
    private lateinit var camera:          CameraHelper
    private lateinit var video:           VideoHelper
    private lateinit var audio:           AudioHelper
    private lateinit var sensorManager:   SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var cameraManager:   CameraManager
    private var wakeLock: PowerManager.WakeLock? = null

    // --- State ---
    private val  handler      = Handler(Looper.getMainLooper())
    private var  lastUpdateId = 0L
    private var  lastLocation: Location? = null
    private var  lastAccel    = floatArrayOf(0f, 0f, 9.8f)
    private var  lastAlertMs  = 0L

    // --- Security: sessions ---
    // Each authorized session: fromId → chatId to reply to
    // Owner is always authorized. Guests auth via password for session lifetime.
    private val authorizedSessions = mutableMapOf<Long, String>() // fromId → replyToChatId

    // --- GPS on-demand ---
    private var gpsActive   = false
    private var gpsStopJob: Runnable? = null

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        prefs           = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        telegram        = TelegramSender(prefs)
        camera          = CameraHelper(this)
        video           = VideoHelper(this)
        audio           = AudioHelper(this)
        sensorManager   = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        cameraManager   = getSystemService(CAMERA_SERVICE) as CameraManager

        // Always authorize the owner
        authorizeOwner()

        createNotificationChannel()
        startForeground(Config.NOTIFICATION_ID, buildNotification("🟢 Active"))
        acquireWakeLock()

        // GPS: start immediately if not on-demand mode
        if (!prefs.getBoolean(Config.KEY_GPS_ON_DEMAND, false)) {
            startLocationUpdates()
        }
        startAccelerometer()

        handler.postDelayed(pollRunnable, 3_000L)
        handler.post(reportRunnable)

        val alarmOn = prefs.getBoolean(Config.KEY_ALARM_ENABLED, true)
        val password = prefs.getString(Config.KEY_PASSWORD, "") ?: ""
        val secInfo = if (password.isBlank()) "⚠️ No password set — anyone with bot access can send commands." else "🔐 Password protected."
        telegram.sendMessageToOwner(
            "🟢 <b>Beacon started</b>\n${currentTime()}\n$secInfo\n\nSend /help for commands.",
            TelegramSender.mainKeyboard(alarmOn)
        )
        Log.i(TAG, "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(reportRunnable)
        handler.removeCallbacks(pollRunnable)
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
        wakeLock?.release()
        try { cameraManager.setTorchMode(getBackCameraId() ?: return, false) } catch (_: Exception) {}
        telegram.sendMessageToOwner("🔴 <b>Beacon stopped</b>\n${currentTime()}")
        Log.i(TAG, "Service stopped")
    }

    override fun onBind(intent: Intent?) = null

    // =========================================================================
    // Authorization
    // =========================================================================

    private fun authorizeOwner() {
        val ownerChatId = prefs.getString(Config.KEY_CHAT_ID, "") ?: ""
        if (ownerChatId.isNotBlank()) {
            val ownerFromId = ownerChatId.toLongOrNull() ?: 0L
            authorizedSessions[ownerFromId] = ownerChatId
        }
    }

    /** Returns replyToChatId if sender is authorized, null otherwise. */
    private fun getAuthorizedReplyChat(fromId: Long, text: String): String? {
        // Already authorized
        authorizedSessions[fromId]?.let { return it }

        // Try password auth: /auth PASSWORD
        val password = prefs.getString(Config.KEY_PASSWORD, "") ?: ""
        if (password.isNotBlank() && text.startsWith("/auth ")) {
            val provided = text.removePrefix("/auth ").trim()
            if (provided == password) {
                // Grant session — reply goes back to THIS user's chat
                val ownerChatId = prefs.getString(Config.KEY_CHAT_ID, "") ?: ""
                // We don't know their chatId from fromId alone — use fromId as chatId
                // (works for private chats where chatId == userId)
                authorizedSessions[fromId] = fromId.toString()
                return fromId.toString()
            }
        }
        return null
    }

    // =========================================================================
    // Accelerometer
    // =========================================================================

    private fun startAccelerometer() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) { Log.w(TAG, "No accelerometer!"); return }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        lastAccel = event.values.clone()
        if (!prefs.getBoolean(Config.KEY_ALARM_ENABLED, true)) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val delta = abs(sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH)
        val now = System.currentTimeMillis()
        if (delta > Config.MOTION_THRESHOLD && now - lastAlertMs > Config.ALERT_COOLDOWN_MS) {
            lastAlertMs = now
            onMotionDetected(delta)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onMotionDetected(delta: Float) {
        Log.i(TAG, "Motion detected: delta=$delta")
        val alarmOn = prefs.getBoolean(Config.KEY_ALARM_ENABLED, true)
        telegram.sendMessageToOwner(
            "🚨 <b>ALERT — MOTION DETECTED!</b>\nDelta: <b>%.1f m/s²</b>\n%s\n%s"
                .format(delta, locationText(), currentTime()),
            TelegramSender.mainKeyboard(alarmOn)
        )
        if (prefs.getBoolean(Config.KEY_AUTO_PHOTO, false)) {
            camera.takePhoto(useBackCamera = true) { file ->
                if (file != null)
                    telegram.sendPhotoToOwner(file, "📷 Auto-photo on motion\n${currentTime()}")
                else
                    telegram.sendMessageToOwner("⚠️ Auto-photo: camera error")
            }
        }
    }

    // =========================================================================
    // GPS
    // =========================================================================

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { lastLocation = location }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (gpsActive) return
        gpsActive = true
        try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(p, 20_000L, 10f, locationListener)
                    if (lastLocation == null)
                        lastLocation = locationManager.getLastKnownLocation(p)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Location init failed", e) }
    }

    private fun stopLocationUpdates() {
        if (!gpsActive) return
        gpsActive = false
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun requestOnDemandGps(onReady: () -> Unit) {
        gpsStopJob?.let { handler.removeCallbacks(it) }
        if (!gpsActive) startLocationUpdates()

        var fixReceived = false

        val oneShotListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (fixReceived) return
                fixReceived = true
                lastLocation = location
                Log.d(TAG, "On-demand fix: ${location.latitude},${location.longitude} acc=${location.accuracy}m")
                // onReady runs on main thread (handler)
                handler.post {
                    onReady()
                    scheduleGpsStop()
                }
                // Remove from background — safe because we pass handler below
                try { locationManager.removeUpdates(this) } catch (_: Exception) {}
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }

        try {
            // CRITICAL: pass `handler` (main looper) so this works when called from
            // a background Thread (processCommands runs in Thread{}).
            // Without a Looper argument LocationManager silently drops the registration.
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            var registered = false
            providers.forEach { p ->
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(p, 0L, 0f, oneShotListener, handler.looper)
                    registered = true
                    Log.d(TAG, "On-demand GPS registered on provider: $p")
                }
            }
            if (!registered) {
                Log.w(TAG, "No location provider available — returning cached")
                handler.post { onReady(); scheduleGpsStop() }
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "One-shot GPS registration failed", e)
            handler.post { onReady(); scheduleGpsStop() }
            return
        }

        // Timeout fallback: 15s
        handler.postDelayed({
            if (!fixReceived) {
                fixReceived = true
                Log.w(TAG, "On-demand GPS timeout — using cached location")
                try { locationManager.removeUpdates(oneShotListener) } catch (_: Exception) {}
                onReady()
                scheduleGpsStop()
            }
        }, 15_000L)
    }

    private fun scheduleGpsStop() {
        if (!prefs.getBoolean(Config.KEY_GPS_ON_DEMAND, false)) return
        val stopJob = Runnable { stopLocationUpdates() }
        gpsStopJob = stopJob
        handler.postDelayed(stopJob, Config.GPS_ON_DEMAND_TIMEOUT)
    }

    // =========================================================================
    // Runnables
    // =========================================================================

    private val reportRunnable = object : Runnable {
        override fun run() {
            if (prefs.getBoolean(Config.KEY_GPS_ON_DEMAND, false)) {
                requestOnDemandGps { sendStatus() }
            } else {
                sendStatus()
            }
            val delay = prefs.getInt(Config.KEY_INTERVAL_MIN, Config.DEFAULT_INTERVAL_MIN) * 60_000L
            handler.postDelayed(this, delay)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            Thread { processCommands() }.start()
            handler.postDelayed(this, Config.POLL_INTERVAL_MS)
        }
    }

    // =========================================================================
    // Command polling
    // =========================================================================

    private fun processCommands() {
        val updates = telegram.getUpdates(lastUpdateId + 1)
        for (upd in updates) {
            lastUpdateId = maxOf(lastUpdateId, upd.updateId)
            if (upd.callbackQueryId != null) telegram.answerCallback(upd.callbackQueryId)

            val replyChat = getAuthorizedReplyChat(upd.fromId, upd.text)
            if (replyChat == null) {
                // Unauthorized — offer password prompt if password is set
                val password = prefs.getString(Config.KEY_PASSWORD, "") ?: ""
                if (password.isNotBlank()) {
                    telegram.sendToChat(upd.fromId.toString(),
                        "🔒 Access denied.\nSend: <code>/auth YOUR_PASSWORD</code>")
                } else {
                    telegram.sendToChat(upd.fromId.toString(),
                        "🔒 Beacon not configured to accept external commands.")
                }
                Log.w(TAG, "Unauthorized command from fromId=${upd.fromId}: ${upd.text}")
                continue
            }

            Log.d(TAG, "Command from $replyChat: ${upd.text}")
            handleCommand(upd.text, replyChat)
        }
    }

    private fun helpText(alarmOn: Boolean, gpsOnDemand: Boolean) = """
🛡 <b>TelegramBeacon — commands</b>

/foto — rear camera photo
/foto front — front camera photo
/video [N] — video N seconds (default 5)
/video front [N] — front camera video
/audio [N] — microphone recording N sec (default 10)
/gps — current location (Google Maps link)
/find — 🔦 flash + 🔊 beep to locate device
/status — full status report
/on — enable motion alarm
/off — disable motion alarm
/interval N — auto-report interval in minutes (1–120)
/gps_on — GPS always on
/gps_demand — GPS on request only (saves battery)
/help — this message

<i>Security:</i>
/auth PASSWORD — authenticate from another account
/setpass NEW_PASS — change password (owner only)
/revokeall — revoke all guest sessions (owner only)

<i>Status:</i>
Alarm: ${if (alarmOn) "✅ ON" else "🔕 OFF"} | GPS: ${if (gpsOnDemand) "📍 on-demand" else "📡 always on"}
""".trimIndent()

    private fun handleCommand(text: String, replyChat: String) {
        val cmd       = text.lowercase(Locale.ROOT)
        val alarmOn   = prefs.getBoolean(Config.KEY_ALARM_ENABLED, true)
        val gpsOnDemand = prefs.getBoolean(Config.KEY_GPS_ON_DEMAND, false)
        val ownerChat = prefs.getString(Config.KEY_CHAT_ID, "") ?: ""
        val isOwner   = (replyChat == ownerChat)

        when {
            cmd == "/start" || cmd == "/help" -> {
                telegram.sendToChat(replyChat, helpText(alarmOn, gpsOnDemand),
                    TelegramSender.mainKeyboard(alarmOn))
            }

            cmd.startsWith("/auth ") -> {
                // Already authorized (getAuthorizedReplyChat handled it)
                telegram.sendToChat(replyChat,
                    "✅ Authenticated. You can now control the beacon.\nYour responses will appear in <b>this chat</b>.",
                    TelegramSender.mainKeyboard(alarmOn))
            }

            cmd.startsWith("/setpass ") && isOwner -> {
                val newPass = text.removePrefix("/setpass ").removePrefix("/setpass ").trim()
                prefs.edit().putString(Config.KEY_PASSWORD, newPass).apply()
                telegram.sendToChat(replyChat, if (newPass.isBlank())
                    "🔓 Password removed — no authentication required."
                else
                    "🔐 Password updated.")
            }

            cmd == "/revokeall" && isOwner -> {
                val ownerFromId = ownerChat.toLongOrNull() ?: 0L
                authorizedSessions.keys.removeAll { it != ownerFromId }
                telegram.sendToChat(replyChat, "✅ All guest sessions revoked.")
            }

            cmd.startsWith("/foto") || cmd.startsWith("/photo") -> {
                val useBack = !cmd.contains("front")
                telegram.sendToChat(replyChat, "📷 Shooting (${if (useBack) "rear" else "front"} camera)…")
                camera.takePhoto(useBack) { file ->
                    if (file != null)
                        telegram.sendPhotoToChat(replyChat, file, "📷 ${locationText()}\n${currentTime()}")
                    else
                        telegram.sendToChat(replyChat, "❌ Camera error.")
                }
            }

            cmd.startsWith("/video") -> {
                val useBack = !cmd.contains("front")
                val durSec = cmd.split(" ").mapNotNull { it.toIntOrNull() }
                    .firstOrNull()?.coerceIn(1, 60) ?: VideoHelper.DEFAULT_DURATION_SEC
                telegram.sendToChat(replyChat, "🎥 Recording ${durSec}s (${if (useBack) "rear" else "front"} cam)…")
                Thread {
                    video.recordVideo(durSec, useBack) { file ->
                        if (file != null)
                            telegram.sendVideoToChat(replyChat, file, "🎥 ${locationText()}\n${currentTime()}")
                        else
                            telegram.sendToChat(replyChat, "❌ Video error. Camera busy?")
                    }
                }.start()
            }

            cmd.startsWith("/audio") -> {
                val durSec = cmd.split(" ").mapNotNull { it.toIntOrNull() }
                    .firstOrNull()?.coerceIn(1, 120) ?: AudioHelper.DEFAULT_DURATION_SEC
                telegram.sendToChat(replyChat, "🎙 Recording audio ${durSec}s…")
                Thread {
                    audio.recordAudio(durSec) { file ->
                        if (file != null)
                            telegram.sendAudioToChat(replyChat, file, "🎙 ${currentTime()}")
                        else
                            telegram.sendToChat(replyChat, "❌ Audio error.")
                    }
                }.start()
            }

            cmd == "/gps" -> {
                if (gpsOnDemand) {
                    telegram.sendToChat(replyChat, "📍 Acquiring GPS fix (up to 15s)…")
                    requestOnDemandGps {
                        telegram.sendToChat(replyChat, "📍 <b>Location</b>\n${locationText()}\n${currentTime()}")
                    }
                } else {
                    telegram.sendToChat(replyChat, "📍 <b>Location</b>\n${locationText()}\n${currentTime()}")
                }
            }

            cmd == "/find" -> {
                telegram.sendToChat(replyChat, "🔍 Triggering find signal for 10s…")
                Thread { triggerFindSignal() }.start()
            }

            cmd == "/status" -> { sendStatus(replyChat) }

            cmd == "/on" -> {
                prefs.edit().putBoolean(Config.KEY_ALARM_ENABLED, true).apply()
                broadcastAlarmState(true)
                telegram.sendToChat(replyChat, "✅ Motion alarm <b>enabled</b>",
                    TelegramSender.mainKeyboard(true))
            }

            cmd == "/off" -> {
                prefs.edit().putBoolean(Config.KEY_ALARM_ENABLED, false).apply()
                broadcastAlarmState(false)
                telegram.sendToChat(replyChat, "🔕 Motion alarm <b>disabled</b>",
                    TelegramSender.mainKeyboard(false))
            }

            cmd.startsWith("/interval ") -> {
                val min = cmd.removePrefix("/interval ").trim().toIntOrNull()
                if (min != null && min in 1..120) {
                    prefs.edit().putInt(Config.KEY_INTERVAL_MIN, min).apply()
                    handler.removeCallbacks(reportRunnable)
                    handler.postDelayed(reportRunnable, min * 60_000L)
                    telegram.sendToChat(replyChat, "⏱ Auto-report interval: <b>$min min</b>")
                } else {
                    telegram.sendToChat(replyChat, "⚠️ Provide a number 1–120, e.g. <code>/interval 10</code>")
                }
            }

            cmd == "/gps_on" -> {
                prefs.edit().putBoolean(Config.KEY_GPS_ON_DEMAND, false).apply()
                startLocationUpdates()
                telegram.sendToChat(replyChat, "📡 GPS: <b>always on</b>")
            }

            cmd == "/gps_demand" -> {
                prefs.edit().putBoolean(Config.KEY_GPS_ON_DEMAND, true).apply()
                stopLocationUpdates()
                telegram.sendToChat(replyChat, "📍 GPS: <b>on-demand only</b> (saves battery)")
            }
        }
    }

    // =========================================================================
    // /find — flash + beep
    // =========================================================================

    private fun triggerFindSignal() {
        val cameraId = getBackCameraId()
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        val endTime = System.currentTimeMillis() + 10_000L
        try {
            while (System.currentTimeMillis() < endTime) {
                // Flash on
                if (cameraId != null) {
                    try { cameraManager.setTorchMode(cameraId, true)  } catch (_: Exception) {}
                }
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
                Thread.sleep(500)
                // Flash off
                if (cameraId != null) {
                    try { cameraManager.setTorchMode(cameraId, false) } catch (_: Exception) {}
                }
                Thread.sleep(500)
            }
        } catch (_: InterruptedException) {}
        finally {
            if (cameraId != null) {
                try { cameraManager.setTorchMode(cameraId, false) } catch (_: Exception) {}
            }
            tone.release()
        }
    }

    private fun getBackCameraId(): String? {
        return try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (_: Exception) { null }
    }

    // =========================================================================
    // Status report
    // =========================================================================

    private fun sendStatus(replyChat: String? = null) {
        val chat     = replyChat ?: (prefs.getString(Config.KEY_CHAT_ID, "") ?: "")
        val alarmOn  = prefs.getBoolean(Config.KEY_ALARM_ENABLED, true)
        val interval = prefs.getInt(Config.KEY_INTERVAL_MIN, Config.DEFAULT_INTERVAL_MIN)
        val battery  = getBatteryLevel()
        val gpsOnDemand = prefs.getBoolean(Config.KEY_GPS_ON_DEMAND, false)
        val ax = lastAccel[0]
        val ay = lastAccel[1]
        val az = lastAccel[2]
        val sessions = authorizedSessions.size

        val msg = """
📊 <b>Beacon Status</b>
🕐 ${currentTime()}
${locationText()}
📡 Accel: X=%.1f Y=%.1f Z=%.1f m/s²
🔋 Battery: $battery%%
🚨 Alarm: ${if (alarmOn) "✅ ON" else "🔕 OFF"}
📍 GPS: ${if (gpsOnDemand) "on-demand" else "always on"}
⏱ Interval: $interval min
👥 Auth sessions: $sessions
        """.trimIndent().format(ax, ay, az)

        telegram.sendToChat(chat, msg, TelegramSender.mainKeyboard(alarmOn))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun broadcastAlarmState(enabled: Boolean) {
        sendBroadcast(Intent(Config.ACTION_ALARM_STATE_CHANGED).apply {
            putExtra("enabled", enabled); setPackage(packageName)
        })
    }

    private fun locationText(): String {
        val loc = lastLocation ?: return "📍 <i>GPS: no data</i>"
        val lat = "%.6f".format(loc.latitude)
        val lon = "%.6f".format(loc.longitude)
        val acc = "%.0f".format(loc.accuracy)
        val age = (System.currentTimeMillis() - loc.time) / 1000
        return "📍 <a href=\"https://maps.google.com/?q=$lat,$lon\">$lat, $lon</a> (±${acc}m, ${age}s ago)"
    }

    private fun getBatteryLevel() = try {
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    private fun currentTime() =
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())

    // =========================================================================
    // Notification
    // =========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(Config.NOTIFICATION_CHANNEL_ID,
                "Beacon Service", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "TelegramBeacon running"; setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, Config.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TelegramBeacon")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TelegramBeacon::WakeLock")
        wakeLock?.acquire(12 * 3600 * 1000L)
    }
}
