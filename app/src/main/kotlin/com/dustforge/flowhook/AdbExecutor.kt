package com.dustforge.flowhook

import android.content.Context
import android.util.Log
import dadb.Dadb
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes shell commands on the phone via a dadb connection to localhost adbd.
 *
 * This is Flowhook's self-managed shell bridge — replaces Shizuku as of v0.2.0.
 *
 * Prereqs: adbd must be listening on 127.0.0.1 (via `adb tcpip <port>`, which persists
 * across reboots only until adbd is restarted). Flowhook's public key must be accepted
 * by the user on first connect (standard ADB auth prompt).
 */
object AdbExecutor {
    private const val TAG = "FlowhookAdb"
    private const val DEFAULT_PORT = 5555

    data class Result(val stdout: String, val stderr: String, val exit: Int)

    private val dadbRef = AtomicReference<Dadb?>(null)
    @Volatile private var currentPort = DEFAULT_PORT

    fun isReady(): Boolean = dadbRef.get() != null

    @Synchronized
    fun connect(ctx: Context, port: Int = DEFAULT_PORT): Result {
        dadbRef.get()?.let {
            try { (it as? Closeable)?.close() } catch (_: Throwable) {}
        }
        currentPort = port
        return try {
            val keyPair = AdbKeyStore.keyPair(ctx)
            val d = Dadb.create("127.0.0.1", port, keyPair)
            // Probe with a trivial command so authentication completes and any "Allow?" dialog fires
            val probe = d.shell("echo flowhook_ok")
            if (probe.exitCode == 0 && probe.output.contains("flowhook_ok")) {
                dadbRef.set(d)
                Log.i(TAG, "adb bridge up on :$port")
                Result(probe.output, probe.errorOutput, probe.exitCode)
            } else {
                (d as? Closeable)?.close()
                Result(probe.output, "probe failed: exit=${probe.exitCode}\n${probe.errorOutput}", probe.exitCode)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "connect failed on :$port: ${e.message}")
            dadbRef.set(null)
            Result("", "connect failed: ${e.message}", -1)
        }
    }

    fun exec(cmd: String, timeoutMs: Long = 30_000): Result {
        val d = dadbRef.get()
            ?: return Result("", "adb bridge not connected", -2)
        return try {
            // dadb.shell is blocking; for timeout we could use a coroutine wrapper.
            // For simplicity we just call it and rely on socket read timeout behavior.
            val r = d.shell(cmd)
            Result(r.output, r.errorOutput, r.exitCode)
        } catch (e: Throwable) {
            Log.w(TAG, "exec failed (${e.javaClass.simpleName}): ${e.message}")
            dadbRef.set(null)
            Result("", "exec failed: ${e.message}", -3)
        }
    }

    fun disconnect() {
        dadbRef.getAndSet(null)?.let {
            try { (it as? Closeable)?.close() } catch (_: Throwable) {}
        }
    }

    fun currentPort(): Int = currentPort
}
