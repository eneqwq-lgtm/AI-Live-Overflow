package com.example.deskpet.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.*
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import com.example.deskpet.util.SupabaseClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.*

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var whisperRunnable: Runnable? = null
    private var appTracker: UsageTracker? = null
    private var screenshotObserver: ScreenshotObserver? = null

    private var batteryReceiver: BroadcastReceiver? = null
    private val switchTimes = ArrayDeque<Long>()
    private var rapidSwitchCooldown = 0L
    @Volatile private var pushRunning = false
    private var pushThread: Thread? = null
    private var lastSeenMessageId: String? = null

    // Fling 检测:最近一次 MOVE 的时间与位置
    private var lastMoveTime = 0L
    private var lastMoveX = 0f
    private var lastMoveY = 0f

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 180
        private const val PET_HEIGHT_DP = 240
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val LONG_PRESS_TIMEOUT = 600L
        private const val MOVE_THRESHOLD = 10
        private const val WHISPER_INTERVAL = 3600_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getWhisper()))
        setupOverlay()
        startWhisperRotation()
        startAppTracking()
        startScreenshotDetection()
        registerBatteryMonitor()
        startPushPolling()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            WebView.setWebContentsDebuggingEnabled(true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i("DeskPet", "page finished: $url")
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    Log.e("DeskPet", "load error: code=${error?.errorCode} desc=${error?.description} url=${request?.url}")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    Log.i("DeskPet", "JS[${msg?.messageLevel()}]: ${msg?.message()} @ ${msg?.sourceId()}:${msg?.lineNumber()}")
                    return true
                }
            }
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > MOVE_THRESHOLD || Math.abs(dy) > MOVE_THRESHOLD) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    lastMoveX = event.rawX
                    lastMoveY = event.rawY
                    lastMoveTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (hasMoved) {
                        // Fling 检测:快速拖拽后松手 → 甩出屏幕动画
                        val dt = System.currentTimeMillis() - lastMoveTime
                        val dxTotal = event.rawX - initialTouchX
                        if (dt > 0 && Math.abs(dxTotal) > 80) {
                            val vx = Math.abs(dxTotal) / Math.max(1, (System.currentTimeMillis() - touchStartTime))
                            if (vx > 1.2f) {
                                val dir = if (dxTotal > 0) "right" else "left"
                                overlayView?.evaluateJavascript(
                                    "window.petEngine && window.petEngine.onFling('$dir')", null
                                )
                            }
                        }
                    } else {
                        when {
                            elapsed > LONG_PRESS_TIMEOUT -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < DOUBLE_TAP_TIMEOUT -> onDoubleTap()
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onTap()", null)
        reportGesture("tap")
    }

    private fun onDoubleTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDoubleTap()", null)
        reportGesture("double_tap")
    }

    private fun onLongPress() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onLongPress()", null)
        reportGesture("long_press")
    }

    private fun reportGesture(type: String) {
        // TODO: POST to Supabase if configured
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("\uD83D\uDC3E")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startWhisperRotation() {
        whisperRunnable = object : Runnable {
            override fun run() {
                updateWhisper()
                handler.postDelayed(this, WHISPER_INTERVAL)
            }
        }
        handler.postDelayed(whisperRunnable!!, WHISPER_INTERVAL)
    }

    private fun updateWhisper() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(getWhisper()))
    }

    private fun getWhisper(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val whispers = when {
            hour in 0..5 -> listOf("Still awake? Go to sleep...", "It's late...", "Zzz...")
            hour in 6..8 -> listOf("Good morning!", "Wake up~", "A new day starts!")
            hour in 12..13 -> listOf("Lunch time!", "Don't forget to eat~", "Yummy time")
            else -> listOf("I'm watching you~", "Hello there!", "Pet me!", "Bored?", "Thinking of you~")
        }
        return whispers.random()
    }

    private fun startAppTracking() {
        appTracker = UsageTracker(this) { packageName ->
            onAppChanged(packageName)
        }
        appTracker?.start()
    }

    private fun onAppChanged(packageName: String) {
        handler.post {
            overlayView?.evaluateJavascript(
                "window.petEngine && window.petEngine.onAppChanged('$packageName')", null
            )
        }
        trackRapidSwitching()
        // TODO: POST to Supabase
    }

    // 60 秒内切换 3 个 app → 杂耍模式
    private fun trackRapidSwitching() {
        val now = System.currentTimeMillis()
        switchTimes.addLast(now)
        while (switchTimes.isNotEmpty() && now - switchTimes.first() > 60000) {
            switchTimes.removeFirst()
        }
        if (switchTimes.size >= 3 && now - rapidSwitchCooldown > 30000) {
            rapidSwitchCooldown = now
            handler.post {
                overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.onRapidSwitching()", null
                )
            }
            Log.i("DeskPet", "rapid app switching detected")
        }
    }

    /* ================= 电量感知 ================= */
    private fun registerBatteryMonitor() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                reportBattery(intent)
            }
        }
        registerReceiver(batteryReceiver, filter)
        reportBattery(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }

    private fun reportBattery(intent: Intent?) {
        if (intent == null) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        handler.post {
            overlayView?.evaluateJavascript(
                "window.petEngine && window.petEngine.onBattery($charging, $pct)", null
            )
        }
        Log.i("DeskPet", "battery: charging=$charging level=$pct")
    }

    /* ================= AI 实时推送轮询(Supabase) ================= */
    private fun startPushPolling() {
        if (pushRunning) return
        pushRunning = true
        pushThread = Thread {
            while (pushRunning) {
                Thread.sleep(15000)
                if (pushRunning) {
                    try {
                        pollAiMessages()
                    } catch (e: Exception) {
                        Log.w("DeskPet", "push poll error: ${e.message}")
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        Log.i("DeskPet", "AI push polling started")
    }

    private fun pollAiMessages() {
        val msg = runBlocking { SupabaseClient.fetchLatest("pet_messages", "created_at", 1) } ?: return
        val id = msg.optString("id", "")
        if (id.isNotEmpty() && id != lastSeenMessageId) {
            lastSeenMessageId = id
            val state = JSONObject().apply {
                msg.optString("bubble", "").takeIf { it.isNotEmpty() }?.let { put("bubble", it) }
                msg.optString("text", "").takeIf { it.isNotEmpty() }?.let { put("text", it) }
                msg.optString("mood", "").takeIf { it.isNotEmpty() }?.let { put("mood", it) }
                val heatV = msg.optInt("heat", -1)
                if (heatV in 0..100) put("heat", heatV)
            }
            if (state.length() > 0) {
                handler.post {
                    overlayView?.evaluateJavascript(
                        "window.petEngine && window.petEngine.setState(${state.toString()})", null
                    )
                }
                Log.i("DeskPet", "AI push applied: $state")
            }
        }
    }

    private fun startScreenshotDetection() {
        screenshotObserver = ScreenshotObserver(this) {
            onScreenshotDetected()
        }
        screenshotObserver?.start()
    }

    private fun onScreenshotDetected() {
        handler.post {
            overlayView?.evaluateJavascript(
                "window.petEngine && window.petEngine.onScreenshot()", null
            )
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        whisperRunnable?.let { handler.removeCallbacks(it) }
        appTracker?.stop()
        screenshotObserver?.stop()
        pushRunning = false
        pushThread?.interrupt()
        pushThread = null
        try {
            batteryReceiver?.let { unregisterReceiver(it) }
        } catch (e: IllegalArgumentException) {
            // 已注销则忽略
        }
        batteryReceiver = null
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
