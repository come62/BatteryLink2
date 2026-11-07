package com.example.batterylink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var statusView: TextView

    private val prefs by lazy {
        getSharedPreferences(BatterySocketService.PREFS, MODE_PRIVATE)
    }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k == "status") runOnUiThread { showStatus() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun showStatus() {
        statusView.text = "状态：" + (prefs.getString("status", "未连接") ?: "未连接")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }

        box.addView(TextView(this).apply {
            text = "BatteryLink 电量与应用控制"
            textSize = 22f
        })

        urlInput = EditText(this).apply {
            hint = "Worker 地址，例如 https://xxx.com"
            setText(prefs.getString("url", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        keyInput = EditText(this).apply {
            hint = "设备密钥 DEVICE_KEY"
            setText(prefs.getString("deviceKey", ""))
            setSingleLine()
        }
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(12), 0, dp(12))
        }

        box.addView(urlInput)
        box.addView(keyInput)
        box.addView(statusView)

        box.addView(makeButton("保存并启动连接") {
            prefs.edit()
                .putString("url", urlInput.text.toString().trim())
                .putString("deviceKey", keyInput.text.toString().trim())
                .apply()
            val svc = Intent(this, BatterySocketService::class.java)
            stopService(svc)
            startForegroundService(svc)
        })

        box.addView(makeButton("停止连接") {
            stopService(Intent(this, BatterySocketService::class.java))
        })

        box.addView(makeButton("授权：显示在其他应用上层（后台打开App要用）") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })

        box.addView(makeButton("授权：忽略电池优化") {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })

        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        showStatus()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }
}
