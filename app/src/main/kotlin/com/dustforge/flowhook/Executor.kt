package com.dustforge.flowhook

import android.content.Context

/**
 * Unified executor façade — routes all shell commands through Flowhook's
 * self-managed ADB bridge.
 *
 * v0.3.3: Shizuku was removed as a legacy dependency. Self-managed ADB is
 * the sole execution path. If the bridge is down, commands fail fast with
 * a single, actionable error — the caller is expected to surface that to
 * the user (notification, UI, CLI exit), not silently fall back to a
 * second mechanism that had its own independent failure modes.
 */
object Executor {
    data class Result(val stdout: String, val stderr: String, val exit: Int, val source: String)

    fun exec(cmd: String, timeoutMs: Long = 30_000): Result {
        if (AdbExecutor.isReady()) {
            val r = AdbExecutor.exec(cmd, timeoutMs)
            return Result(r.stdout, r.stderr, r.exit, "adb")
        }
        return Result("", "ADB bridge not connected — enable tcpip and reopen Flowhook", -99, "none")
    }

    fun status(ctx: Context): String =
        "adb: ${if (AdbExecutor.isReady()) "connected on :${AdbExecutor.currentPort()}" else "offline"}"
}
