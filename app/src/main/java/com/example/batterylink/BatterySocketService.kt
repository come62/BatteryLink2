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
            backoff = minOf(backoff * 2, 30000L)
        }
    }
        private fun handle(ws: WebSocket, text: String) {
        val msg = try {
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject().put("action", text.trim())
        }
        val id = msg.optString("id", "")
        var action = msg.optString("action", "")
        if (action.isEmpty()) action = msg.optString("type", msg.optString("cmd", ""))

        val reply = JSONObject().put("id", id).put("action", action)
        when (action) {
            "get_battery" -> readBattery(reply)
            "open_app" -> openApp(msg.optString("name", ""), msg.optString("pkg", ""), reply)
            "ping" -> reply.put("ok", true)
            else -> reply.put("ok", false).put("error", "未知指令: $action")
        }
        ws.send(reply.toString())
    }

    private fun readBattery(reply: JSONObject) {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) {
            reply.put("ok", false).put("error", "读不到电量")
            return
        }
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        reply.put("ok", true)
            .put("level", if (scale > 0) level * 100 / scale else level)
            .put("charging", charging)
            .put("plugged", plugged)
            .put("ts", System.currentTimeMillis())
    }

    @Suppress("DEPRECATION")
    private fun openApp(name: String, pkg: String, reply: JSONObject) {
        val pm = packageManager
        var target = pkg.trim()
        var label = name.trim()

        if (target.isEmpty()) {
            if (label.isEmpty()) {
                reply.put("ok", false).put("error", "缺少 name 或 pkg")
                return
            }
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(main, 0)
            val hit = apps.firstOrNull { it.loadLabel(pm).toString() == label }
                ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(label, ignoreCase = true) }
            if (hit == null) {
                reply.put("ok", false).put("error", "没找到应用: $label")
                return
            }
            target = hit.activityInfo.packageName
            label = hit.loadLabel(pm).toString()
        }

        val launch = pm.getLaunchIntentForPackage(target)
        if (launch == null) {
            reply.put("ok", false).put("error", "无法启动: $target")
            return
        }

        try {
            val power = getSystemService(PowerManager::class.java)
            val wl = power.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "BatteryLink:open"
            )
            wl.acquire(8000)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(launch)
            reply.put("ok", true).put("package", target).put("label", label)
        } catch (e: Exception) {
            reply.put("ok", false).put("error", "启动失败: " + (e.message ?: ""))
        }
    }

    override fun onDestroy() {
        running = false
        socket?.cancel()
        worker?.interrupt()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("status", "已停止").apply()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "battery_link"
        private const val CHANNEL = "battery_link"
        private const val NOTIF_ID = 1
    }
}
    
