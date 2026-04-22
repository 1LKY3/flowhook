package com.dustforge.flowhook

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * v0.3.4: keep ADB-related global settings in the "wireless debugging path still works"
 * state. Samsung OneUI has been observed to silently flip `adb_enabled` off after
 * certain security events / system updates. We want the bridge path to survive that
 * without requiring Kyle to plug into USB and re-enable USB debugging manually.
 *
 * All writes here use `Settings.Global.putInt(...)` from the app process, which
 * requires only the `WRITE_SECURE_SETTINGS` permission (granted once via
 * `pm grant com.dustforge.flowhook android.permission.WRITE_SECURE_SETTINGS` on
 * first pair). No shell bridge needed — that's the whole point. This is the
 * bootstrap rung so the bridge can come back up when the bridge itself is down.
 */
object SettingsGuard {
    private const val TAG = "FlowhookGuard"

    /** Re-assert the settings that make our ADB bridge viable. Returns true iff all
     *  writes completed without a SecurityException. */
    fun reassert(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        var ok = true
        ok = ok and tryPutInt(cr, Settings.Global.ADB_ENABLED, 1)
        // adb_wifi_enabled is the Android 11+ Wireless Debugging toggle. Setting it
        // on is cheap insurance even when persist.adb.tcp.port already keeps adbd
        // listening on 5555 — some Samsung builds prefer the newer path.
        ok = ok and tryPutInt(cr, "adb_wifi_enabled", 1)
        return ok
    }

    private fun tryPutInt(cr: android.content.ContentResolver, key: String, value: Int): Boolean {
        return try {
            val current = Settings.Global.getInt(cr, key, -1)
            if (current == value) return true
            val wrote = Settings.Global.putInt(cr, key, value)
            Log.i(TAG, "reassert $key: was=$current wrote=$value ok=$wrote")
            wrote
        } catch (e: SecurityException) {
            Log.w(TAG, "reassert $key blocked (WRITE_SECURE_SETTINGS not granted?): ${e.message}")
            false
        } catch (e: Throwable) {
            Log.w(TAG, "reassert $key failed: ${e.message}")
            false
        }
    }
}
