package com.dustforge.flowhook

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Execute shell commands via Shizuku's privileged process (runs as shell user, uid 2000).
 */
object ShizukuExecutor {

    data class Result(val stdout: String, val stderr: String, val exit: Int)

    fun isReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.getVersion() >= 10
    } catch (e: Throwable) { false }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun exec(cmd: String, timeoutMs: Long = 30_000): Result {
        if (!isReady()) return Result("", "Shizuku not running", -1)
        if (!hasPermission()) return Result("", "Shizuku permission not granted", -2)

        // Shizuku.newProcess executes as the Shizuku user (shell/root depending on start mode)
        val args = arrayOf("sh", "-c", cmd)
        @Suppress("UNCHECKED_CAST")
        val newProcessMethod = Shizuku::class.java.declaredMethods.firstOrNull { it.name == "newProcess" }
            ?: return Result("", "Shizuku.newProcess unavailable", -3)
        newProcessMethod.isAccessible = true
        val proc = try {
            newProcessMethod.invoke(null, args, null, null) as Process
        } catch (e: Throwable) {
            return Result("", "newProcess failed: ${e.message}", -4)
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val tOut = Thread { BufferedReader(InputStreamReader(proc.inputStream)).useLines { it.forEach { stdout.appendLine(it) } } }
        val tErr = Thread { BufferedReader(InputStreamReader(proc.errorStream)).useLines { it.forEach { stderr.appendLine(it) } } }
        tOut.start(); tErr.start()

        // Poll for exit; Shizuku's remote Process doesn't reliably honor waitFor(timeout, unit).
        val deadline = System.currentTimeMillis() + timeoutMs
        var exitCode: Int? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                exitCode = proc.exitValue()
                break
            } catch (e: Throwable) {
                // IllegalThreadStateException or anything wrapping it ("process hasn't exited")
                Thread.sleep(40)
            }
        }
        if (exitCode == null) {
            try { proc.destroyForcibly() } catch (_: Throwable) {}
            tOut.join(500); tErr.join(500)
            return Result(stdout.toString(), "TIMEOUT after ${timeoutMs}ms\n$stderr", -5)
        }
        tOut.join(1000); tErr.join(1000)
        return Result(stdout.toString(), stderr.toString(), exitCode)
    }

    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (_: Throwable) {}
    }
}
