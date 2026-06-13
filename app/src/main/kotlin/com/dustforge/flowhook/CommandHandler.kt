package com.dustforge.flowhook

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
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
                "recover_adb" -> handleRecoverAdb(res)
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
                "rubin" -> handleRubin(msg, res)
                else -> res.put("ok", false).put("error", "unknown command type: $type")
            }
        } catch (e: Throwable) {
            res.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun handleRecoverAdb(res: JSONObject): JSONObject {
        val ctx = appContext ?: return res.put("ok", false).put("error", "no app context")
        // Manual recovery is the authenticated escape hatch for a dead bridge,
        // including the red-switch-off state. The red switch still cuts normal
        // command execution by stopping the local ADB connector, but it should
        // not make a remote owner permanently dependent on USB.
        if (!Config.getAdbBridgeEnabled(ctx)) Config.setAdbBridgeEnabled(ctx, true)
        val r = SettingsGuard.recoverAdbBridge(ctx)
        return res.put("ok", r.ok)
            .put("before_ready", r.beforeReady)
            .put("after_ready", r.afterReady)
            .put("settings_ok", r.settingsOk)
            .put("cycle_ok", r.cycleOk)
            .put("tcpip_ok", r.tcpipOk)
            .put("stdout", r.stdout)
            .put("stderr", r.stderr)
            .put("exit", if (r.ok) 0 else 1)
            .put("source", "manual_recovery")
    }

    private val RUBIN_PROVIDERS = mapOf(
        "visitedplace" to "content://com.samsung.android.rubin.persona.visitedplace",
        "placepattern" to "content://com.samsung.android.rubin.persona.placepattern",
        "userprofile" to "content://com.samsung.android.rubin.userprofile",
        "presence" to "content://com.samsung.android.rubin.context.presence",
        "driving" to "content://com.samsung.android.rubin.context.drivingevent",
        "trip" to "content://com.samsung.android.rubin.context.tripevent",
        "silentplace" to "content://com.samsung.android.rubin.context.silentplaceevent",
        "music" to "content://com.samsung.android.rubin.persona.preferredmusic",
        "games" to "content://com.samsung.android.rubin.context.playinggamesevent",
        "pets" to "content://com.samsung.android.rubin.context.caringpetsevent",
        "signalmeta" to "content://com.samsung.android.rubin.signalmeta",
    )

    private fun handleRubin(msg: JSONObject, res: JSONObject): JSONObject {
        val ctx = appContext ?: return res.put("ok", false).put("error", "no app context")
        val provider = msg.optString("provider", "")

        if (provider == "list" || provider.isBlank()) {
            return res.put("ok", true).put("providers", JSONArray(RUBIN_PROVIDERS.keys.sorted()))
        }

        if (provider == "probe") {
            return probeRubin(ctx, res)
        }

        val uri = if (provider.startsWith("content://")) {
            provider
        } else {
            RUBIN_PROVIDERS[provider]
                ?: return res.put("ok", false).put("error", "unknown provider: $provider. available: ${RUBIN_PROVIDERS.keys.sorted()}")
        }

        val limit = msg.optInt("limit", 100)
        var cursor: Cursor? = null
        return try {
            cursor = ctx.contentResolver.query(Uri.parse(uri), null, null, null, null)
            if (cursor == null) return res.put("ok", false).put("error", "provider returned null cursor")

            val columns = JSONArray(cursor.columnNames.toList())
            val rows = JSONArray()
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val row = JSONObject()
                for (i in 0 until cursor.columnCount) {
                    val name = cursor.getColumnName(i)
                    row.put(name, when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> "[blob ${cursor.getBlob(i).size} bytes]"
                        else -> cursor.getString(i)
                    })
                }
                rows.put(row)
                count++
            }
            res.put("ok", true)
                .put("provider", provider)
                .put("columns", columns)
                .put("total", cursor.count)
                .put("returned", count)
                .put("rows", rows)
        } catch (e: SecurityException) {
            res.put("ok", false).put("error", "permission denied: ${e.message}")
        } finally {
            cursor?.close()
        }
    }

    private fun probeRubin(ctx: Context, res: JSONObject): JSONObject {
        val results = JSONObject()

        for ((name, baseUri) in RUBIN_PROVIDERS) {
            val info = JSONObject()

            // getType on base URI
            try {
                val t = ctx.contentResolver.getType(Uri.parse(baseUri))
                info.put("type", t ?: "null")
            } catch (e: Exception) {
                info.put("type_err", e.message?.take(80))
            }

            // query with many path suffixes
            val paths = listOf("", "/data", "/items", "/events", "/list", "/all",
                "/context", "/current", "/latest", "/history", "/0", "/1")
            val queryHits = JSONObject()
            for (path in paths) {
                try {
                    val cursor = ctx.contentResolver.query(Uri.parse(baseUri + path), null, null, null, null)
                    val label = path.ifEmpty { "(base)" }
                    if (cursor != null) {
                        queryHits.put(label, "${cursor.count} rows, cols=${cursor.columnNames.toList()}")
                        cursor.close()
                    } else {
                        queryHits.put(label, "null")
                    }
                } catch (e: Exception) {
                    queryHits.put(path.ifEmpty { "(base)" }, "ERR: ${e.message?.take(60)}")
                }
            }
            info.put("query", queryHits)

            // call with various methods
            val methods = listOf("get", "getAll", "getData", "query", "read",
                "getContext", "getEvents", "getProfile", "getPresence",
                "getCurrent", "getLatest", "getList", "getPlaces")
            val callHits = JSONObject()
            for (method in methods) {
                try {
                    val bundle = ctx.contentResolver.call(Uri.parse(baseUri), method, null, null)
                    if (bundle != null && bundle.keySet().isNotEmpty()) {
                        val keys = bundle.keySet().toList()
                        val sample = JSONObject()
                        for (k in keys.take(5)) {
                            sample.put(k, bundle.get(k)?.toString()?.take(200) ?: "null")
                        }
                        callHits.put(method, JSONObject().put("keys", JSONArray(keys)).put("sample", sample))
                    }
                } catch (e: Exception) {
                    callHits.put(method, "ERR: ${e.message?.take(60)}")
                }
            }
            if (callHits.length() > 0) info.put("call", callHits)

            results.put(name, info)
        }
        return res.put("ok", true).put("results", results)
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
