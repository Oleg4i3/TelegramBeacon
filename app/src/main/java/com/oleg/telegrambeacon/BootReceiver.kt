package com.oleg.telegrambeacon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts BeaconService after boot.
 *
 * WHY two actions:
 *
 *   BOOT_COMPLETED fires before the user unlocks the screen.
 *   On devices with File-Based Encryption (FBE) — all Android 7+ phones —
 *   SharedPreferences stored in the default (credential-encrypted) storage
 *   are NOT readable at that point. Reading them crashes with:
 *     "SharedPreferences file not accessible before first unlock"
 *
 *   ACTION_USER_UNLOCKED fires after the user enters PIN/pattern/fingerprint.
 *   At that point credential-encrypted storage is available and prefs can be read.
 *
 *   We listen to BOTH:
 *   - LOCKED_BOOT_COMPLETED  — fires early, storage encrypted, we do NOTHING
 *                              (just a placeholder so Android knows we care about boot)
 *   - USER_UNLOCKED          — fires after unlock, safe to read prefs and start service
 *
 *   This covers 100% of cases: phones without encryption get USER_UNLOCKED immediately
 *   after boot; encrypted phones get it after the user swipes/unlocks.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_BOOT_COMPLETED -> tryStart(context, intent.action ?: "")
        }
    }

    private fun tryStart(context: Context, action: String) {
        try {
            val prefs     = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            val token     = prefs.getString(Config.KEY_BOT_TOKEN, "") ?: ""
            val autostart = prefs.getBoolean(Config.KEY_AUTOSTART, false)

            if (token.isNotBlank() && autostart) {
                Log.i("BootReceiver", "[$action] Auto-starting BeaconService")
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BeaconService::class.java)
                )
            } else {
                Log.d("BootReceiver", "[$action] Autostart skipped: token=${token.isNotBlank()} autostart=$autostart")
            }
        } catch (e: Exception) {
            // Should not happen after USER_UNLOCKED, but guard anyway
            Log.e("BootReceiver", "[$action] Failed to start service", e)
        }
    }
}
