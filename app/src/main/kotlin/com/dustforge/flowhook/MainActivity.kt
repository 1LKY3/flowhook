package com.dustforge.flowhook

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var server: EditText
    private lateinit var token: EditText
    private lateinit var footer: TextView

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        server = findViewById(R.id.server)
        token  = findViewById(R.id.token)
        footer = findViewById(R.id.footer)

        server.setText(Config.getServerUrl(this))
        token.setText(Config.getToken(this) ?: "")

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            Config.setServerUrl(this, server.text.toString().trim())
            Config.setToken(this, token.text.toString().trim())
            stopService(Intent(this, FlowhookService::class.java))
            startForegroundService(Intent(this, FlowhookService::class.java))
            toast("Service restarted")
            refreshStatus()
        }

        findViewById<Button>(R.id.btn_shizuku).setOnClickListener {
            if (ShizukuExecutor.isReady()) {
                try { Shizuku.requestPermission(42) } catch (_: Throwable) {}
            } else {
                toast("Shizuku not running — start it first.")
            }
        }

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            val r = ShizukuExecutor.exec("id; getprop ro.product.model")
            status.text = "exit=${r.exit}\n${r.stdout}${r.stderr}"
        }

        findViewById<Button>(R.id.btn_battery_opt).setOnClickListener {
            openBatteryOptimization()
        }
        findViewById<Button>(R.id.btn_samsung).setOnClickListener {
            openSamsungNeverSleeping()
        }
        findViewById<Button>(R.id.btn_app_info).setOnClickListener {
            openAppInfo()
        }

        Shizuku.addRequestPermissionResultListener(shizukuListener)

        // Start service on first open if token present
        if (!Config.getToken(this).isNullOrBlank()) {
            startForegroundService(Intent(this, FlowhookService::class.java))
        }

        refreshStatus()
        footer.text = "v${packageManager.getPackageInfo(packageName, 0).versionName}"
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun refreshStatus() {
        val shizuku = ShizukuExecutor.isReady()
        val perm = ShizukuExecutor.hasPermission()
        val tokenSet = !Config.getToken(this).isNullOrBlank()
        val batteryIgnored = isIgnoringBatteryOpt()
        val sb = StringBuilder()
        sb.appendLine(line("Shizuku running", shizuku))
        sb.appendLine(line("Shizuku permission", perm))
        sb.appendLine(line("Token configured", tokenSet))
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
        // Try Samsung's Device Care battery page. Class names vary by OneUI version.
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
            } catch (_: Throwable) { /* try next */ }
        }
        // Fallback: generic battery settings
        try {
            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            toast("Navigate to 'Apps' or 'Background usage limits' → Never sleeping apps")
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
