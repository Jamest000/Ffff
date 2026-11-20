package com.aospa.flymefreeform.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aospa.flymefreeform.core.AppCatalog
import com.aospa.flymefreeform.core.RootShell
import com.aospa.flymefreeform.core.WechatRecorder
import com.aospa.flymefreeform.data.FreeformSettings
import com.aospa.flymefreeform.notification.ActiveNotificationItem
import com.aospa.flymefreeform.notification.FreeformNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 120Hz 逐帧跟手下拉 + 实时渐变原生高斯模糊 + 弹簧曲线自定义控制中心
 */
class ControlCenterOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope,
    private val onToast: (String) -> Unit
) {
    private var rootView: FrameLayout? = null
    private var contentContainer: View? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var activeSettings: FreeformSettings = FreeformSettings()
    private var torchOn = false
    private var currentProgress = 0f

    private val springOut = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)
    private val springClose = PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f)

    fun isShowing(): Boolean = rootView != null

    private fun screenHeight(): Int =
        windowManager.currentWindowMetrics.bounds.height().coerceAtLeast(1920)

    /**
     * 开始逐帧跟手下拉（从第 1 像素就开始挂载并随手指实时滑下）
     */
    fun beginInteractivePull(settings: FreeformSettings) {
        if (rootView == null) {
            attachOverlay(settings, initialProgress = 0.01f)
        }
    }

    /**
     * 根据手指下拉距离实时更新位移、透明度与实时模糊半径
     */
    fun updateInteractivePull(pullDistancePx: Float) {
        val sh = screenHeight().toFloat()
        val progress = (pullDistancePx / (sh * 0.55f)).coerceIn(0.01f, 1f)
        applyProgress(progress)
    }

    /**
     * 手指松开后根据速度与拉动进度执行丝滑展开或回弹收起
     */
    fun finishInteractivePull(velocityYPxPerSec: Float) {
        if (rootView == null) return
        val shouldExpand = currentProgress >= 0.28f || velocityYPxPerSec > 850f
        if (shouldExpand) {
            animateToExpanded()
        } else {
            animateToDismiss()
        }
    }

    fun show(settings: FreeformSettings) {
        if (rootView != null) {
            animateToDismiss()
            return
        }
        attachOverlay(settings, initialProgress = 0.01f)
        animateToExpanded()
    }

    fun dismiss() {
        val v = rootView ?: return
        rootView = null
        contentContainer = null
        rootParams = null
        currentProgress = 0f
        try {
            windowManager.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun applyProgress(progress: Float) {
        val root = rootView ?: return
        val content = contentContainer ?: return
        val params = rootParams ?: return

        currentProgress = progress.coerceIn(0f, 1f)
        val travel = screenHeight() * 0.38f
        content.translationY = -(1f - currentProgress) * travel
        content.scaleX = 0.96f + 0.04f * currentProgress
        content.scaleY = 0.96f + 0.04f * currentProgress
        content.alpha = (currentProgress * 1.35f).coerceIn(0f, 1f)
        root.alpha = currentProgress

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activeSettings.ccBlurRadius > 0) {
            val targetBlur = (activeSettings.ccBlurRadius * currentProgress).toInt().coerceIn(0, 100)
            if (kotlin.math.abs(params.blurBehindRadius - targetBlur) >= 4) {
                params.blurBehindRadius = targetBlur
                try {
                    windowManager.updateViewLayout(root, params)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun animateToExpanded() {
        val root = rootView ?: return
        val content = contentContainer ?: return
        val params = rootParams ?: return

        currentProgress = 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activeSettings.ccBlurRadius > 0) {
            params.blurBehindRadius = activeSettings.ccBlurRadius.coerceIn(0, 100)
            try {
                windowManager.updateViewLayout(root, params)
            } catch (_: Exception) {
            }
        }
        root.animate().alpha(1f).setDuration(240L).setInterpolator(springOut).start()
        content.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(260L)
            .setInterpolator(springOut)
            .start()
    }

    private fun animateToDismiss() {
        val root = rootView ?: return
        val content = contentContainer ?: return
        val travel = screenHeight() * 0.30f
        root.animate().alpha(0f).setDuration(170L).setInterpolator(springClose).start()
        content.animate()
            .translationY(-travel)
            .alpha(0f)
            .setDuration(180L)
            .setInterpolator(springClose)
            .withEndAction { dismiss() }
            .start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachOverlay(settings: FreeformSettings, initialProgress: Float) {
        activeSettings = settings
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val isDark = when (settings.ccThemeMode) {
            1 -> true
            2 -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            else -> false
        }

        val alphaByte = (settings.ccCardAlpha.coerceIn(0.55f, 1.0f) * 255).toInt()
        val cardColor = if (isDark) {
            Color.argb(alphaByte, 32, 35, 42)
        } else {
            Color.argb(alphaByte, 246, 247, 249)
        }
        val surfaceTint = if (isDark) Color.argb(185, 16, 18, 24) else Color.argb(155, 214, 217, 222)
        val textPrimary = if (isDark) Color.WHITE else Color.parseColor("#181A1F")
        val textSecondary = if (isDark) Color.parseColor("#B0B7C3") else Color.parseColor("#5C626E")
        val sliderActiveColor = if (isDark) Color.WHITE else Color.parseColor("#111317")
        val sliderTrackColor = if (isDark) Color.argb(150, 56, 60, 70) else Color.argb(220, 238, 240, 244)
        val radiusPx = dp(settings.ccCardRadiusDp.coerceIn(8, 34)).toFloat()

        fun makeCardBg(r: Float = radiusPx, color: Int = cardColor): GradientDrawable {
            return GradientDrawable().apply {
                cornerRadius = r
                setColor(color)
            }
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(surfaceTint)
            setOnClickListener { animateToDismiss() }
        }

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            setPadding(dp(18), dp(46), dp(18), dp(52))
            clipToPadding = false
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
        }

        // 1. 顶部栏：日期 + 设置/电源圆按钮
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(16))
        }

        val dateText = TextView(context).apply {
            text = SimpleDateFormat("M月d日E", Locale.CHINA).format(Date())
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(dateText)

        fun circleActionBtn(symbol: String, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = symbol
                setTextColor(textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                background = makeCardBg(dp(999).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginStart = dp(10)
                }
                setOnClickListener { onClick() }
            }
        }

        topBar.addView(circleActionBtn("⚙") {
            animateToDismiss()
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })
        topBar.addView(circleActionBtn("⏻") {
            animateToDismiss()
            scope.launch { RootShell.exec("input keyevent --longpress 26") }
        })
        content.addView(topBar)

        // 2. 左侧 2x2 音乐卡 + 右侧双竖向粗条滑块
        if (settings.ccShowMusicCard || settings.ccShowVerticalSliders) {
            val heroRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(178)
                ).apply { bottomMargin = dp(14) }
            }

            if (settings.ccShowMusicCard) {
                val musicCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = makeCardBg()
                    setPadding(dp(14), dp(14), dp(14), dp(12))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1.15f
                    ).apply {
                        if (settings.ccShowVerticalSliders) marginEnd = dp(12)
                    }
                }

                val albumBox = TextView(context).apply {
                    text = "♪"
                    setTextColor(if (isDark) Color.BLACK else Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    gravity = Gravity.CENTER
                    background = makeCardBg(dp(12).toFloat(), sliderActiveColor)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                }
                musicCard.addView(albumBox)

                val trackTitle = TextView(context).apply {
                    text = "媒体播放控制"
                    setTextColor(textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(12), 0, dp(2))
                }
                val trackSub = TextView(context).apply {
                    text = "切歌 / 暂停"
                    setTextColor(textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    maxLines = 1
                }
                musicCard.addView(trackTitle)
                musicCard.addView(trackSub)

                val controls = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                }

                val audioManager = context.getSystemService(AudioManager::class.java)
                fun sendMediaKey(keyCode: Int) {
                    audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                }

                listOf(
                    "⏮" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    "⏯" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    "⏭" to KeyEvent.KEYCODE_MEDIA_NEXT
                ).forEach { (sym, code) ->
                    val btn = TextView(context).apply {
                        text = sym
                        setTextColor(textPrimary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        setOnClickListener { sendMediaKey(code) }
                    }
                    controls.addView(btn)
                }
                musicCard.addView(controls)
                heroRow.addView(musicCard)
            }

            if (settings.ccShowVerticalSliders) {
                val audioManager = context.getSystemService(AudioManager::class.java)
                val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
                val curVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 7
                val curBright = try {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                } catch (_: Exception) {
                    128
                }

                fun buildVerticalSlider(
                    iconText: String,
                    initialRatio: Float,
                    marginEndPx: Int,
                    onRatioChanged: (Float) -> Unit
                ): FrameLayout {
                    var ratio = initialRatio.coerceIn(0.05f, 1f)
                    val track = FrameLayout(context).apply {
                        background = makeCardBg(radiusPx, sliderTrackColor)
                        clipToOutline = true
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0.55f
                        ).apply { marginEnd = marginEndPx }
                    }

                    val fill = View(context).apply {
                        background = makeCardBg(radiusPx * 0.7f, sliderActiveColor)
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            dp((178 * ratio).toInt())
                        ).apply { gravity = Gravity.BOTTOM }
                    }
                    track.addView(fill)

                    val icon = TextView(context).apply {
                        text = iconText
                        setTextColor(if (isDark) Color.BLACK else Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            dp(48)
                        ).apply { gravity = Gravity.BOTTOM }
                    }
                    track.addView(icon)

                    track.setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                val h = v.height.coerceAtLeast(1)
                                ratio = (1f - (event.y / h)).coerceIn(0.04f, 1f)
                                val lp = fill.layoutParams as FrameLayout.LayoutParams
                                lp.height = (h * ratio).toInt().coerceAtLeast(dp(24))
                                fill.layoutParams = lp
                                onRatioChanged(ratio)
                                true
                            }

                            else -> false
                        }
                    }
                    return track
                }

                heroRow.addView(
                    buildVerticalSlider("♪", curVol.toFloat() / maxVol, dp(10)) { r ->
                        val target = (r * maxVol).toInt().coerceIn(0, maxVol)
                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    }
                )
                heroRow.addView(
                    buildVerticalSlider("☀", curBright / 255f, 0) { r ->
                        val b = (r * 255).toInt().coerceIn(8, 255)
                        scope.launch { RootShell.exec("settings put system screen_brightness $b") }
                    }
                )
            }

            content.addView(heroRow)
        }

        // 3. 大圆角胶囊快捷开关
        if (settings.ccShowQuickToggles) {
            fun pillToggle(label: String, icon: String, active: Boolean = false, onClick: () -> Unit): LinearLayout {
                val bg = if (active) Color.parseColor("#E53935") else cardColor
                val fg = if (active) Color.WHITE else textPrimary
                return LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = makeCardBg(dp(999).toFloat(), bg)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
                    setOnClickListener { onClick() }

                    addView(TextView(context).apply {
                        text = icon
                        setTextColor(fg)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) }
                    })
                    addView(TextView(context).apply {
                        text = label
                        setTextColor(fg)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                    })
                }
            }

            val row1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            val wifiBtn = pillToggle("WLAN", "📶") {
                animateToDismiss()
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            (wifiBtn.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)
            val btBtn = pillToggle("蓝牙", "ᛒ") {
                animateToDismiss()
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            row1.addView(wifiBtn)
            row1.addView(btBtn)
            content.addView(row1)

            val row2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            }
            val torchBtn = pillToggle(if (torchOn) "手电筒 · 开" else "手电筒", "🔦", torchOn) {
                try {
                    val cm = context.getSystemService(CameraManager::class.java)
                    val camId = cm.cameraIdList.firstOrNull()
                    if (camId != null) {
                        torchOn = !torchOn
                        cm.setTorchMode(camId, torchOn)
                        dismiss()
                        show(settings)
                    }
                } catch (_: Exception) {
                }
            }
            (torchBtn.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)

            val recording = WechatRecorder.isRecording.value
            val recBtn = pillToggle(if (recording) "录音中 · 点停" else "微信录音", "🎙", recording) {
                val msg = WechatRecorder.toggle(context)
                onToast(msg)
                animateToDismiss()
            }
            row2.addView(torchBtn)
            row2.addView(recBtn)
            content.addView(row2)
        }

        // 4. 分隔横条
        content.addView(View(context).apply {
            background = makeCardBg(dp(4).toFloat(), textSecondary)
            alpha = 0.45f
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        })

        // 5. 通知列表
        if (settings.ccShowNotifications) {
            val notifs: List<ActiveNotificationItem> = FreeformNotificationListener.activeItems.value
            if (notifs.isEmpty()) {
                content.addView(TextView(context).apply {
                    text = "暂无新通知"
                    setTextColor(textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.CENTER
                    background = makeCardBg()
                    setPadding(dp(16), dp(22), dp(16), dp(22))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(12) }
                })
            } else {
                notifs.take(6).forEach { item ->
                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = makeCardBg()
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(10) }
                        setOnClickListener {
                            animateToDismiss()
                            context.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(it)
                            }
                        }
                    }

                    val entry = AppCatalog.loadAppEntry(context, item.packageName)
                    if (entry != null) {
                        card.addView(ImageView(context).apply {
                            setImageDrawable(entry.icon)
                            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) }
                        })
                    }

                    val col = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    col.addView(TextView(context).apply {
                        text = item.title.ifBlank { entry?.label ?: item.packageName }
                        setTextColor(textPrimary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    col.addView(TextView(context).apply {
                        text = item.text.ifBlank { "点击查看详情" }
                        setTextColor(textSecondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    card.addView(col)
                    content.addView(card)
                }
            }

            content.addView(TextView(context).apply {
                text = "全部清除"
                setTextColor(textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = makeCardBg(dp(999).toFloat())
                setPadding(dp(24), dp(12), dp(24), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                setOnClickListener {
                    FreeformNotificationListener.clearAllNotifications()
                    animateToDismiss()
                    onToast("已清除所有可移除通知")
                }
            })
        }

        scroll.addView(content)
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 底部跟手上滑收起把手（拖着往上推即可丝滑收起）
        val bottomDismissHandle = FrameLayout(context).apply {
            setPadding(0, dp(12), 0, dp(16))
            addView(View(context).apply {
                background = makeCardBg(dp(4).toFloat(), textPrimary)
                alpha = 0.5f
                layoutParams = FrameLayout.LayoutParams(dp(108), dp(5)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                }
            })
        }
        var upStartY = 0f
        var upVelocity: VelocityTracker? = null
        bottomDismissHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    upStartY = event.rawY
                    upVelocity?.recycle()
                    upVelocity = VelocityTracker.obtain().apply { addMovement(event) }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    upVelocity?.addMovement(event)
                    val dy = (upStartY - event.rawY).coerceAtLeast(0f)
                    val p = (1f - dy / (screenHeight() * 0.42f)).coerceIn(0.05f, 1f)
                    applyProgress(p)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    upVelocity?.computeCurrentVelocity(1000)
                    val vy = upVelocity?.yVelocity ?: 0f
                    upVelocity?.recycle()
                    upVelocity = null
                    if (currentProgress < 0.72f || vy < -750f) {
                        animateToDismiss()
                    } else {
                        animateToExpanded()
                    }
                    true
                }

                else -> false
            }
        }
        root.addView(
            bottomDismissHandle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { gravity = Gravity.BOTTOM }
        )

        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && settings.ccBlurRadius > 0) {
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            } else {
                0
            }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && settings.ccBlurRadius > 0) {
                blurBehindRadius = (settings.ccBlurRadius * initialProgress).toInt().coerceIn(0, 100)
            }
        }

        try {
            windowManager.addView(root, params)
            rootView = root
            contentContainer = content
            rootParams = params
            applyProgress(initialProgress)
        } catch (_: Exception) {
            rootView = null
        }
    }
}
