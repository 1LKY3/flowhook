package com.dustforge.flowhook

import android.content.Context
import android.util.Log
import dadb.Dadb
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Executes shell commands on the phone via a dadb connection to localhost adbd.
 *
 * This is Flowhook's sole shell execution path as of v0.3.3.
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

    // v0.3.4: expose the bridge state as a StateFlow so MainActivity's status
    // screen and FlowhookService's notification can reflect the real ADB link
    // state the same way v0.3.2 did for the WebSocket. No more "green toggles
    // while the bridge is dead" ambiguity.
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

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
                _ready.value = true
                Log.i(TAG, "adb bridge up on :$port")
                Result(probe.output, probe.errorOutput, probe.exitCode)
            } else {
                (d as? Closeable)?.close()
                _ready.value = false
                Result(probe.output, "probe failed: exit=${probe.exitCode}\n${probe.errorOutput}", probe.exitCode)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "connect failed on :$port: ${e.message}")
            dadbRef.set(null)
            _ready.value = false
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
            _ready.value = false
            Result("", "exec failed: ${e.message}", -3)
        }
    }

    fun disconnect() {
        dadbRef.getAndSet(null)?.let {
            try { (it as? Closeable)?.close() } catch (_: Throwable) {}
        }
        _ready.value = false
    }

    fun currentPort(): Int = currentPort
}
