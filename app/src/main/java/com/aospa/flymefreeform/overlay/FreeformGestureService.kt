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
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.aospa.flymefreeform.App
import com.aospa.flymefreeform.R
import com.aospa.flymefreeform.core.AppCatalog
import com.aospa.flymefreeform.core.RootFreeformEngine
import com.aospa.flymefreeform.data.FreeformSettings
import com.aospa.flymefreeform.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FreeformGestureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var quickLaunchPanel: QuickLaunchPanel
    private lateinit var notificationBanner: NotificationBannerView

    private val overlayViews = mutableListOf<View>()
    private var topControlHandle: View? = null
    private var topHandleAdded = false
    private var feedbackCapsule: TextView? = null
    private var monitorJob: Job? = null

    private var currentSettings: FreeformSettings = FreeformSettings()
    private var lastTopBarTapMs = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        quickLaunchPanel = QuickLaunchPanel(
            context = this,
            windowManager = windowManager,
            onSelectApp = { pkg -> launchPackageInFreeform(pkg) },
            onDismissCurrentFreeform = { triggerDismissFreeform() }
        )

        notificationBanner = NotificationBannerView(
            context = this,
            windowManager = windowManager,
            onOpenInFreeform = { pkg -> launchPackageInFreeform(pkg) }
        )

        serviceScope.launch {
            App.instance.settings.settings.collectLatest { s ->
                currentSettings = s
                rebuildOverlays(s)
            }
        }

        startFreeformStateMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OPEN_PANEL -> openQuickPanel()
            ACTION_DISMISS_FREEFORM -> triggerDismissFreeform()
            ACTION_LAUNCH_PACKAGE -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (!pkg.isNullOrBlank()) launchPackageInFreeform(pkg)
            }
            ACTION_SHOW_BANNER -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (pkg.isNotBlank() && currentSettings.notificationBannerEnabled) {
                    showNotificationBanner(pkg, title, text)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        monitorJob?.cancel()
        clearOverlays()
        removeTopControlHandle()
        hideFeedbackCapsule()
        quickLaunchPanel.dismiss()
        notificationBanner.dismiss()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

        // 1. 左下角斜向内滑呼出小窗菜单
        if (settings.cornerLeftLaunchEnabled) {
            addCornerHotzone(isLeft = true, settings = settings)
        }
        // 2. 右下角斜向内滑呼出小窗菜单
        if (settings.cornerRightLaunchEnabled) {
            addCornerHotzone(isLeft = false, settings = settings)
        }
        // 3. 侧边栏滑出呼出小窗菜单
        if (settings.sidebarLaunchEnabled) {
            addSidebarLaunchHotzone(onRight = settings.sidebarOnRight, settings = settings)
        }
        // 4. 屏幕边缘一键取消小窗手势热区
        if (settings.edgeDismissGestureEnabled) {
            addEdgeDismissHotzone(onLeft = settings.edgeDismissOnLeft, settings = settings)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun addCornerHotzone(isLeft: Boolean, settings: FreeformSettings) {
        val view = View(this).apply {
            setBackgroundColor(
                if (settings.showHotzoneDebug) Color.parseColor("#552196F3") else Color.TRANSPARENT
            )
        }
        val thresholdPx = dp(settings.gestureSensitivityDp)
        var startX = 0f
        var startY = 0f
        var armed = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    armed = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = startY - event.rawY // 向上为正
                    val inward = if (isLeft) dx > thresholdPx * 0.65f else dx < -thresholdPx * 0.65f
                    val upward = dy > thresholdPx * 0.65f
                    if (inward && upward && !armed) {
                        armed = true
                        vibrateTick()
                        showFeedbackCapsule("松手呼出小窗应用菜单")
                    } else if (!(inward && upward) && armed) {
                        armed = false
                        hideFeedbackCapsule()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideFeedbackCapsule()
                    if (armed && event.actionMasked == MotionEvent.ACTION_UP) {
                        openQuickPanel()
                    }
                    armed = false
                    true
                }

                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            dp(36),
            dp(96),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (isLeft) Gravity.START else Gravity.END) or Gravity.BOTTOM
            y = dp(24)
        }

        try {
            windowManager.addView(view, params)
            overlayViews.add(view)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addSidebarLaunchHotzone(onRight: Boolean, settings: FreeformSettings) {
        val view = FrameLayout(this).apply {
            setBackgroundColor(
                if (settings.showHotzoneDebug) Color.parseColor("#554CAF50") else Color.TRANSPARENT
            )
            // 半透明细指示条
            val pill = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(Color.parseColor("#66FFFFFF"))
                }
                layoutParams = FrameLayout.LayoutParams(dp(4), dp(54)).apply {
                    gravity = (if (onRight) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            }
            addView(pill)
        }

        val thresholdPx = dp(settings.gestureSensitivityDp)
        var startX = 0f
        var startY = 0f
        var armed = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    armed = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = abs(event.rawY - startY)
                    val inward = if (onRight) dx < -thresholdPx * 0.7f else dx > thresholdPx * 0.7f
                    if (inward && dy < thresholdPx * 1.4f && !armed) {
                        armed = true
                        vibrateTick()
                        showFeedbackCapsule("松手打开小窗快捷栏")
                    } else if (!inward && armed) {
                        armed = false
                        hideFeedbackCapsule()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideFeedbackCapsule()
                    if (armed && event.actionMasked == MotionEvent.ACTION_UP) {
                        openQuickPanel()
                    }
                    armed = false
                    true
                }

                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            dp(18),
            dp(140),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (onRight) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            y = -dp(40)
        }

        try {
            windowManager.addView(view, params)
            overlayViews.add(view)
        } catch (_: Exception) {
        }
    }

    /**
     * 屏幕边缘一键取消小窗手势热区：向内快速滑动或向下滑动即可直接取消当前小窗
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addEdgeDismissHotzone(onLeft: Boolean, settings: FreeformSettings) {
        val view = FrameLayout(this).apply {
            setBackgroundColor(
                if (settings.showHotzoneDebug) Color.parseColor("#55F44336") else Color.TRANSPARENT
            )
            val indicator = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(Color.parseColor("#66FF6B6B"))
                }
                layoutParams = FrameLayout.LayoutParams(dp(4), dp(42)).apply {
                    gravity = (if (onLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            }
            addView(indicator)
        }

        val thresholdPx = dp(settings.gestureSensitivityDp)
        var startX = 0f
        var startY = 0f
        var armed = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    armed = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val inward = if (onLeft) dx > thresholdPx * 0.75f else dx < -thresholdPx * 0.75f
                    val swipeDown = dy > thresholdPx * 0.9f
                    if ((inward || swipeDown) && !armed) {
                        armed = true
                        vibrateTick()
                        showFeedbackCapsule("松手取消当前小窗")
                    } else if (!(inward || swipeDown) && armed) {
                        armed = false
                        hideFeedbackCapsule()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideFeedbackCapsule()
                    if (armed && event.actionMasked == MotionEvent.ACTION_UP) {
                        triggerDismissFreeform()
                    }
                    armed = false
                    true
                }

                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            dp(18),
            dp(116),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (onLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            y = -dp(140)
        }

        try {
            windowManager.addView(view, params)
            overlayViews.add(view)
        } catch (_: Exception) {
        }
    }

    /**
     * 当检测到存在活跃小窗时，在顶部自动显示轻量悬浮控制条：
     * - 上滑关闭小窗
     * - 双击关闭小窗
     * - 下滑转全屏
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun ensureTopControlHandleVisible() {
        if (topHandleAdded || !Settings.canDrawOverlays(this)) return
        val s = currentSettings
        if (!s.topBarDismissSwipeUpEnabled && !s.topBarDismissDoubleTapEnabled && !s.topBarExpandSwipeDownEnabled) {
            return
        }

        val container = FrameLayout(this).apply {
            setPadding(dp(18), dp(8), dp(18), dp(12))
            if (s.showHotzoneDebug) {
                setBackgroundColor(Color.parseColor("#44FF9800"))
            }
        }

        val pill = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#CCFFFFFF"))
                setStroke(dp(1), Color.parseColor("#55000000"))
            }
            layoutParams = FrameLayout.LayoutParams(dp(54), dp(6)).apply {
                gravity = Gravity.CENTER
            }
        }
        container.addView(pill)

        var startY = 0f
        var startX = 0f
        var actionMode = 0 // 0=none, 1=dismiss(up), 2=expand(down)

        container.setOnTouchListener { _, event ->
            val thresholdPx = dp(currentSettings.gestureSensitivityDp.coerceAtMost(44))
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    actionMode = 0
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - startY
                    when {
                        currentSettings.topBarDismissSwipeUpEnabled && dy < -thresholdPx * 0.65f -> {
                            if (actionMode != 1) {
                                actionMode = 1
                                vibrateTick()
                                showFeedbackCapsule("松手取消小窗")
                            }
                        }

                        currentSettings.topBarExpandSwipeDownEnabled && dy > thresholdPx * 0.9f -> {
                            if (actionMode != 2) {
                                actionMode = 2
                                vibrateTick()
                                showFeedbackCapsule("松手展开为全屏")
                            }
                        }

                        else -> {
                            if (actionMode != 0) {
                                actionMode = 0
                                hideFeedbackCapsule()
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    hideFeedbackCapsule()
                    val dx = abs(event.rawX - startX)
                    val dy = abs(event.rawY - startY)
                    when (actionMode) {
                        1 -> triggerDismissFreeform()
                        2 -> triggerExpandFullscreen()
                        else -> {
                            if (currentSettings.topBarDismissDoubleTapEnabled && dx < dp(14) && dy < dp(14)) {
                                val now = System.currentTimeMillis()
                                if (now - lastTopBarTapMs < 360L) {
                                    lastTopBarTapMs = 0L
                                    vibrateTick()
                                    triggerDismissFreeform()
                                } else {
                                    lastTopBarTapMs = now
                                    flashToastCapsule("再点一次取消小窗 · 上滑也可取消")
                                }
                            }
                        }
                    }
                    actionMode = 0
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    hideFeedbackCapsule()
                    actionMode = 0
                    true
                }

                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            dp(120),
            dp(32),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(42)
        }

        try {
            windowManager.addView(container, params)
            topControlHandle = container
            topHandleAdded = true
        } catch (_: Exception) {
        }
    }

    private fun removeTopControlHandle() {
        val v = topControlHandle ?: return
        topControlHandle = null
        topHandleAdded = false
        try {
            windowManager.removeView(v)
        } catch (_: Exception) {
        }
    }

    /**
     * 后台低频轮询当前是否存在 Freeform 活跃小窗，仅在小窗存在时显示顶部控制条
     */
    private fun startFreeformStateMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                val hasFreeform = RootFreeformEngine.getActiveFreeformTasks().isNotEmpty()
                if (hasFreeform) {
                    ensureTopControlHandleVisible()
                } else {
                    removeTopControlHandle()
                }
                delay(1800L)
            }
        }
    }

    private fun openQuickPanel() {
        serviceScope.launch {
            val allApps = AppCatalog.loadLaunchableApps(this@FreeformGestureService)
            val chosen = currentSettings.quickLaunchPackages
            val filtered = allApps.filter { it.packageName in chosen }
                .ifEmpty { allApps.take(12) }
            quickLaunchPanel.show(filtered)
        }
    }

    private fun launchPackageInFreeform(packageName: String) {
        serviceScope.launch {
            val ok = RootFreeformEngine.launchInFreeform(
                context = this@FreeformGestureService,
                packageName = packageName,
                widthRatio = currentSettings.windowWidthRatio,
                heightRatio = currentSettings.windowHeightRatio
            )
            if (ok) {
                ensureTopControlHandleVisible()
            } else {
                flashToastCapsule("启动小窗失败，请检查 Root 权限")
            }
        }
    }

    private fun triggerDismissFreeform() {
        serviceScope.launch {
            val ok = RootFreeformEngine.dismissTopFreeformTask()
            if (ok) {
                removeTopControlHandle()
                flashToastCapsule("已取消小窗")
            } else {
                flashToastCapsule("当前没有可取消的小窗")
            }
        }
    }

    private fun triggerExpandFullscreen() {
        serviceScope.launch {
            val ok = RootFreeformEngine.expandTopFreeformToFullscreen()
            if (ok) {
                removeTopControlHandle()
                flashToastCapsule("已展开为全屏")
            }
        }
    }

    private fun showNotificationBanner(packageName: String, title: String, text: String) {
        serviceScope.launch {
            val entry = withContext(Dispatchers.IO) {
                AppCatalog.loadAppEntry(this@FreeformGestureService, packageName)
            }
            notificationBanner.show(
                packageName = packageName,
                appName = entry?.label ?: packageName,
                appIcon = entry?.icon,
                title = title,
                text = text
            )
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Flyme 小窗手势服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持底角呼出小窗与手势取消小窗服务运行"
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
            .setContentTitle("Flyme小窗 · AOSPA 运行中")
            .setContentText("底角/侧边呼出小窗 · 顶部条上滑或边缘手势取消小窗")
            .setContentIntent(openAppIntent)
            .addAction(0, "取消当前小窗", dismissIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "flyme_freeform_service"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_OPEN_PANEL = "com.aospa.flymefreeform.OPEN_PANEL"
        const val ACTION_DISMISS_FREEFORM = "com.aospa.flymefreeform.DISMISS_FREEFORM"
        const val ACTION_LAUNCH_PACKAGE = "com.aospa.flymefreeform.LAUNCH_PACKAGE"
        const val ACTION_SHOW_BANNER = "com.aospa.flymefreeform.SHOW_BANNER"

        const val EXTRA_PACKAGE_NAME = "extra_package"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"

        @Volatile
        var instance: FreeformGestureService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, FreeformGestureService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FreeformGestureService::class.java))
        }
    }
}
