package com.oleg.telegrambeacon

object Config {
    const val PREFS_NAME          = "beacon_prefs"
    const val KEY_BOT_TOKEN       = "bot_token"
    const val KEY_CHAT_ID         = "chat_id"           // owner's numeric chat ID
    const val KEY_INTERVAL_MIN    = "interval_min"
    const val KEY_ALARM_ENABLED   = "alarm_enabled"
    const val KEY_AUTO_PHOTO      = "auto_photo"
    const val KEY_AUTOSTART       = "autostart"
    const val KEY_FIRST_RUN       = "first_run"
    const val KEY_PASSWORD        = "access_password"   // optional: "" = no password required
    const val KEY_GPS_ON_DEMAND   = "gps_on_demand"     // true = GPS only on explicit request

    const val NOTIFICATION_CHANNEL_ID = "beacon_channel"
    const val NOTIFICATION_ID         = 1001

    const val DEFAULT_INTERVAL_MIN    = 5
    const val MOTION_THRESHOLD        = 4.0f
    const val ALERT_COOLDOWN_MS       = 30_000L
    const val POLL_INTERVAL_MS        = 8_000L
    const val GPS_ON_DEMAND_TIMEOUT   = 20_000L        // stop GPS after 20s if on-demand mode

    const val LAUNCHER_ALIAS = "com.oleg.telegrambeacon.MainActivityAlias"
    const val ACTION_ALARM_STATE_CHANGED = "com.oleg.telegrambeacon.ALARM_STATE_CHANGED"
}
