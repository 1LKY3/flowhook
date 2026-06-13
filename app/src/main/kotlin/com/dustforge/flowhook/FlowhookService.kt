package com.dustforge.flowhook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
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
 *  - IDLE:  ADB connector only, no WS, no WakeLock. Minimum-importance notification (collapsed, silent).
 *           Used when left toggle is OFF but right toggle (ADB bridge) is ON.
 *  - STOP:  Service exits. Used when both toggles are off.
 *
 * Transition via onStartCommand intents carrying ACTION_* extras.
 */
class FlowhookService : Service() {

    enum class Mode { FULL, IDLE, STOP }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ws: WebSocket? = null
    private val wsLock = Any()
    private var wsConnecting = false
    private var wsGeneration = 0L
    private var reconnectJob: Job? = null
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
        // v0.3.6: the companion StateFlow persists across service restarts
        // within the same process. Reset it to false on every onCreate so
        // a fresh service can't inherit a stale "connected" value from a
        // prior life — the heartbeat tick will re-assert true once a real
        // hello+pong round-trip lands.
        _wsConnected.value = false
        lastPongAt = 0L
        // v0.3.9: do not write adb_enabled/adb_wifi_enabled from normal
        // service startup. Restarting adbd can re-enumerate USB and break
        // Android Auto while the phone is connected to a car.
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
            right -> Mode.IDLE  // keep ADB connector warm
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
            val builder = Notification.Builder(this, CH_ALERTS)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
            // If the text looks like a URL, make the notification tap open it
            val url = text.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else null }
            if (url != null) {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                val pi = PendingIntent.getActivity(this, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.setContentIntent(pi)
            }
            nm.notify(id, builder.build())
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
            var consecutiveFailures = 0
            while (isActive) {
                if (Config.getAdbBridgeEnabled(this@FlowhookService) && !AdbExecutor.isReady()) {
                    val r = AdbExecutor.connect(applicationContext)
                    if (r.exit == 0) {
                        Log.i(TAG, "adb bridge connected: ${r.stdout.trim()}")
                        SettingsGuard.ensureTcpip(this@FlowhookService)
                        consecutiveFailures = 0
                        AdbExecutor.exec("pm grant com.dustforge.flowhook android.permission.READ_LOGS")
                        updateNotification()
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures == 3) {
                            Log.w(TAG, "adb bridge unavailable for 3 ticks; forcing adbd restart via cycleAdb")
                            val cycled = SettingsGuard.cycleAdb(this@FlowhookService)
                            if (cycled) {
                                delay(3_000)
                                val retry = AdbExecutor.connect(applicationContext)
                                if (retry.exit == 0) {
                                    Log.i(TAG, "adb bridge recovered after cycleAdb: ${retry.stdout.trim()}")
                                    SettingsGuard.ensureTcpip(this@FlowhookService)
                                    consecutiveFailures = 0
                                    AdbExecutor.exec("pm grant com.dustforge.flowhook android.permission.READ_LOGS")
                                    updateNotification()
                                }
                            }
                        } else if (consecutiveFailures % 30 == 0) {
                            Log.w(TAG, "adb bridge still unavailable after $consecutiveFailures ticks; retrying cycleAdb")
                            SettingsGuard.cycleAdb(this@FlowhookService)
                            delay(3_000)
                            val retry = AdbExecutor.connect(applicationContext)
                            if (retry.exit == 0) {
                                Log.i(TAG, "adb bridge recovered on retry $consecutiveFailures: ${retry.stdout.trim()}")
                                SettingsGuard.ensureTcpip(this@FlowhookService)
                                consecutiveFailures = 0
                                AdbExecutor.exec("pm grant com.dustforge.flowhook android.permission.READ_LOGS")
                                updateNotification()
                            }
                        }
                    }
                } else if (AdbExecutor.isReady()) {
                    consecutiveFailures = 0
                }
                tick++
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
        connectWs()
    }

    private fun closeWebSocket() {
        val sock = synchronized(wsLock) {
            wsGeneration++
            wsConnecting = false
            reconnectJob?.cancel()
            reconnectJob = null
            val current = ws
            ws = null
            current
        }
        stopHeartbeat()
        sock?.close(1000, "mode change")
        _wsConnected.value = false
    }

    private fun connectWs() {
        val token = Config.getToken(this)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "no agent token configured; ws idle")
            return
        }
        val url = Config.getServerUrl(this)
        val generation = synchronized(wsLock) {
            if (ws != null || wsConnecting) return
            reconnectJob?.cancel()
            reconnectJob = null
            wsConnecting = true
            ++wsGeneration
        }
        Log.i(TAG, "ws connecting to $url")
        val req = Request.Builder().url(url).build()
        val newSocket = okClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrentOrConnectingSocket(webSocket, generation)) {
                    Log.i(TAG, "ignoring stale ws open generation=$generation")
                    webSocket.cancel()
                    return
                }
                Log.i(TAG, "ws open")
                reconnectDelay = 1000L
                webSocket.send(JSONObject().put("token", token).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    if (!isCurrentOrConnectingSocket(webSocket, generation)) return@launch
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return@launch
                    if (msg.optString("type") == "hello") {
                        val renewedToken = msg.optString("renewed_token", "")
                        if (renewedToken.isNotEmpty()) {
                            Config.setToken(applicationContext, renewedToken)
                            Log.i(TAG, "token auto-renewed by server")
                        }
                        Log.i(TAG, "handshake ok device=${msg.optString("device_id")}")
                        onAuthenticated(webSocket, generation)
                        return@launch
                    }
                    if (msg.optString("type") == "pong") {
                        if (isCurrentSocket(webSocket, generation)) {
                            lastPongAt = System.currentTimeMillis()
                        }
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
                handleSocketDisconnected(webSocket, generation)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closed: $code $reason")
                handleSocketDisconnected(webSocket, generation)
            }
        })
        synchronized(wsLock) {
            if (generation == wsGeneration && (ws == null || ws === newSocket)) {
                ws = newSocket
                wsConnecting = false
            } else {
                newSocket.cancel()
            }
        }
    }

    private fun isCurrentSocket(webSocket: WebSocket, generation: Long): Boolean =
        synchronized(wsLock) { generation == wsGeneration && ws === webSocket }

    private fun isCurrentOrConnectingSocket(webSocket: WebSocket, generation: Long): Boolean =
        synchronized(wsLock) {
            generation == wsGeneration && (ws === webSocket || (ws == null && wsConnecting))
        }

    private fun onAuthenticated(webSocket: WebSocket, generation: Long) {
        val authenticated = synchronized(wsLock) {
            if (generation == wsGeneration && (ws === webSocket || (ws == null && wsConnecting))) {
                ws = webSocket
                wsConnecting = false
                true
            } else {
                false
            }
        }
        if (!authenticated) {
            Log.i(TAG, "ignoring stale ws auth generation=$generation")
            return
        }
        Log.i(TAG, "DIAG onAuthenticated: flipping _wsConnected -> true (was ${_wsConnected.value})")
        _wsConnected.value = true
        lastPongAt = System.currentTimeMillis()
        startHeartbeat(webSocket, generation)
        updateNotification()
    }

    private fun handleSocketDisconnected(webSocket: WebSocket, generation: Long) {
        val shouldHandle = synchronized(wsLock) {
            if (generation != wsGeneration || (ws !== webSocket && !(ws == null && wsConnecting))) {
                false
            } else {
                ws = null
                wsConnecting = false
                wsGeneration++
                true
            }
        }
        if (!shouldHandle) {
            Log.i(TAG, "ignoring stale ws disconnect generation=$generation")
            return
        }
        Log.i(TAG, "DIAG onDisconnected: flipping _wsConnected -> false (was ${_wsConnected.value})")
        _wsConnected.value = false
        stopHeartbeat()
        updateNotification()
        if (currentMode == Mode.FULL) scheduleReconnect()
    }

    private fun startHeartbeat(webSocket: WebSocket, generation: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isCurrentSocket(webSocket, generation)) break
                val age = System.currentTimeMillis() - lastPongAt
                // v0.3.6: the app is its own source of truth for connection
                // health. Do not wait for onFailure / onClosed to fire —
                // under Doze + silent NAT rebinds OkHttp callbacks can go
                // missing for hours, leaving the StateFlow stuck at true
                // while the server has no record of us. Instead, re-derive
                // _wsConnected on every tick from local data (pong age +
                // socket presence). UI status reflects reality at each
                // tick regardless of what OkHttp does or doesn't deliver.
                val healthy = age < HEARTBEAT_DEADLINE_MS
                if (_wsConnected.value != healthy) {
                    Log.i(TAG, "tick: _wsConnected ${_wsConnected.value} -> $healthy (age=${age}ms, sock=true)")
                    _wsConnected.value = healthy
                    updateNotification()
                }
                if (!healthy) {
                    Log.w(TAG, "heartbeat declared unhealthy — cancelling socket + scheduling reconnect")
                    webSocket.cancel()
                    handleSocketDisconnected(webSocket, generation)
                    break
                }
                val ok = webSocket.send(JSONObject().put("type", "ping").toString())
                if (!ok) {
                    Log.w(TAG, "heartbeat send returned false — socket queue saturated, forcing reconnect")
                    webSocket.cancel()
                    handleSocketDisconnected(webSocket, generation)
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
        synchronized(wsLock) {
            if (reconnectJob?.isActive == true || ws != null || wsConnecting) return
            reconnectJob = scope.launch {
                val delayMs = synchronized(wsLock) {
                    val current = reconnectDelay
                    reconnectDelay = (reconnectDelay * 2).coerceAtMost(10_000)
                    current
                }
                delay(delayMs)
                synchronized(wsLock) {
                    reconnectJob = null
                }
                if (currentMode == Mode.FULL) ensureWebSocket()
            }
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
