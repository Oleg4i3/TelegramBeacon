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
🛡 <b>TelegramBeacon — команды</b>

/foto — фото задней камерой
/foto front — фото передней камерой
/gps — текущая геолокация (ссылка на карты)
/status — полный статус
/on — включить сигнализацию (движение)
/off — выключить сигнализацию
/interval N — интервал автоотчётов в минутах (1–120)
/help — эта справка

<i>Авто-события:</i>
• Каждые N минут — /status
• При обнаружении движения — 🚨 тревога (+ фото, если включено)
""".trimIndent()
    }

    // --- Services & helpers --------------------------------------------------
    private lateinit var prefs:          SharedPreferences
    private lateinit var telegram:       TelegramSender
    private lateinit var camera:         CameraHelper
    private lateinit var sensorManager:  SensorManager
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    // --- State ---------------------------------------------------------------
    private val  handler      = Handler(Looper.getMainLooper())
    private var  lastUpdateId = 0L
    private var  lastLocation: Location? = null
    private var  lastAccel    = floatArrayOf(0f, 0f, 9.8f)
    private var  lastAlertMs  = 0L

    // --- Runnables -----------------------------------------------------------

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

        prefs         = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        telegram      = TelegramSender(prefs)
        camera        = CameraHelper(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        createNotificationChannel()
        startForeground(Config.NOTIFICATION_ID, buildNotification("🟢 Активен"))

        acquireWakeLock()
        startLocationUpdates()
        startAccelerometer()

        // First poll after 3 s, then every POLL_INTERVAL_MS
        handler.postDelayed(pollRunnable, 3_000L)
        // First report immediately
        handler.post(reportRunnable)

        telegram.sendMessage("🟢 <b>Маяк запущен</b>\n${currentTime()}\n\nОтправь /help для списка команд.")
        Log.i(TAG, "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY   // restart automatically if killed by OS

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(reportRunnable)
        handler.removeCallbacks(pollRunnable)
        sensorManager.unregisterListener(this)
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        wakeLock?.release()
        telegram.sendMessage("🔴 <b>Маяк остановлен</b>\n${currentTime()}")
        Log.i(TAG, "Service stopped")
    }

    override fun onBind(intent: Intent?) = null

    // =========================================================================
    // Accelerometer
    // =========================================================================

    private fun startAccelerometer() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            Log.w(TAG, "No accelerometer!")
            return
        }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        lastAccel = event.values.clone()

        if (!prefs.getBoolean(Config.KEY_ALARM_ENABLED, true)) return

        val (x, y, z) = event.values
        val magnitude = sqrt(x * x + y * y + z * z)
        val delta = abs(magnitude - SensorManager.GRAVITY_EARTH)

        val now = System.currentTimeMillis()
        if (delta > Config.MOTION_THRESHOLD && now - lastAlertMs > Config.ALERT_COOLDOWN_MS) {
            lastAlertMs = now
            onMotionDetected(delta)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onMotionDetected(delta: Float) {
        Log.i(TAG, "Motion detected: delta=$delta")
        val msg = "🚨 <b>ТРЕВОГА — ДВИЖЕНИЕ!</b>\nОтклонение: <b>%.1f m/s²</b>\n%s\n%s"
            .format(delta, locationText(), currentTime())
        telegram.sendMessage(msg)

        if (prefs.getBoolean(Config.KEY_AUTO_PHOTO, false)) {
            camera.takePhoto(useBackCamera = true) { file ->
                if (file != null)
                    telegram.sendPhoto(file, "📷 Авто-фото при движении\n${currentTime()}")
                else
                    telegram.sendMessage("⚠️ Авто-фото: ошибка камеры")
            }
        }
    }

    // =========================================================================
    // Location
    // =========================================================================

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            providers.forEach { provider ->
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 20_000L, 10f, locationListener)
                    // seed with last known immediately
                    if (lastLocation == null) {
                        lastLocation = locationManager.getLastKnownLocation(provider)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location init failed", e)
        }
    }

    // =========================================================================
    // Command polling
    // =========================================================================

    private fun processCommands() {
        val updates = telegram.getUpdates(lastUpdateId + 1)
        for (upd in updates) {
            lastUpdateId = maxOf(lastUpdateId, upd.updateId)
            Log.d(TAG, "Command: ${upd.text}")
            handleCommand(upd.text)
        }
    }

    private fun handleCommand(text: String) {
        val cmd = text.lowercase(Locale.ROOT)
        when {
            cmd == "/start" || cmd == "/help" -> {
                telegram.sendMessage(HELP_TEXT)
            }

            cmd.startsWith("/foto") || cmd.startsWith("/photo") -> {
                val useBack = !cmd.contains("front")
                telegram.sendMessage("📷 Снимаю (${if (useBack) "задняя" else "передняя"} камера)…")
                camera.takePhoto(useBack) { file ->
                    if (file != null) {
                        val loc = locationText()
                        telegram.sendPhoto(file, "📷 $loc\n${currentTime()}")
                    } else {
                        telegram.sendMessage("❌ Ошибка камеры. Убедитесь, что камера не занята.")
                    }
                }
            }

            cmd == "/gps" || cmd == "📍" -> {
                telegram.sendMessage("📍 <b>Геолокация</b>\n${locationText()}\n${currentTime()}")
            }

            cmd == "/status" || cmd == "ℹ️" -> {
                sendStatus()
            }

            cmd == "/on" || cmd == "/alarm_on" -> {
                prefs.edit().putBoolean(Config.KEY_ALARM_ENABLED, true).apply()
                telegram.sendMessage("✅ Сигнализация <b>включена</b>")
            }

            cmd == "/off" || cmd == "/alarm_off" -> {
                prefs.edit().putBoolean(Config.KEY_ALARM_ENABLED, false).apply()
                telegram.sendMessage("🔕 Сигнализация <b>отключена</b>")
            }

            cmd.startsWith("/interval ") -> {
                val min = cmd.removePrefix("/interval ").trim().toIntOrNull()
                if (min != null && min in 1..120) {
                    prefs.edit().putInt(Config.KEY_INTERVAL_MIN, min).apply()
                    handler.removeCallbacks(reportRunnable)
                    handler.postDelayed(reportRunnable, min * 60_000L)
                    telegram.sendMessage("⏱ Интервал отчётов: <b>$min мин</b>")
                } else {
                    telegram.sendMessage("⚠️ Укажите число от 1 до 120, например:\n<code>/interval 10</code>")
                }
            }
        }
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
📊 <b>Статус маяка</b>
🕐 ${currentTime()}
${locationText()}
📡 Акселерометр: X=%.1f Y=%.1f Z=%.1f m/s²
🔋 Батарея: ${battery}%%
🚨 Сигнализация: ${if (alarmOn) "✅ вкл" else "🔕 выкл"}
⏱ Интервал: $interval мин
        """.trimIndent().format(ax, ay, az)

        telegram.sendMessage(msg)
        Log.d(TAG, "Status sent")
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun locationText(): String {
        val loc = lastLocation ?: return "📍 <i>GPS: нет данных</i>"
        val lat = "%.6f".format(loc.latitude)
        val lon = "%.6f".format(loc.longitude)
        val acc = "%.0f".format(loc.accuracy)
        val age = (System.currentTimeMillis() - loc.time) / 1000
        return "📍 <a href=\"https://maps.google.com/?q=$lat,$lon\">$lat, $lon</a> (±${acc}м, ${age}с назад)"
    }

    private fun getBatteryLevel(): Int {
        return try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) { -1 }
    }

    private fun currentTime(): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())

    // =========================================================================
    // Notification
    // =========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Config.NOTIFICATION_CHANNEL_ID,
                "Beacon Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description    = "TelegramBeacon работает в фоне"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
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
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TelegramBeacon::WakeLock"
        )
        wakeLock?.acquire(12 * 3600 * 1000L)  // max 12 hours
    }
}
