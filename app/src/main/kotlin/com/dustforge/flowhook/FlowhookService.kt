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

class FlowhookService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var reconnectDelay = 1000L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CommandHandler.appContext = applicationContext
        acquireWakeLock()
        startForeground()
        connect()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flowhook:ws").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "wakelock acquired")
        } catch (e: Throwable) {
            Log.w(TAG, "wakelock failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ws?.close(1000, "service destroyed")
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun startForeground() {
        val channelId = "flowhook_bridge"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Flowhook bridge", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Flowhook")
            .setContentText("Remote admin bridge active")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    private fun connect() {
        val token = Config.getToken(this)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "no agent token configured; service idle")
            return
        }
        val url = Config.getServerUrl(this)
        Log.i(TAG, "connecting to $url")
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
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
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { /* unused */ }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closed: $code $reason")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(10_000)
            connect()
        }
    }

    companion object {
        const val TAG = "FlowhookSvc"
    }
}
