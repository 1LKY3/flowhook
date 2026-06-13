package com.dustforge.flowhook

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * ADB recovery helpers.
 *
 * These methods restart adbd by writing Android global settings. cycleAdb()
 * is called automatically by the ADB supervisor after 3 consecutive connect
 * failures (e.g. phone reboot) and periodically thereafter.
 */
object SettingsGuard {
    private const val TAG = "FlowhookGuard"

    data class RecoveryResult(
        val ok: Boolean,
        val beforeReady: Boolean,
        val afterReady: Boolean,
        val settingsOk: Boolean,
        val cycleOk: Boolean,
        val tcpipOk: Boolean,
        val stdout: String,
        val stderr: String,
    )

    /** Re-assert the settings that make our ADB bridge viable. Returns true iff all
     *  writes completed without a SecurityException. */
    fun reassert(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        var ok = true
        ok = ok and tryPutInt(cr, Settings.Global.ADB_ENABLED, 1)
        ok = ok and tryPutInt(cr, "adb_wifi_enabled", 1)
        return ok
    }

    /**
     * Re-arm legacy TCP ADB on localhost:5555 using the ADB protocol itself,
     * equivalent to `adb tcpip 5555` from a computer. This must run while some
     * ADB connection is already alive; when the bridge is totally down,
     * recoverAdbBridge first reasserts settings/cycles adbd and tries direct
     * connect plus local discovery.
     */
    fun ensureTcpip(ctx: Context): Boolean {
        val r = AdbExecutor.ensureTcpipMode(ctx)
        val ok = r.exit == 0
        if (ok) {
            Log.i(TAG, "ensureTcpip: ${r.stdout.trim()}")
        } else {
            Log.w(TAG, "ensureTcpip failed: exit=${r.exit} stdout=${r.stdout.trim()} stderr=${r.stderr.trim()}")
        }
        return ok
    }

    /** Cycle adb_enabled 0→1 to force adbd restart. When persist.adb.tcp.port=5555
     *  is set, the restarted adbd will listen on TCP again. Use this when the ADB
     *  bridge is down and normal connect() can't reach localhost:5555. */
    fun cycleAdb(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        try {
            val was = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 1)
            if (was == 1) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0)
                Thread.sleep(500)
            }
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            Log.i(TAG, "cycleAdb: toggled adb_enabled 0→1 to restart adbd")
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "cycleAdb blocked: ${e.message}")
        } catch (e: Throwable) {
            Log.w(TAG, "cycleAdb failed: ${e.message}")
        }
        return false
    }

    /** Explicit user-triggered recovery. Never call from background loops. */
    fun recoverAdbBridge(ctx: Context): RecoveryResult {
        val beforeReady = AdbExecutor.isReady()
        if (beforeReady) {
            return RecoveryResult(
                ok = true,
                beforeReady = true,
                afterReady = true,
                settingsOk = false,
                cycleOk = false,
                tcpipOk = ensureTcpip(ctx),
                stdout = "ADB bridge already connected",
                stderr = "",
            )
        }

        val first = AdbExecutor.connect(ctx)
        if (first.exit == 0) {
            val tcpipOk = ensureTcpip(ctx)
            return RecoveryResult(
                ok = true,
                beforeReady = false,
                afterReady = true,
                settingsOk = false,
                cycleOk = false,
                tcpipOk = tcpipOk,
                stdout = first.stdout,
                stderr = first.stderr,
            )
        }

        val settingsOk = reassert(ctx)
        val cycleOk = cycleAdb(ctx)
        try {
            Thread.sleep(2_500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        AdbExecutor.disconnect()
        var second = AdbExecutor.connect(ctx)
        if (second.exit != 0) {
            val discovered = AdbExecutor.discover(ctx)
            if (discovered.exit == 0) second = discovered
        }
        val afterReady = second.exit == 0
        val tcpipOk = afterReady && ensureTcpip(ctx)
        return RecoveryResult(
            ok = afterReady,
            beforeReady = false,
            afterReady = afterReady,
            settingsOk = settingsOk,
            cycleOk = cycleOk,
            tcpipOk = tcpipOk,
            stdout = second.stdout,
            stderr = listOf(first.stderr, second.stderr)
                .filter { it.isNotBlank() }
                .joinToString("\n"),
        )
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
