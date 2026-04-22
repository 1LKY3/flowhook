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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    // v0.3.2: heartbeat + authoritative WS state.
    // OkHttp's 15s protocol-level ping sometimes fails silently under
    // Android Doze + carrier NAT rebinds — the socket looks "open" but
    // writes land in a void. App-level ping/pong gives us a second
    // watchdog with a hard deadline.
    private var heartbeatJob: Job? = null
    @Volatile private var lastPongAt: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CommandHandler.appContext = applicationContext
        ensureChannels()
        // v0.3.4: re-assert adb_enabled / adb_wifi_enabled on every service
        // start. No-op if Samsung hasn't touched them; self-heal if it has.
        SettingsGuard.reassert(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val desired = Mode.valueOf(intent?.getStringExtra(EXTRA_MODE) ?: chooseMode().name)
        applyMode(desired)
        ensureAdbStateWatcher()
        return START_STICKY
    }

    // v0.3.4: watch AdbExecutor.ready so the notification re-renders when the
    // ADB bridge flips independently of the WebSocket. Same one-shot pattern
    // as the WS flow in MainActivity.
    private var adbWatcherJob: Job? = null
    private fun ensureAdbStateWatcher() {
        if (adbWatcherJob?.isActive == true) return
        adbWatcherJob = scope.launch {
            AdbExecutor.ready.collect { updateNotification() }
        }
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
        // v0.3.5: user-visible alerts (ADB recovery failures, etc.). DEFAULT
        // importance so the phone chirps and the shade shows the message —
        // this channel is reserved for "something needs your attention,"
        // not the always-on service chatter.
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Flowhook alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    // v0.3.5: show a user-visible notification triggered by a server push.
    // IDs are derived from a rolling counter so consecutive alerts don't
    // collapse on top of each other — each new problem gets its own entry.
    private fun showUserNotification(title: String, text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val id = ALERT_ID_BASE + (alertCounter++ and 0xFF)
            val n = Notification.Builder(this, CH_ALERTS)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        } catch (t: Throwable) {
            Log.w(TAG, "alert notification failed: ${t.message}")
        }
    }

    private var alertCounter = 0

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
        val notif = buildNotif(CH_ACTIVE, fullModeStatusText())
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

    // v0.3.2: notification text reflects real WS state so glancing the
    // shade tells the user whether the remote bridge actually works,
    // not just whether the feature is toggled on.
    // v0.3.4: also surface ADB bridge state — WS up + ADB down means
    // commands will all return "ADB bridge not connected", which is a
    // different failure mode than "server unreachable". Users need to
    // see both to diagnose from the shade.
    private fun fullModeStatusText(): String {
        val ws = _wsConnected.value
        val adb = AdbExecutor.isReady()
        return when {
            ws && adb -> "Remote admin bridge: connected (WS + ADB)"
            ws && !adb -> "Remote admin bridge: WS up, ADB offline"
            !ws && adb -> "Remote admin bridge: reconnecting (ADB up)"
            else -> "Remote admin bridge: reconnecting…"
        }
    }

    private fun updateNotification() {
        if (currentMode != Mode.FULL) return
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotif(CH_ACTIVE, fullModeStatusText()))
        } catch (_: Throwable) { /* no-op; notifications are best-effort */ }
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
            var tick = 0
            while (isActive) {
                if (Config.getAdbBridgeEnabled(this@FlowhookService) && !AdbExecutor.isReady()) {
                    val r = AdbExecutor.connect(applicationContext)
                    if (r.exit == 0) {
                        Log.i(TAG, "adb bridge connected: ${r.stdout.trim()}")
                        AdbExecutor.exec("pm grant com.dustforge.flowhook android.permission.WRITE_SECURE_SETTINGS")
                        AdbExecutor.exec("pm grant com.dustforge.flowhook android.permission.READ_LOGS")
                        // v0.3.4: once we have shell, plant the persistence
                        // props so adbd keeps listening on 5555 after reboots.
                        // Idempotent — if already set, setprop is a no-op.
                        AdbExecutor.exec("setprop persist.adb.tcp.port 5555")
                        AdbExecutor.exec("settings put global adb_enabled 1")
                        updateNotification()
                    }
                }
                // v0.3.4: every ~5 min (10s * 30 ticks) re-run the app-process
                // Settings guard. This catches Samsung silently flipping
                // adb_enabled off between service starts.
                tick++
                if (tick % 30 == 0) {
                    SettingsGuard.reassert(applicationContext)
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
        stopHeartbeat()
        ws?.close(1000, "mode change")
        ws = null
        _wsConnected.value = false
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
                        // v0.3.2: connection is authenticated — flip the
                        // authoritative state and start the watchdog.
                        onAuthenticated()
                        return@launch
                    }
                    if (msg.optString("type") == "pong") {
                        lastPongAt = System.currentTimeMillis()
                        return@launch
                    }
                    // v0.3.5: server-pushed notification. Fire-and-forget —
                    // no reply expected. Used by K1's adb-recovery script to
                    // surface failures (denied auth dialog, etc.) on the
                    // phone so the user knows what to do.
                    if (msg.optString("type") == "notify") {
                        showUserNotification(
                            msg.optString("title", "Flowhook"),
                            msg.optString("text", "")
                        )
                        return@launch
                    }
                    val reply = CommandHandler.handle(msg)
                    webSocket.send(reply.toString())
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                onDisconnected()
                if (currentMode == Mode.FULL) scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closed: $code $reason")
                onDisconnected()
                if (currentMode == Mode.FULL) scheduleReconnect()
            }
        })
    }

    private fun onAuthenticated() {
        Log.i(TAG, "DIAG onAuthenticated: flipping _wsConnected -> true (was ${_wsConnected.value})")
        _wsConnected.value = true
        lastPongAt = System.currentTimeMillis()
        startHeartbeat()
        updateNotification()
    }

    private fun onDisconnected() {
        Log.i(TAG, "DIAG onDisconnected: flipping _wsConnected -> false (was ${_wsConnected.value})")
        _wsConnected.value = false
        stopHeartbeat()
        updateNotification()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val sock = ws ?: break
                val age = System.currentTimeMillis() - lastPongAt
                if (age > HEARTBEAT_DEADLINE_MS) {
                    Log.w(TAG, "heartbeat deadline exceeded (${age}ms since last pong) — forcing reconnect")
                    // Cancelling the socket (vs close()) skips the graceful
                    // close handshake that can itself hang on a dead TCP
                    // connection — onFailure fires immediately.
                    sock.cancel()
                    break
                }
                val ok = sock.send(JSONObject().put("type", "ping").toString())
                if (!ok) {
                    Log.w(TAG, "heartbeat send returned false — socket queue saturated, forcing reconnect")
                    sock.cancel()
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
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
        const val CH_ALERTS = "flowhook_alerts"
        const val ALERT_ID_BASE = 100
        const val EXTRA_MODE = "mode"

        // v0.3.2: app-level heartbeat. 20s ping cadence with 30s deadline
        // means a dead socket is detected and replaced within ~50s worst
        // case. Shorter than Android Doze's first tier (~60s stationary)
        // so a dozing phone still closes+reconnects before Doze reshapes
        // its power budget against us.
        const val HEARTBEAT_INTERVAL_MS = 20_000L
        const val HEARTBEAT_DEADLINE_MS = 30_000L

        // Authoritative WS auth state — true only between the "hello"
        // handshake response and the next close/failure. MainActivity
        // binds to this so the green ✓ actually means connected.
        private val _wsConnected = MutableStateFlow(false)
        val wsConnected: StateFlow<Boolean> = _wsConnected.asStateFlow()

        fun intentFor(ctx: android.content.Context, mode: Mode) =
            Intent(ctx, FlowhookService::class.java).putExtra(EXTRA_MODE, mode.name)
    }
}
