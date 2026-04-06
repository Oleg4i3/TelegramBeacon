package com.oleg.telegrambeacon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Auto-starts BeaconService after device reboot,
 * but only if a bot token is already saved AND autostart is enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        val token     = prefs.getString(Config.KEY_BOT_TOKEN, "") ?: ""
        val autostart = prefs.getBoolean(Config.KEY_AUTOSTART, false)

        if (token.isNotBlank() && autostart) {
            Log.i("BootReceiver", "Auto-starting BeaconService")
            ContextCompat.startForegroundService(
                context,
                Intent(context, BeaconService::class.java)
            )
        }
    }
}
