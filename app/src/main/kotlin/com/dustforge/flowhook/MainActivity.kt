package com.dustforge.flowhook

import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var server: EditText
    private lateinit var token: EditText
    private lateinit var footer: TextView
    private lateinit var toggleServices: SwitchCompat
    private lateinit var toggleAdb: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        server = findViewById(R.id.server)
        token  = findViewById(R.id.token)
        footer = findViewById(R.id.footer)
        toggleServices = findViewById(R.id.toggle_services)
        toggleAdb = findViewById(R.id.toggle_adb)

        server.setText(Config.getServerUrl(this))
        token.setText(Config.getToken(this) ?: "")

        // Left toggle: Flowhook services on/off
        toggleServices.isChecked = Config.getServicesEnabled(this)
        toggleServices.setOnCheckedChangeListener { _, isChecked ->
            Config.setServicesEnabled(this, isChecked)
            syncService()
            refreshStatus()
        }

        // Right toggle: ADB bridge on/off — tapping OFF requires confirmation
        toggleAdb.isChecked = Config.getAdbBridgeEnabled(this)
        toggleAdb.setOnClickListener {
            val newState = toggleAdb.isChecked  // Switch already flipped visually
            if (newState) {
                // Turning on (from UI) — but note this doesn't actually enable tcpip.
                // It just tells Flowhook to try using it. Show a note.
                Config.setAdbBridgeEnabled(this, true)
                toast("ADB Bridge will re-try connection. If tcpip is off, connect via USB and run `adb tcpip 5555`.")
                syncService()
                refreshStatus()
            } else {
                // Turning off — show confirmation. Revert toggle until confirmed.
                toggleAdb.isChecked = true
                showAdbOffConfirmDialog()
            }
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            Config.setServerUrl(this, server.text.toString().trim())
            Config.setToken(this, token.text.toString().trim())
            syncService()
            toast("Saved")
            refreshStatus()
        }

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val r = Executor.exec("id; getprop ro.product.model")
                withContext(Dispatchers.Main) {
                    status.text = "[${r.source}] exit=${r.exit}\n${r.stdout}${r.stderr}"
                }
            }
        }

        findViewById<Button>(R.id.btn_battery_opt).setOnClickListener { openBatteryOptimization() }
        findViewById<Button>(R.id.btn_samsung).setOnClickListener { openSamsungNeverSleeping() }
        findViewById<Button>(R.id.btn_app_info).setOnClickListener { openAppInfo() }

        // Kick the service to the right state for current toggles
        syncService()

        refreshStatus()
        footer.text = "v${packageManager.getPackageInfo(packageName, 0).versionName}"
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun syncService() {
        val left = Config.getServicesEnabled(this)
        val right = Config.getAdbBridgeEnabled(this)
        val mode = when {
            left -> FlowhookService.Mode.FULL
            right -> FlowhookService.Mode.IDLE
            else -> FlowhookService.Mode.STOP
        }
        if (mode == FlowhookService.Mode.STOP) {
            stopService(Intent(this, FlowhookService::class.java))
        } else {
            startForegroundService(FlowhookService.intentFor(this, mode))
        }
    }

    private fun showAdbOffConfirmDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_adb_confirm)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setCancelable(true)

        dialog.findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
            // toggle stays on (already reverted before showing dialog)
        }
        dialog.findViewById<Button>(R.id.btn_confirm).setOnClickListener {
            dialog.dismiss()
            toggleAdb.isChecked = false
            Config.setAdbBridgeEnabled(this, false)
            toast("Disabling ADB Bridge…")
            CoroutineScope(Dispatchers.IO).launch {
                // Issue the kill command via the current ADB bridge. This will itself
                // disconnect us, which is expected.
                val killCmd = "setprop service.adb.tcp.port -1; stop adbd; start adbd"
                val r = AdbExecutor.exec(killCmd, timeoutMs = 5_000)
                AdbExecutor.disconnect()
                withContext(Dispatchers.Main) {
                    toast("ADB Bridge off. Re-enable via USB + `adb tcpip 5555`.")
                    syncService()
                    refreshStatus()
                }
            }
        }
        dialog.show()
    }

    private fun refreshStatus() {
        val adb = AdbExecutor.isReady()
        val leftOn = Config.getServicesEnabled(this)
        val rightOn = Config.getAdbBridgeEnabled(this)
        val tokenSet = !Config.getToken(this).isNullOrBlank()
        val batteryIgnored = isIgnoringBatteryOpt()
        val hasWss = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val mode = when {
            leftOn -> "FULL"
            rightOn -> "IDLE (ADB only)"
            else -> "OFF"
        }
        val sb = StringBuilder()
        sb.appendLine("Mode: $mode")
        sb.appendLine(line("Flowhook services (left toggle)", leftOn))
        sb.appendLine(line("ADB Bridge (right toggle)", rightOn))
        sb.appendLine(line("ADB connected", adb))
        sb.appendLine(line("Token configured", tokenSet))
        sb.appendLine(line("WRITE_SECURE_SETTINGS", hasWss))
        sb.appendLine(line("Battery optimization exempt", batteryIgnored))
        sb.append("Server: ${Config.getServerUrl(this)}")
        status.text = sb.toString()
    }

    private fun line(k: String, ok: Boolean) = "${if (ok) "✓" else "✗"}  $k"

    private fun isIgnoringBatteryOpt(): Boolean = try {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    } catch (_: Throwable) { false }

    private fun openBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) { toast("Couldn't open battery settings") }
        }
    }

    private fun openSamsungNeverSleeping() {
        val attempts = listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.sm" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.sm_cn" to "com.samsung.android.sm.ui.battery.BatteryActivity"
        )
        for ((pkg, cls) in attempts) {
            try {
                val i = Intent().apply {
                    component = ComponentName(pkg, cls)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(i)
                toast("Find 'Never sleeping apps' → add Flowhook")
                return
            } catch (_: Throwable) { }
        }
        try {
            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            toast("Navigate to 'Background usage limits' → Never sleeping apps")
        } catch (_: Throwable) {
            toast("Couldn't open Samsung battery settings")
        }
    }

    private fun openAppInfo() {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(i)
            toast("Battery → Unrestricted")
        } catch (_: Throwable) { toast("Couldn't open app info") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
