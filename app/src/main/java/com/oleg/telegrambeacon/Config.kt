package com.oleg.telegrambeacon

object Config {
    const val PREFS_NAME          = "beacon_prefs"
    const val KEY_BOT_TOKEN       = "bot_token"
    const val KEY_CHAT_ID         = "chat_id"
    const val KEY_INTERVAL_MIN    = "interval_min"
    const val KEY_ALARM_ENABLED   = "alarm_enabled"
    const val KEY_AUTO_PHOTO      = "auto_photo"
    const val KEY_AUTOSTART       = "autostart"
    const val KEY_FIRST_RUN       = "first_run"

    const val NOTIFICATION_CHANNEL_ID = "beacon_channel"
    const val NOTIFICATION_ID         = 1001

    const val DEFAULT_INTERVAL_MIN    = 5
    const val MOTION_THRESHOLD        = 4.0f
    const val ALERT_COOLDOWN_MS       = 30_000L
    const val POLL_INTERVAL_MS        = 8_000L

    // Activity-alias name declared in AndroidManifest
    const val LAUNCHER_ALIAS = "com.oleg.telegrambeacon.MainActivityAlias"

    // Local broadcast: BeaconService → MainActivity when /on or /off received via bot
    const val ACTION_ALARM_STATE_CHANGED = "com.oleg.telegrambeacon.ALARM_STATE_CHANGED"
}
