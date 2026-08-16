package com.example.deskpet

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.deskpet.service.OverlayService
import com.example.deskpet.util.SupabaseClient

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 配置 Supabase 后端（小奈的大脑连接通道）
        SupabaseClient.configure(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
        setContentView(createMainView())
    }

    private fun createMainView(): android.widget.LinearLayout {
        val textView = TextView(this).apply {
            text = "小奈桌宠 🐱\n\n点击下方按钮启动悬浮窗桌宠。\n请确保已授予所有权限。"
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }

        val button = Button(this).apply {
            text = "启动小奈"
            setOnClickListener {
                checkAndStartService()
            }
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(textView)
            addView(button)
        }

        return container
    }

    private fun checkAndStartService() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
                return
            }
        }
        startOverlayService()
        finish()
    }

    private fun requestOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("Overlay Permission Required")
            .setMessage("The pet needs permission to display over other apps.")
            .setPositiveButton("Grant") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
