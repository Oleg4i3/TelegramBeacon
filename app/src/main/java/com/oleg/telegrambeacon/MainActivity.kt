package com.oleg.telegrambeacon

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs:       SharedPreferences
    private lateinit var etToken:     EditText
    private lateinit var etChatId:    EditText
    private lateinit var etInterval:  EditText
    private lateinit var etPassword:  EditText
    private lateinit var swAlarm:     Switch
    private lateinit var swAutoPhoto: Switch
    private lateinit var swAutostart: Switch
    private lateinit var swGpsDemand: Switch
    private lateinit var btnToggle:   Button
    private lateinit var btnHelp:     Button
    private lateinit var btnHideIcon: Button
    private lateinit var tvStatus:    TextView

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            swAlarm.isChecked = intent.getBooleanExtra("enabled", true)
        }
    }

    private val REQUIRED_PERMISSIONS = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs       = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        etToken     = findViewById(R.id.et_token)
        etChatId    = findViewById(R.id.et_chat_id)
        etInterval  = findViewById(R.id.et_interval)
        etPassword  = findViewById(R.id.et_password)
        swAlarm     = findViewById(R.id.sw_alarm)
        swAutoPhoto = findViewById(R.id.sw_auto_photo)
        swAutostart = findViewById(R.id.sw_autostart)
        swGpsDemand = findViewById(R.id.sw_gps_demand)
        btnToggle   = findViewById(R.id.btn_toggle)
        btnHelp     = findViewById(R.id.btn_help)
        btnHideIcon = findViewById(R.id.btn_hide_icon)
        tvStatus    = findViewById(R.id.tv_status)

        loadConfig()
        updateUI(BeaconService.isRunning)
        requestMissingPermissions()

        if (prefs.getBoolean(Config.KEY_FIRST_RUN, true)) {
            prefs.edit().putBoolean(Config.KEY_FIRST_RUN, false).apply()
            showSetupInstructions()
        }

        btnToggle.setOnClickListener {
            if (BeaconService.isRunning) {
                stopService(Intent(this, BeaconService::class.java))
                updateUI(false)
            } else {
                val token  = etToken.text.toString().trim()
                val chatId = etChatId.text.toString().trim()
                when {
                    token.isBlank()  -> toast("Enter Bot Token")
                    chatId.isBlank() -> toast("Enter Chat ID")
                    !allPermissionsGranted() -> {
                        toast("Permissions required: camera, microphone, location")
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

        btnHelp.setOnClickListener { showSetupInstructions() }
        btnHideIcon.setOnClickListener { confirmHideIcon() }

        swAlarm.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Config.KEY_ALARM_ENABLED, checked).apply()
        }
        swGpsDemand.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Config.KEY_GPS_ON_DEMAND, checked).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI(BeaconService.isRunning)
        swAlarm.isChecked     = prefs.getBoolean(Config.KEY_ALARM_ENABLED, true)
        swGpsDemand.isChecked = prefs.getBoolean(Config.KEY_GPS_ON_DEMAND, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alarmReceiver,
                IntentFilter(Config.ACTION_ALARM_STATE_CHANGED), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(alarmReceiver, IntentFilter(Config.ACTION_ALARM_STATE_CHANGED))
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(alarmReceiver) } catch (_: Exception) {}
    }

    private fun showSetupInstructions() {
        val msg = """
<b>Step 1 — Create a bot</b>
Open Telegram → find <b>@BotFather</b>
Send /newbot → follow prompts → copy the <b>Token</b>

<b>Step 2 — Get your Chat ID</b>
Find <b>@userinfobot</b> in Telegram
Send /start → copy the numeric <b>ID</b>

<b>Step 3 — Configure this app</b>
Paste Token and Chat ID below
Set an access password to restrict who can send commands
Set interval, enable alarm → tap Start

<b>Step 4 — Keep alive</b>
Disable battery optimization for this app
(Settings → Apps → TelegramBeacon → Battery → Unrestricted)

<b>Security:</b>
Only your Chat ID can control the beacon by default.
Set a password to allow other accounts via /auth PASSWORD.
Responses always go to the sender's chat.

<b>GPS options:</b>
• Always on — GPS icon visible, best accuracy
• On-demand — GPS activates on /gps or /status, auto-off after 20s

<b>Commands:</b>
/foto · /video · /audio · /gps · /find
/on · /off · /status · /interval N · /help
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("🛡 TelegramBeacon — Setup")
            .setMessage(android.text.Html.fromHtml(msg, android.text.Html.FROM_HTML_MODE_LEGACY))
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun confirmHideIcon() {
        AlertDialog.Builder(this)
            .setTitle("Hide app icon?")
            .setMessage(
                "The launcher icon will be removed.\n\n" +
                "To reopen: Settings → Apps → TelegramBeacon → Open\n\n" +
                "The beacon continues running normally."
            )
            .setPositiveButton("Hide") { _, _ -> setIconVisible(false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setIconVisible(visible: Boolean) {
        val alias = ComponentName(this, "$packageName.MainActivityAlias")
        val state = if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else         PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
        if (!visible) {
            toast("Icon hidden. Find app in Settings → Apps.")
            btnHideIcon.text = "Show app icon"
            btnHideIcon.setOnClickListener {
                setIconVisible(true)
                btnHideIcon.text = "Hide app icon"
                btnHideIcon.setOnClickListener { confirmHideIcon() }
            }
        }
    }

    private fun loadConfig() {
        etToken.setText    (prefs.getString(Config.KEY_BOT_TOKEN,  ""))
        etChatId.setText   (prefs.getString(Config.KEY_CHAT_ID,    ""))
        etInterval.setText (prefs.getInt(Config.KEY_INTERVAL_MIN,  Config.DEFAULT_INTERVAL_MIN).toString())
        etPassword.setText (prefs.getString(Config.KEY_PASSWORD,   ""))
        swAlarm.isChecked      = prefs.getBoolean(Config.KEY_ALARM_ENABLED, true)
        swAutoPhoto.isChecked  = prefs.getBoolean(Config.KEY_AUTO_PHOTO,    false)
        swAutostart.isChecked  = prefs.getBoolean(Config.KEY_AUTOSTART,     false)
        swGpsDemand.isChecked  = prefs.getBoolean(Config.KEY_GPS_ON_DEMAND, false)
    }

    private fun saveConfig() {
        prefs.edit()
            .putString (Config.KEY_BOT_TOKEN,    etToken.text.toString().trim())
            .putString (Config.KEY_CHAT_ID,      etChatId.text.toString().trim())
            .putInt    (Config.KEY_INTERVAL_MIN, etInterval.text.toString().toIntOrNull() ?: Config.DEFAULT_INTERVAL_MIN)
            .putString (Config.KEY_PASSWORD,     etPassword.text.toString().trim())
            .putBoolean(Config.KEY_ALARM_ENABLED, swAlarm.isChecked)
            .putBoolean(Config.KEY_AUTO_PHOTO,    swAutoPhoto.isChecked)
            .putBoolean(Config.KEY_AUTOSTART,     swAutostart.isChecked)
            .putBoolean(Config.KEY_GPS_ON_DEMAND, swGpsDemand.isChecked)
            .apply()
    }

    private fun updateUI(running: Boolean) {
        btnToggle.text = if (running) "⏹  Stop Beacon" else "▶  Start Beacon"
        tvStatus.text  = if (running) "🟢 Running" else "🔴 Stopped"
        val editable = !running
        etToken.isEnabled    = editable
        etChatId.isEnabled   = editable
        etInterval.isEnabled = editable
        etPassword.isEnabled = editable
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMissingPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(this, missing, 100)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
