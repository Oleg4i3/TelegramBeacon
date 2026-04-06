package com.oleg.telegrambeacon

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs:       SharedPreferences
    private lateinit var etToken:     EditText
    private lateinit var etChatId:    EditText
    private lateinit var etInterval:  EditText
    private lateinit var swAlarm:     Switch
    private lateinit var swAutoPhoto: Switch
    private lateinit var swAutostart: Switch
    private lateinit var btnToggle:   Button
    private lateinit var tvStatus:    TextView

    private val REQUIRED_PERMISSIONS = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs       = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        etToken     = findViewById(R.id.et_token)
        etChatId    = findViewById(R.id.et_chat_id)
        etInterval  = findViewById(R.id.et_interval)
        swAlarm     = findViewById(R.id.sw_alarm)
        swAutoPhoto = findViewById(R.id.sw_auto_photo)
        swAutostart = findViewById(R.id.sw_autostart)
        btnToggle   = findViewById(R.id.btn_toggle)
        tvStatus    = findViewById(R.id.tv_status)

        loadConfig()
        updateUI(BeaconService.isRunning)
        requestMissingPermissions()

        btnToggle.setOnClickListener {
            if (BeaconService.isRunning) {
                stopService(Intent(this, BeaconService::class.java))
                updateUI(false)
            } else {
                val token  = etToken.text.toString().trim()
                val chatId = etChatId.text.toString().trim()
                when {
                    token.isBlank()  -> toast("Введите Bot Token")
                    chatId.isBlank() -> toast("Введите Chat ID")
                    !allPermissionsGranted() -> {
                        toast("Нужны разрешения: камера, геолокация")
                        requestMissingPermissions()
                    }
                    else -> {
                        saveConfig()
                        startForegroundService(Intent(this, BeaconService::class.java))
                        updateUI(true)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI(BeaconService.isRunning)
    }

    // -------------------------------------------------------------------------

    private fun loadConfig() {
        etToken.setText    (prefs.getString(Config.KEY_BOT_TOKEN, ""))
        etChatId.setText   (prefs.getString(Config.KEY_CHAT_ID,   ""))
        etInterval.setText (prefs.getInt(Config.KEY_INTERVAL_MIN, Config.DEFAULT_INTERVAL_MIN).toString())
        swAlarm.isChecked     = prefs.getBoolean(Config.KEY_ALARM_ENABLED, true)
        swAutoPhoto.isChecked = prefs.getBoolean(Config.KEY_AUTO_PHOTO,    false)
        swAutostart.isChecked = prefs.getBoolean(Config.KEY_AUTOSTART,     false)
    }

    private fun saveConfig() {
        prefs.edit()
            .putString (Config.KEY_BOT_TOKEN,    etToken.text.toString().trim())
            .putString (Config.KEY_CHAT_ID,      etChatId.text.toString().trim())
            .putInt    (Config.KEY_INTERVAL_MIN, etInterval.text.toString().toIntOrNull() ?: Config.DEFAULT_INTERVAL_MIN)
            .putBoolean(Config.KEY_ALARM_ENABLED, swAlarm.isChecked)
            .putBoolean(Config.KEY_AUTO_PHOTO,    swAutoPhoto.isChecked)
            .putBoolean(Config.KEY_AUTOSTART,     swAutostart.isChecked)
            .apply()
    }

    private fun updateUI(running: Boolean) {
        btnToggle.text = if (running) "⏹  Остановить маяк" else "▶  Запустить маяк"
        tvStatus.text  = if (running) "🟢 Работает" else "🔴 Остановлен"
        // Disable config fields while running
        val editable = !running
        etToken.isEnabled     = editable
        etChatId.isEnabled    = editable
        etInterval.isEnabled  = editable
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMissingPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
