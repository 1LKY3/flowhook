package com.dustforge.flowhook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Service modes:
 *  - FULL:  WebSocket to flowhook.dustforge.com + WakeLock + ADB supervisor. Default-importance notification.
 *           Used when left toggle (services) is ON.
 *  - IDLE:  ADB supervisor only, no WS, no WakeLock. Minimum-importance notification (collapsed, silent).
 *           Used when left toggle is OFF but right toggle (ADB bridge) is ON.
 *  - STOP:  Service exits. Used when both toggles are off.
 *
 * Transition via onStartCommand intents carrying ACTION_* extras.
 */
class FlowhookService : Service() {

    enum class Mode { FULL, IDLE, STOP }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ws: WebSocket? = null
    private val okClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var reconnectDelay = 1000L
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var currentMode: Mode = Mode.FULL
    private var adbSupervisorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CommandHandler.appContext = applicationContext
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val desired = Mode.valueOf(intent?.getStringExtra(EXTRA_MODE) ?: chooseMode().name)
        applyMode(desired)
        return START_STICKY
    }

    private fun chooseMode(): Mode {
        val left  = Config.getServicesEnabled(this)
        val right = Config.getAdbBridgeEnabled(this)
        return when {
            left  -> Mode.FULL
            right -> Mode.IDLE  // keep ADB supervisor warm
            else  -> Mode.STOP
        }
    }

    private fun applyMode(mode: Mode) {
        if (mode == currentMode) {
            // Still make sure the foreground notification / threads are up-to-date
            when (mode) {
                Mode.FULL -> { ensureForegroundFull(); ensureWebSocket(); ensureAdbSupervisor(); acquireWakeLock() }
                Mode.IDLE -> { ensureForegroundIdle(); closeWebSocket(); ensureAdbSupervisor(); releaseWakeLock() }
                Mode.STOP -> { closeWebSocket(); cancelAdbSupervisor(); releaseWakeLock(); stopSelfCleanly() }
            }
            return
        }
        Log.i(TAG, "mode $currentMode → $mode")
        currentMode = mode
        when (mode) {
            Mode.FULL -> {
                ensureForegroundFull()
                acquireWakeLock()
                ensureWebSocket()
                ensureAdbSupervisor()
            }
            Mode.IDLE -> {
                ensureForegroundIdle()
                closeWebSocket()
                releaseWakeLock()
                ensureAdbSupervisor()
            }
            Mode.STOP -> {
                closeWebSocket()
                cancelAdbSupervisor()
                releaseWakeLock()
                stopSelfCleanly()
            }
        }
    }

    private fun stopSelfCleanly() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        ws?.close(1000, "service destroyed")
        releaseWakeLock()
        super.onDestroy()
    }

    // --- Notifications -------------------------------------------------

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_ACTIVE, "Flowhook (active)", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_IDLE, "Flowhook (idle)", NotificationManager.IMPORTANCE_MIN)
        )
    }

    private fun buildNotif(channelId: String, text: String): Notification {
        return Notification.Builder(this, channelId)
            .setContentTitle("Flowhook")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun ensureForegroundFull() {
        val notif = buildNotif(CH_ACTIVE, "Remote admin bridge active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun ensureForegroundIdle() {
        val notif = buildNotif(CH_IDLE, "ADB bridge ready (idle)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // --- WakeLock ------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flowhook:ws").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "wakelock acquired")
        } catch (e: Throwable) { Log.w(TAG, "wakelock failed: ${e.message}") }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    // --- ADB supervisor ------------------------------------------------

    private fun ensureAdbSupervisor() {
        if (adbSupervisorJob?.isActive == true) return
        adbSupervisorJob = scope.launch {
            while (isActive) {
                if (Config.getAdbBridgeEnabled(this@FlowhookService) && !AdbExecutor.isReady()) {
                    val r = AdbExecutor.connect(applicationContext)
                    if (r.exit == 0) {
                        Log.i(TAG, "adb bridge connected: ${r.stdout.trim()}")
                        AdbExecutor.exec("pm grant com.dustforge.flowhook android.permission.WRITE_SECURE_SETTINGS")
                        AdbExecutor.exec("pm grant com.dustforge.flowhook android.permission.READ_LOGS")
                    }
                }
                delay(10_000)
            }
        }
    }

    private fun cancelAdbSupervisor() {
        adbSupervisorJob?.cancel()
        adbSupervisorJob = null
        AdbExecutor.disconnect()
    }

    // --- WebSocket -----------------------------------------------------

    private fun ensureWebSocket() {
        if (ws != null) return
        connectWs()
    }

    private fun closeWebSocket() {
        ws?.close(1000, "mode change")
        ws = null
    }

    private fun connectWs() {
        val token = Config.getToken(this)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "no agent token configured; ws idle")
            return
        }
        val url = Config.getServerUrl(this)
        Log.i(TAG, "ws connecting to $url")
        val req = Request.Builder().url(url).build()
        ws = okClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "ws open")
                reconnectDelay = 1000L
                webSocket.send(JSONObject().put("token", token).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return@launch
                    if (msg.optString("type") == "hello") {
                        Log.i(TAG, "handshake ok device=${msg.optString("device_id")}")
                        return@launch
                    }
                    if (msg.optString("type") == "pong") return@launch
                    val reply = CommandHandler.handle(msg)
                    webSocket.send(reply.toString())
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                if (currentMode == Mode.FULL) scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closed: $code $reason")
                if (currentMode == Mode.FULL) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(10_000)
            if (currentMode == Mode.FULL) connectWs()
        }
    }

    companion object {
        const val TAG = "FlowhookSvc"
        const val NOTIF_ID = 1
        const val CH_ACTIVE = "flowhook_active"
        const val CH_IDLE = "flowhook_idle"
        const val EXTRA_MODE = "mode"

        fun intentFor(ctx: android.content.Context, mode: Mode) =
            Intent(ctx, FlowhookService::class.java).putExtra(EXTRA_MODE, mode.name)
    }
}
