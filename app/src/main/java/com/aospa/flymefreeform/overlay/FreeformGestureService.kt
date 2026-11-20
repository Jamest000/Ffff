package com.aospa.flymefreeform.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import com.aospa.flymefreeform.App
import com.aospa.flymefreeform.R
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.aospa.flymefreeform.core.AppCatalog
import com.aospa.flymefreeform.core.RootFreeformEngine
import com.aospa.flymefreeform.core.WechatRecorder
import com.aospa.flymefreeform.data.FreeformSettings
import com.aospa.flymefreeform.ui.MainActivity
import com.aospa.flymefreeform.xposed.SystemUiHookEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * v2.0 伴侣服务（零干扰原生气泡）：
 * - 不再执行 am task resize，彻底杜绝 QQ 等原生气泡半屏卡顿
 * - 负责全圆角弹幕通知 & 悬浮横幅
 * - 负责左侧红条手势：向内滑直接关闭当前气泡，向下滑把气泡收起成悬浮圆球
 */
class FreeformGestureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var notificationBanner: NotificationBannerView
    private lateinit var danmakuView: DanmakuNotificationView
    private lateinit var controlCenter: ControlCenterOverlay
    private lateinit var quickLaunchPanel: QuickLaunchPanel

    private val overlayViews = mutableListOf<View>()
    private var feedbackCapsule: TextView? = null
    private var currentSettings: FreeformSettings = FreeformSettings()
    private val recReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SystemUiHookEntry.ACTION_TOGGLE_WECHAT_REC) {
                val msg = WechatRecorder.toggle(this@FreeformGestureService)
                flashToastCapsule(msg)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        val recFilter = IntentFilter(SystemUiHookEntry.ACTION_TOGGLE_WECHAT_REC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recReceiver, recFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(recReceiver, recFilter)
        }

        notificationBanner = NotificationBannerView(
            context = this,
            windowManager = windowManager,
            onOpenInFreeform = { pkg -> openAppDirectly(pkg) }
        )

        danmakuView = DanmakuNotificationView(
            context = this,
            windowManager = windowManager,
            onClickOpenFreeform = { pkg -> openAppDirectly(pkg) }
        )

        controlCenter = ControlCenterOverlay(
            context = this,
            windowManager = windowManager,
            scope = serviceScope,
            onToast = { msg -> flashToastCapsule(msg) }
        )

        quickLaunchPanel = QuickLaunchPanel(
            context = this,
            windowManager = windowManager,
            onSelectApp = { pkg -> launchAppBubble(pkg) },
            onDismissCurrentFreeform = { sendBubbleDismiss() }
        )

        serviceScope.launch {
            App.instance.settings.settings.collectLatest { s ->
                currentSettings = s
                rebuildOverlays(s)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS_FREEFORM -> sendBubbleDismiss()
            ACTION_COLLAPSE_BUBBLE -> sendBubbleCollapse()
            ACTION_OPEN_CONTROL_CENTER -> controlCenter.show(currentSettings)
            ACTION_FLASH_TOAST -> {
                val msg = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (msg.isNotBlank()) flashToastCapsule(msg)
            }
            ACTION_SHOW_BANNER -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (pkg.isNotBlank() && (currentSettings.danmakuNotificationEnabled || currentSettings.notificationBannerEnabled)) {
                    showNotificationOverlay(pkg, title, text)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        clearOverlays()
        hideFeedbackCapsule()
        notificationBanner.dismiss()
        danmakuView.clearAll()
        controlCenter.dismiss()
        quickLaunchPanel.dismiss()
        serviceScope.cancel()
        try { unregisterReceiver(recReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun excludeFromSystemBackGesture(view: View) {
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 0 && v.height > 0) {
                ViewCompat.setSystemGestureExclusionRects(
                    v,
                    listOf(Rect(0, 0, v.width, v.height))
                )
            }
        }
    }

    private fun vibrateTick() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } catch (_: Exception) {
        }
    }

    private fun showFeedbackCapsule(message: String) {
        val view = feedbackCapsule ?: TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#E61C2029"))
                setStroke(dp(1), Color.parseColor("#44FFFFFF"))
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(92)
            }
            try {
                windowManager.addView(this, params)
                feedbackCapsule = this
            } catch (_: Exception) {
            }
        }
        view.text = message
    }

    private fun hideFeedbackCapsule() {
        val v = feedbackCapsule ?: return
        feedbackCapsule = null
        try {
            windowManager.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun flashToastCapsule(message: String) {
        showFeedbackCapsule(message)
        mainHandler.postDelayed({ hideFeedbackCapsule() }, 1200L)
    }

    private fun rebuildOverlays(settings: FreeformSettings) {
        clearOverlays()
        if (!Settings.canDrawOverlays(this)) return
        if (settings.cornerLeftLaunchEnabled) {
            addLeftCornerLaunchHotzone(settings)
        }
        if (settings.sidebarLaunchEnabled) {
            addRightSidebarLaunchHotzone(settings)
        }
        if (settings.edgeDismissGestureEnabled) {
            addEdgeBubbleControlHotzone(onLeft = settings.edgeDismissOnLeft, settings = settings)
        }
        if (settings.customControlCenterEnabled) {
            addTopRightControlCenterHotzone(settings)
        }
    }

    private fun clearOverlays() {
        overlayViews.forEach { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
        overlayViews.clear()
    }

    /**
     * 边缘气泡快捷手势条（默认在左侧，避开右侧与右下角 Gemini）：
     * - 向内滑 / 轻点：直接取消关闭当前原生气泡
     * - 向下滑：将当前原生气泡收起为悬浮圆球
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addEdgeBubbleControlHotzone(onLeft: Boolean, settings: FreeformSettings) {
        val view = FrameLayout(this).apply {
            setBackgroundColor(
                if (settings.showHotzoneDebug) Color.parseColor("#55F44336") else Color.TRANSPARENT
            )
            val indicator = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(Color.parseColor("#AAFF5252"))
                }
                layoutParams = FrameLayout.LayoutParams(dp(4), dp(52)).apply {
                    gravity = (if (onLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            }
            addView(indicator)
        }
        excludeFromSystemBackGesture(view)

        val thresholdPx = dp(settings.gestureSensitivityDp.coerceAtMost(40))
        var startX = 0f
        var startY = 0f
        var mode = 0 // 0=none, 1=dismiss(inward), 2=collapse(down)

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    mode = 0
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val inward = if (onLeft) dx > thresholdPx * 0.6f else dx < -thresholdPx * 0.6f
                    val swipeDown = dy > thresholdPx * 0.75f
                    when {
                        inward && abs(dx) >= abs(dy) -> {
                            if (mode != 1) {
                                mode = 1
                                vibrateTick()
                                showFeedbackCapsule("松手取消当前气泡")
                            }
                        }

                        swipeDown && abs(dy) > abs(dx) -> {
                            if (mode != 2) {
                                mode = 2
                                vibrateTick()
                                showFeedbackCapsule("松手收起气泡为圆球")
                            }
                        }

                        else -> {
                            if (mode != 0) {
                                mode = 0
                                hideFeedbackCapsule()
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideFeedbackCapsule()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val dx = abs(event.rawX - startX)
                        val dy = abs(event.rawY - startY)
                        when {
                            mode == 1 || (dx < dp(14) && dy < dp(14)) -> {
                                vibrateTick()
                                sendBubbleDismiss()
                            }

                            mode == 2 -> {
                                vibrateTick()
                                sendBubbleCollapse()
                            }
                        }
                    }
                    mode = 0
                    true
                }

                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            dp(26),
            dp(104),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (onLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            y = -dp(130)
        }

        try {
            windowManager.addView(view, params)
            overlayViews.add(view)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addTopRightControlCenterHotzone(settings: FreeformSettings) {
        val view = View(this).apply {
            setBackgroundColor(
                if (settings.showHotzoneDebug) Color.parseColor("#559C27B0") else Color.TRANSPARENT
            )
        }
        var startY = 0f
        var pulling = false
        var vt: VelocityTracker? = null

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    pulling = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain().apply { addMovement(event) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(event)
                    val dy = event.rawY - startY
                    if (dy > dp(8)) {
                        if (!pulling) {
                            pulling = true
                            vibrateTick()
                            controlCenter.beginInteractivePull(currentSettings)
                        }
                        controlCenter.updateInteractivePull(dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    if (pulling) {
                        controlCenter.finishInteractivePull(vy)
                    }
                    pulling = false
                    true
                }
                else -> false
            }
        }
        val barWidth = if (settings.ccFullTopBarTakeover) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            dp(150)
        }
        val params = WindowManager.LayoutParams(
            barWidth,
            dp(30),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
        }
        try {
            windowManager.addView(view, params)
            overlayViews.add(view)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addLeftCornerLaunchHotzone(settings: FreeformSettings) {
        val view = View(this).apply {
            setBackgroundColor(
                if (settings.showHotzoneDebug) Color.parseColor("#552196F3") else Color.TRANSPARENT
            )
        }
        excludeFromSystemBackGesture(view)
        val thresholdPx = dp(settings.gestureSensitivityDp.coerceAtMost(40))
        var startX = 0f
        var startY = 0f
        var armed = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; armed = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = startY - event.rawY
                    if (dx > thresholdPx * 0.55f && dy > thresholdPx * 0.55f && !armed) {
                        armed = true; vibrateTick(); showFeedbackCapsule("松手呼出小窗菜单")
                    } else if (!(dx > thresholdPx * 0.55f && dy > thresholdPx * 0.55f) && armed) {
                        armed = false; hideFeedbackCapsule()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideFeedbackCapsule()
                    if (armed && event.actionMasked == MotionEvent.ACTION_UP) openQuickPanel()
                    armed = false; true
                }
                else -> false
            }
        }
        val params = WindowManager.LayoutParams(
            dp(44), dp(96),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.BOTTOM; y = dp(20) }
        try { windowManager.addView(view, params); overlayViews.add(view) } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addRightSidebarLaunchHotzone(settings: FreeformSettings) {
        val view = FrameLayout(this).apply {
            setBackgroundColor(
                if (settings.showHotzoneDebug) Color.parseColor("#554CAF50") else Color.TRANSPARENT
            )
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(Color.parseColor("#88FFFFFF"))
                }
                layoutParams = FrameLayout.LayoutParams(dp(4), dp(60)).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    marginEnd = dp(2)
                }
            })
        }
        excludeFromSystemBackGesture(view)
        val thresholdPx = dp(settings.gestureSensitivityDp.coerceAtMost(38))
        var startX = 0f
        var startY = 0f
        var armed = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; armed = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = abs(event.rawY - startY)
                    if (dx < -thresholdPx * 0.55f && dy < thresholdPx * 1.8f && !armed) {
                        armed = true; vibrateTick(); showFeedbackCapsule("松手打开小窗快捷栏")
                    } else if (!(dx < -thresholdPx * 0.55f) && armed) {
                        armed = false; hideFeedbackCapsule()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideFeedbackCapsule()
                    val isTap = event.actionMasked == MotionEvent.ACTION_UP && abs(event.rawX - startX) < dp(14) && abs(event.rawY - startY) < dp(14)
                    if ((armed && event.actionMasked == MotionEvent.ACTION_UP) || isTap) {
                        vibrateTick(); openQuickPanel()
                    }
                    armed = false; true
                }
                else -> false
            }
        }
        val params = WindowManager.LayoutParams(
            dp(26), dp(116),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; y = -dp(30) }
        try { windowManager.addView(view, params); overlayViews.add(view) } catch (_: Exception) {}
    }

    private fun openQuickPanel() {
        serviceScope.launch {
            val allApps = AppCatalog.loadLaunchableApps(this@FreeformGestureService)
            val chosen = currentSettings.quickLaunchPackages
            val filtered = allApps.filter { it.packageName in chosen }.ifEmpty { allApps.take(12) }
            quickLaunchPanel.show(filtered)
        }
    }

    private fun launchAppBubble(pkg: String) {
        serviceScope.launch {
            val ok = RootFreeformEngine.launchInFreeform(
                context = this@FreeformGestureService,
                packageName = pkg,
                widthRatio = 0.80f,
                heightRatio = 0.56f
            )
            if (!ok) {
                flashToastCapsule("小窗启动失败：请先授予 Root 权限")
            }
        }
    }

    private fun sendBubbleDismiss() {
        sendBroadcast(Intent(SystemUiHookEntry.ACTION_BUBBLE_DISMISS))
        flashToastCapsule("已发送取消气泡指令")
    }

    private fun sendBubbleCollapse() {
        sendBroadcast(Intent(SystemUiHookEntry.ACTION_BUBBLE_COLLAPSE))
        flashToastCapsule("已收起气泡")
    }

    private fun openAppDirectly(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launchIntent)
        } catch (_: Exception) {
        }
    }

    private fun showNotificationOverlay(packageName: String, title: String, text: String) {
        serviceScope.launch {
            val entry = withContext(Dispatchers.IO) {
                AppCatalog.loadAppEntry(this@FreeformGestureService, packageName)
            }
            val appName = entry?.label ?: packageName
            if (currentSettings.danmakuNotificationEnabled) {
                danmakuView.show(
                    packageName = packageName,
                    appName = appName,
                    appIcon = entry?.icon,
                    title = title,
                    text = text
                )
            }
            if (currentSettings.notificationBannerEnabled) {
                notificationBanner.show(
                    packageName = packageName,
                    appName = appName,
                    appIcon = entry?.icon,
                    title = title,
                    text = text
                )
            }
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AOSPA 气泡与弹幕服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "提供全圆角弹幕通知与侧边一键取消气泡手势"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismissIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FreeformGestureService::class.java).setAction(ACTION_DISMISS_FREEFORM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Flyme小窗 · AOSPA 17 增强运行中")
            .setContentText("弹幕通知已就绪 · 左侧红条可一键取消/收起原生气泡")
            .setContentIntent(openAppIntent)
            .addAction(0, "取消当前气泡", dismissIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "flyme_freeform_service"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_DISMISS_FREEFORM = "com.aospa.flymefreeform.DISMISS_FREEFORM"
        const val ACTION_COLLAPSE_BUBBLE = "com.aospa.flymefreeform.COLLAPSE_BUBBLE"
        const val ACTION_OPEN_CONTROL_CENTER = "com.aospa.flymefreeform.OPEN_CONTROL_CENTER"
        const val ACTION_FLASH_TOAST = "com.aospa.flymefreeform.FLASH_TOAST"
        const val ACTION_SHOW_BANNER = "com.aospa.flymefreeform.SHOW_BANNER"

        const val EXTRA_PACKAGE_NAME = "extra_package"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"

        @Volatile
        var instance: FreeformGestureService? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FreeformGestureService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FreeformGestureService::class.java))
        }
    }
}
