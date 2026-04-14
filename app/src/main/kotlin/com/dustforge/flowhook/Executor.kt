package com.dustforge.flowhook

import android.content.Context

/**
 * Unified executor façade — prefers self-managed ADB bridge (v0.2+), falls back to Shizuku.
 *
 * CommandHandler and clients should use this instead of AdbExecutor/ShizukuExecutor directly.
 */
object Executor {
    data class Result(val stdout: String, val stderr: String, val exit: Int, val source: String)

    fun exec(cmd: String, timeoutMs: Long = 30_000): Result {
        if (AdbExecutor.isReady()) {
            val r = AdbExecutor.exec(cmd, timeoutMs)
            return Result(r.stdout, r.stderr, r.exit, "adb")
        }
        if (ShizukuExecutor.isReady() && ShizukuExecutor.hasPermission()) {
            val r = ShizukuExecutor.exec(cmd, timeoutMs)
            return Result(r.stdout, r.stderr, r.exit, "shizuku")
        }
        return Result("", "no shell bridge available (adb not connected, shizuku not ready)", -99, "none")
    }

    fun status(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("adb:     ${if (AdbExecutor.isReady()) "connected on :${AdbExecutor.currentPort()}" else "offline"}")
        sb.appendLine("shizuku: ready=${ShizukuExecutor.isReady()} perm=${ShizukuExecutor.hasPermission()}")
        return sb.toString()
    }
}
