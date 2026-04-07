package com.oleg.telegrambeacon

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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

        private val HELP_TEXT = """
🛡 <b>TelegramBeacon — commands</b>

/foto — rear camera photo
/foto front — front camera photo
/video [N] — video N seconds (default 5), rear cam
/video front [N] — video, front cam
/audio [N] — microphone recording N seconds (default 10)
/gps — current location (Google Maps link)
/status — full status report
/on — enable motion alarm
/off — disable motion alarm
/interval N — auto-report interval in minutes (1–120)
/help — this help message

<i>Automatic events:</i>
• Every N minutes — status report
• Motion detected — 🚨 alert (+ photo/video if enabled)
""".trimIndent()
    }

    private lateinit var prefs:           SharedPreferences
    private lateinit var telegram:        TelegramSender
    private lateinit var camera:          CameraHelper
    private lateinit var video:           VideoHelper
    private lateinit var audio:           AudioHelper
    private lateinit var sensorManager:   SensorManager
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val  handler      = Handler(Looper.getMainLooper())
    private var  lastUpdateId = 0L
    private var  lastLocation: Location? = null
    private var  lastAccel    = floatArrayOf(0f, 0f, 9.8f)
    private var  lastAlertMs  = 0L

    private val reportRunnable = object : Runnable {
        override fun run() {
            sendStatus()
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

        createNotificationChannel()
        startForeground(Config.NOTIFICATION_ID, buildNotification("🟢 Active"))

        acquireWakeLock()
        startLocationUpdates()
        startAccelerometer()

        handler.postDelayed(pollRunnable, 3_000L)
        handler.post(reportRunnable)

        val alarmOn = prefs.getBoolean(Config.KEY_ALARM_ENABLED, true)
        telegram.sendMessage(
            "🟢 <b>Beacon started</b>\n${currentTime()}\n\nSend /help for commands.",
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
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        wakeLock?.release()
        telegram.sendMessage("🔴 <b>Beacon stopped</b>\n${currentTime()}")
        Log.i(TAG, "Service stopped")
    }

    override fun onBind(intent: Intent?) = null

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
        val (x, y, z) = event.values
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
        telegram.sendMessage(
            "🚨 <b>ALERT — MOTION DETECTED!</b>\nDelta: <b>%.1f m/s²</b>\n%s\n%s"
                .format(delta, locationText(), currentTime()),
            TelegramSender.mainKeyboard(alarmOn)
        )
        if (prefs.getBoolean(Config.KEY_AUTO_PHOTO, false)) {
            camera.takePhoto(useBackCamera = true) { file ->
                if (file != null)
                    telegram.sendPhoto(file, "📷 Auto-photo on motion\n${currentTime()}")
                else
                    telegram.sendMessage("⚠️ Auto-photo: camera error")
            }
        }
    }

    // =========================================================================
    // Location
    // =========================================================================

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { lastLocation = location }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
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

    // =========================================================================
    // Command polling
    // =========================================================================

    private fun processCommands() {
        val updates = telegram.getUpdates(lastUpdateId + 1)
        for (upd in updates) {
            lastUpdateId = maxOf(lastUpdateId, upd.updateId)
            // Acknowledge button press immediately (removes Telegram spinner)
            if (upd.callbackQueryId != null)
                telegram.answerCallback(upd.callbackQueryId)
            Log.d(TAG, "Command: ${upd.text}")
            handleCommand(upd.text)
        }
    }

    private fun handleCommand(text: String) {
        val cmd = text.lowercase(Locale.ROOT)
        val alarmOn = prefs.getBoolean(Config.KEY_ALARM_ENABLED, true)

        when {
            cmd == "/start" || cmd == "/help" -> {
                telegram.sendMessage(HELP_TEXT, TelegramSender.mainKeyboard(alarmOn))
            }

            cmd.startsWith("/foto") || cmd.startsWith("/photo") -> {
                val useBack = !cmd.contains("front")
                telegram.sendMessage("📷 Shooting (${if (useBack) "rear" else "front"} camera)…")
                camera.takePhoto(useBack) { file ->
                    if (file != null)
                        telegram.sendPhoto(file, "📷 ${locationText()}\n${currentTime()}")
                    else
                        telegram.sendMessage("❌ Camera error. Is another app using it?")
                }
            }

            cmd.startsWith("/video") -> {
                val useBack = !cmd.contains("front")
                val durSec = cmd.split(" ").mapNotNull { it.toIntOrNull() }
                    .firstOrNull()?.coerceIn(1, 60) ?: VideoHelper.DEFAULT_DURATION_SEC
                telegram.sendMessage("🎥 Recording video ${durSec}s (${if (useBack) "rear" else "front"} cam)…")
                Thread {
                    video.recordVideo(durSec, useBack) { file ->
                        if (file != null)
                            telegram.sendVideo(file, "🎥 ${locationText()}\n${currentTime()}")
                        else
                            telegram.sendMessage("❌ Video error. Camera busy?")
                    }
                }.start()
            }

            cmd.startsWith("/audio") -> {
                val durSec = cmd.split(" ").mapNotNull { it.toIntOrNull() }
                    .firstOrNull()?.coerceIn(1, 120) ?: AudioHelper.DEFAULT_DURATION_SEC
                telegram.sendMessage("🎙 Recording audio ${durSec}s…")
                Thread {
                    audio.recordAudio(durSec) { file ->
                        if (file != null)
                            telegram.sendAudio(file, "🎙 ${currentTime()}")
                        else
                            telegram.sendMessage("❌ Audio error. Microphone busy?")
                    }
                }.start()
            }

            cmd == "/gps" -> {
                telegram.sendMessage("📍 <b>Location</b>\n${locationText()}\n${currentTime()}")
            }

            cmd == "/status" -> {
                sendStatus()
            }

            cmd == "/on" -> {
                prefs.edit().putBoolean(Config.KEY_ALARM_ENABLED, true).apply()
                broadcastAlarmState(true)
                telegram.sendMessage(
                    "✅ Motion alarm <b>enabled</b>",
                    TelegramSender.mainKeyboard(true)
                )
            }

            cmd == "/off" -> {
                prefs.edit().putBoolean(Config.KEY_ALARM_ENABLED, false).apply()
                broadcastAlarmState(false)
                telegram.sendMessage(
                    "🔕 Motion alarm <b>disabled</b>",
                    TelegramSender.mainKeyboard(false)
                )
            }

            cmd.startsWith("/interval ") -> {
                val min = cmd.removePrefix("/interval ").trim().toIntOrNull()
                if (min != null && min in 1..120) {
                    prefs.edit().putInt(Config.KEY_INTERVAL_MIN, min).apply()
                    handler.removeCallbacks(reportRunnable)
                    handler.postDelayed(reportRunnable, min * 60_000L)
                    telegram.sendMessage("⏱ Auto-report interval: <b>$min min</b>")
                } else {
                    telegram.sendMessage("⚠️ Provide a number 1–120, e.g.:\n<code>/interval 10</code>")
                }
            }
        }
    }

    // Notify MainActivity so the switch updates without needing a restart
    private fun broadcastAlarmState(enabled: Boolean) {
        val intent = Intent(Config.ACTION_ALARM_STATE_CHANGED).apply {
            putExtra("enabled", enabled)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // =========================================================================
    // Status report
    // =========================================================================

    private fun sendStatus() {
        val alarmOn  = prefs.getBoolean(Config.KEY_ALARM_ENABLED, true)
        val interval = prefs.getInt(Config.KEY_INTERVAL_MIN, Config.DEFAULT_INTERVAL_MIN)
        val battery  = getBatteryLevel()
        val (ax, ay, az) = lastAccel

        val msg = """
📊 <b>Beacon Status</b>
🕐 ${currentTime()}
${locationText()}
📡 Accelerometer: X=%.1f Y=%.1f Z=%.1f m/s²
🔋 Battery: ${battery}%%
🚨 Motion alarm: ${if (alarmOn) "✅ ON" else "🔕 OFF"}
⏱ Interval: $interval min
        """.trimIndent().format(ax, ay, az)

        telegram.sendMessage(msg, TelegramSender.mainKeyboard(alarmOn))
        Log.d(TAG, "Status sent")
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun locationText(): String {
        val loc = lastLocation ?: return "📍 <i>GPS: no data</i>"
        val lat = "%.6f".format(loc.latitude)
        val lon = "%.6f".format(loc.longitude)
        val acc = "%.0f".format(loc.accuracy)
        val age = (System.currentTimeMillis() - loc.time) / 1000
        return "📍 <a href=\"https://maps.google.com/?q=$lat,$lon\">$lat, $lon</a> (±${acc}m, ${age}s ago)"
    }

    private fun getBatteryLevel(): Int = try {
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    private fun currentTime(): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())

    // =========================================================================
    // Notification
    // =========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                Config.NOTIFICATION_CHANNEL_ID, "Beacon Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "TelegramBeacon running in background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Config.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TelegramBeacon")
            .setContentText(statusText)
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
