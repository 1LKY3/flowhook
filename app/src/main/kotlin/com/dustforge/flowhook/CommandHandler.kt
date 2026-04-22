package com.dustforge.flowhook

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object CommandHandler {

    var appContext: Context? = null

    fun handle(msg: JSONObject): JSONObject {
        val reqId = msg.optString("req_id", "")
        val type = msg.optString("type", "")
        val res = JSONObject().put("req_id", reqId)

        return try {
            when (type) {
                "exec" -> {
                    val cmd = msg.optString("cmd")
                    val r = Executor.exec(cmd)
                    res.put("ok", r.exit == 0)
                        .put("stdout", r.stdout)
                        .put("stderr", r.stderr)
                        .put("exit", r.exit)
                        .put("source", r.source)
                }
                "install" -> handleInstall(msg.optString("apk_url"), res)
                "uninstall" -> {
                    val pkg = msg.optString("package")
                    val r = Executor.exec("pm uninstall $pkg")
                    res.put("ok", r.exit == 0).put("stdout", r.stdout).put("stderr", r.stderr).put("exit", r.exit)
                }
                "screencap" -> {
                    val tmp = "/sdcard/flowhook_cap.png"
                    val r = Executor.exec("screencap -p $tmp && base64 $tmp && rm $tmp", 20_000)
                    res.put("ok", r.exit == 0).put("png_b64", r.stdout.replace("\n", "")).put("stderr", r.stderr)
                }
                else -> res.put("ok", false).put("error", "unknown command type: $type")
            }
        } catch (e: Throwable) {
            res.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun handleInstall(apkUrl: String, res: JSONObject): JSONObject {
        if (apkUrl.isBlank()) return res.put("ok", false).put("error", "apk_url required")
        val ctx = appContext ?: return res.put("ok", false).put("error", "no app context")
        // externalCacheDir is under /sdcard/Android/data/<pkg>/cache — app-writable, shell-readable.
        val cacheDir = ctx.externalCacheDir ?: ctx.cacheDir
        val tmp = File(cacheDir, "flowhook_install.apk")
        return try {
            URL(apkUrl).openStream().use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            }
            // Copy to /data/local/tmp via the shell user so pm can read it under all SELinux profiles
            val stagePath = "/data/local/tmp/flowhook_install.apk"
            val copyRes = Executor.exec("cp ${tmp.absolutePath} $stagePath && chmod 644 $stagePath", 30_000)
            if (copyRes.exit != 0) {
                return res.put("ok", false).put("error", "stage failed: ${copyRes.stderr}")
            }
            val r = Executor.exec("pm install -r -t $stagePath; rm -f $stagePath", 120_000)
            res.put("ok", r.exit == 0 && r.stdout.contains("Success", true))
                .put("stdout", r.stdout).put("stderr", r.stderr).put("exit", r.exit)
        } catch (e: Throwable) {
            res.put("ok", false).put("error", "install failed: ${e.message}")
        } finally {
            tmp.delete()
        }
    }
}
