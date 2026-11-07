package com.example.batterylink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BatterySocketService : Service() {

    @Volatile private var running = false
    @Volatile private var socket: WebSocket? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "BatteryLink", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotice("正在连接…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (!running) {
            running = true
            worker = Thread({ connectLoop() }, "BatteryLink-connect").also { it.start() }
        }
        return START_STICKY
    }

    private fun buildNotice(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("BatteryLink")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    private fun setStatus(text: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("status", text).apply()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotice(text))
    }

    private fun connectLoop() {
        var backoff = 2000L
        while (running) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val base = (prefs.getString("url", "") ?: "").trim().trimEnd('/')
            val key = (prefs.getString("deviceKey", "") ?: "").trim()
            if (base.isEmpty() || key.isEmpty()) {
                setStatus("未填写地址或密钥")
                running = false
                stopSelf()
                break
            }
            val wsBase = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> "wss://$base"
            }
            val url = wsBase + "/ws?role=device&key=" + URLEncoder.encode(key, "UTF-8")

            val client = OkHttpClient.Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            val done = CountDownLatch(1)
            var opened = false

            val ws = client.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened = true
                        setStatus("已连接，等待指令")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            handle(webSocket, text)
                        } catch (e: Exception) {
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        setStatus("已断开，重连中…")
                        done.countDown()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        done.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        setStatus("连接失败：" + (t.message ?: "未知错误").take(60))
                        done.countDown()
                    }
                }
            )
            socket = ws

            try {
                done.await()
            } catch (e: InterruptedException) {
            }
            ws.cancel()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()

            if (!running) break
            if (opened) backoff = 2000L
            try {
                Thread.sleep(backoff)
            } catch (e: InterruptedException) {
            }
       
