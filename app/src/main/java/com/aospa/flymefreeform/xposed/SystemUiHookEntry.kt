package com.aospa.flymefreeform.xposed

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 专为小米 13 AOSPA Android 17 SystemUI.apk 编写的 LSPosed 模块入口（v4.1 直觉手势版）：
 * 1. 原生控制中心直替（注入 QuickSettingsControllerImpl.mQsFrame，100% 复用原生 120Hz 动画）
 * 2. 原生气泡小窗魔改：去遮罩、双窗同操作、自定义高度、气泡底部直觉手势条（直上滑取消、向左上/右上刮直接挂起缩小到左上/右上角）
 * 3. 状态栏时钟多种样式 + 字号缩放 + 粗字体防显示不全保护
 * 4. 通知栏卡片多种圆角与通透风格
 */
class SystemUiHookEntry : IXposedHookLoadPackage {

    companion object {
        const val MODULE_PKG = "com.aospa.flymefreeform"
        const val PREFS_NAME = "flyme_freeform_prefs"

        const val ACTION_RELOAD_CONFIG = "com.aospa.flymefreeform.XPOSED_RELOAD_CONFIG"
        const val ACTION_BUBBLE_COLLAPSE = "com.aospa.flymefreeform.XPOSED_BUBBLE_COLLAPSE"
        const val ACTION_BUBBLE_DISMISS = "com.aospa.flymefreeform.XPOSED_BUBBLE_DISMISS"
        const val ACTION_TOGGLE_WECHAT_REC = "com.aospa.flymefreeform.TOGGLE_WECHAT_REC"
        const val ACTION_LAUNCH_APP_BUBBLE = "com.aospa.flymefreeform.XPOSED_LAUNCH_APP_BUBBLE"
        const val EXTRA_PKG = "pkg"
    }

    private var prefs: XSharedPreferences? = null

    @Volatile private var xpNativeQsEnabled = true
    @Volatile private var ccConfig = NativeCcStyleConfig()

    @Volatile private var bubbleNoScrim = true
    @Volatile private var bubbleAllowTouchOutside = true
    @Volatile private var bubbleHeightScale = 0.78f
    @Volatile private var clockStyle = 2
    @Volatile private var clockBold = true
    @Volatile private var clockScale = 1.0f
    @Volatile private var fixBoldClipping = true
    @Volatile private var notifCardStyle = 2

    @Volatile private var bubbleControllerRef: Any? = null
    @Volatile private var bubbleStackViewRef: Any? = null
    @Volatile private var bubblePositionerRef: Any? = null
    @Volatile private var bubbleGestureHandle: View? = null
    @Volatile private var bubbleHintCapsule: TextView? = null
    @Volatile private var qsFrameRef: FrameLayout? = null
    @Volatile private var nativeCcViewRef: NativeQsControlCenterView? = null
    @Volatile private var receiverRegistered = false

    private fun reloadPrefs() {
        try {
            val p = prefs ?: XSharedPreferences(MODULE_PKG, PREFS_NAME).also { prefs = it }
            p.makeWorldReadable()
            p.reload()
            xpNativeQsEnabled = p.getBoolean("xp_native_qs_enabled", true)
            ccConfig = NativeCcStyleConfig(
                themeMode = p.getInt("cc_theme_mode", 0),
                cardRadiusDp = p.getInt("cc_card_radius_dp", 26),
                cardAlpha = p.getFloat("cc_card_alpha", 0.88f),
                showMusicCard = p.getBoolean("cc_show_music", true),
                showVerticalSliders = p.getBoolean("cc_show_sliders", true),
                showQuickToggles = p.getBoolean("cc_show_toggles", true)
            )
            bubbleNoScrim = p.getBoolean("xp_bubble_no_scrim", true)
            bubbleAllowTouchOutside = p.getBoolean("xp_bubble_touch_outside", true)
            bubbleHeightScale = p.getFloat("xp_bubble_height_scale", 0.78f).coerceIn(0.48f, 1.0f)
            clockStyle = p.getInt("xp_clock_style", 2)
            clockBold = p.getBoolean("xp_clock_bold", true)
            clockScale = p.getFloat("xp_clock_scale", 1.0f).coerceIn(0.80f, 1.15f)
            fixBoldClipping = p.getBoolean("xp_fix_bold_clipping", true)
            notifCardStyle = p.getInt("xp_notif_card_style", 2)
        } catch (t: Throwable) {
            XposedBridge.log("AospaFlymeHook reloadPrefs error: ${t.message}")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == MODULE_PKG) {
            hookSelfActive(lpparam.classLoader)
            return
        }

        if (lpparam.packageName != "com.android.systemui") return

        reloadPrefs()
        XposedBridge.log("AospaFlymeHook v4.1: 已注入 AOSPA 17 SystemUI")

        hookNativeQuickSettings(lpparam.classLoader)
        hookBubbleStackView(lpparam.classLoader)
        hookBubbleController(lpparam.classLoader)
        hookBubblePositioner(lpparam.classLoader)
        hookStatusBarClock(lpparam.classLoader)
        hookNotificationCardStyle(lpparam.classLoader)
    }

    private fun hookSelfActive(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "$MODULE_PKG.xposed.XposedStatus",
                cl,
                "isModuleActive",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun hookNativeQuickSettings(cl: ClassLoader) {
        try {
            val qsCtrlCls = XposedHelpers.findClass(
                "com.android.systemui.shade.QuickSettingsControllerImpl",
                cl
            )
            val panelCtrlCls = XposedHelpers.findClass(
                "com.android.systemui.shade.NotificationPanelViewController",
                cl
            )

            XposedBridge.hookAllConstructors(qsCtrlCls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val frame = XposedHelpers.getObjectField(param.thisObject, "mQsFrame")
                            as? FrameLayout ?: return
                        qsFrameRef = frame
                        registerControlReceiverIfNeeded(frame.context.applicationContext)
                        frame.post { syncNativeQsOverlay(frame, barState = 0) }
                    } catch (_: Throwable) {
                    }
                }
            })

            XposedHelpers.findAndHookMethod(
                panelCtrlCls,
                "setExpandedHeightInternal",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val frame = qsFrameRef ?: return
                        try {
                            val barState = XposedHelpers.getIntField(param.thisObject, "mBarState")
                            val expanded = (param.args[0] as? Float) ?: 0f
                            if (expanded > 10f) {
                                syncNativeQsOverlay(frame, barState)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("AospaFlymeHook NativeQS hook failed: ${t.message}")
        }
    }

    private fun syncNativeQsOverlay(frame: FrameLayout, barState: Int) {
        try {
            val shouldShowCustom = xpNativeQsEnabled && barState == 0

            var customView = nativeCcViewRef
            if (shouldShowCustom) {
                if (customView == null || customView.parent !== frame) {
                    (customView?.parent as? ViewGroup)?.removeView(customView)
                    customView = NativeQsControlCenterView(frame.context, ccConfig)
                    frame.addView(
                        customView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                    nativeCcViewRef = customView
                }
                customView.refreshDynamicInfo()
                customView.visibility = View.VISIBLE
                customView.bringToFront()

                for (i in 0 until frame.childCount) {
                    val child = frame.getChildAt(i)
                    if (child !== customView && child.visibility != View.INVISIBLE) {
                        child.visibility = View.INVISIBLE
                    }
                }
            } else {
                customView?.visibility = View.GONE
                for (i in 0 until frame.childCount) {
                    val child = frame.getChildAt(i)
                    if (child !== customView && child.visibility == View.INVISIBLE) {
                        child.visibility = View.VISIBLE
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookBubbleStackView(cl: ClassLoader) {
        try {
            val stackViewCls = XposedHelpers.findClass(
                "com.android.wm.shell.bubbles.BubbleStackView",
                cl
            )

            XposedBridge.hookAllConstructors(stackViewCls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    bubbleStackViewRef = param.thisObject
                    val view = param.thisObject as? FrameLayout ?: return
                    registerControlReceiverIfNeeded(view.context.applicationContext)
                    view.post { attachIntuitiveBubbleGestureBar(view) }
                }
            })

            XposedBridge.hookAllMethods(stackViewCls, "setExpanded", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val expanded = (param.args.firstOrNull() as? Boolean) ?: false
                    val stackView = param.thisObject as? FrameLayout ?: return
                    stackView.post {
                        attachIntuitiveBubbleGestureBar(stackView)
                        bubbleGestureHandle?.visibility = if (expanded) View.VISIBLE else View.GONE
                        bubbleGestureHandle?.bringToFront()
                        if (expanded) {
                            playQElasticExpandAnimation(stackView)
                        }
                    }
                }
            })

            XposedBridge.hookAllMethods(stackViewCls, "showScrim", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    reloadPrefs()
                    if (bubbleNoScrim && param.args.isNotEmpty() && param.args[0] is Boolean) {
                        if (param.args[0] == true) {
                            param.args[0] = false
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("AospaFlymeHook BubbleStackView hook failed: ${t.message}")
        }
    }

    /**
     * 在原生气泡展开容器底部嵌入 Flyme 风格直觉手势横条：
     * - 笔直向上滑：取消并关闭当前气泡
     * - 向左上角斜滑（刮到左上角）：缩成悬浮圆球并自动吸附到屏幕左上角
     * - 向右上角斜滑（刮到右上角）：缩成悬浮圆球并自动吸附到屏幕右上角
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachIntuitiveBubbleGestureBar(stackView: FrameLayout) {
        try {
            val existing = bubbleGestureHandle
            if (existing != null && existing.parent === stackView) return
            (existing?.parent as? ViewGroup)?.removeView(existing)

            val ctx = stackView.context
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            val handleWrap = FrameLayout(ctx).apply {
                setPadding(dp(24), dp(10), dp(24), dp(14))
                visibility = View.GONE
            }

            val pill = View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#DDF2F4F8"))
                    setStroke(dp(1), Color.parseColor("#55000000"))
                }
                layoutParams = FrameLayout.LayoutParams(dp(96), dp(6)).apply {
                    gravity = Gravity.CENTER
                }
            }
            handleWrap.addView(pill)

            // 顶部实时手势提示胶囊（内嵌在 stackView 内，零额外窗口）
            val hint = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(7), dp(16), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(Color.parseColor("#E61C2029"))
                }
                visibility = View.GONE
            }
            stackView.addView(
                hint,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = dp(96)
                }
            )
            bubbleHintCapsule = hint

            fun vibrate() {
                try {
                    val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    }
                    v?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } catch (_: Throwable) {
                }
            }

            var startX = 0f
            var startY = 0f
            // 0=none, 1=dismiss(直上滑), 2=dockTopLeft(刮向左上角), 3=dockTopRight(刮向右上角)
            var gestureAction = 0

            handleWrap.setOnTouchListener { _, event ->
                val upThreshold = dp(38).toFloat()
                val sideThreshold = dp(38).toFloat()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        gestureAction = 0
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startX
                        val dy = startY - event.rawY // 向上为正
                        val nextAction = when {
                            dy > upThreshold && dx < -sideThreshold -> 2 // 刮到左上角
                            dy > upThreshold && dx > sideThreshold -> 3  // 刮到右上角
                            dy > upThreshold && abs(dx) <= sideThreshold -> 1 // 直上取消
                            else -> 0
                        }
                        if (nextAction != gestureAction) {
                            gestureAction = nextAction
                            if (nextAction != 0) vibrate()
                            when (nextAction) {
                                1 -> {
                                    hint.text = "松手取消当前小窗"
                                    hint.visibility = View.VISIBLE
                                    hint.bringToFront()
                                }

                                2 -> {
                                    hint.text = "松手挂起到左上角"
                                    hint.visibility = View.VISIBLE
                                    hint.bringToFront()
                                }

                                3 -> {
                                    hint.text = "松手挂起到右上角"
                                    hint.visibility = View.VISIBLE
                                    hint.bringToFront()
                                }

                                else -> hint.visibility = View.GONE
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        hint.visibility = View.GONE
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            when (gestureAction) {
                                1 -> dismissCurrentBubble()
                                2 -> dockBubbleToTopCorner(ctx, toLeft = true)
                                3 -> dockBubbleToTopCorner(ctx, toLeft = false)
                            }
                        }
                        gestureAction = 0
                        true
                    }

                    else -> false
                }
            }

            stackView.addView(
                handleWrap,
                FrameLayout.LayoutParams(dp(180), dp(36)).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(28)
                }
            )
            bubbleGestureHandle = handleWrap
        } catch (t: Throwable) {
            XposedBridge.log("AospaFlymeHook attachGestureBar failed: ${t.message}")
        }
    }

    /**
     * 给原生气泡展开视图施加 Flyme 风格 Q 弹弹簧缩放动画
     */
    private fun playQElasticExpandAnimation(stackView: FrameLayout) {
        try {
            val target = (XposedHelpers.callMethod(stackView, "getExpandedView") as? View) ?: stackView
            target.animate().cancel()
            target.scaleX = 0.84f
            target.scaleY = 0.84f
            target.alpha = 0.25f
            target.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(290L)
                .setInterpolator(OvershootInterpolator(1.25f))
                .start()
        } catch (_: Throwable) {
        }
    }

    private fun launchAppAsNativeBubble(pkg: String) {
        val ctrl = bubbleControllerRef ?: return
        try {
            XposedHelpers.callMethod(ctrl, "expandStackAndSelectBubbleFromLauncher", pkg, 0)
        } catch (t: Throwable) {
            XposedBridge.log("AospaFlymeHook launchAppAsNativeBubble($pkg) failed: ${t.message}")
        }
    }

    private fun dismissCurrentBubble() {
        val stack = bubbleStackViewRef ?: return
        try {
            val expanded = XposedHelpers.callMethod(stack, "getExpandedBubble")
            if (expanded != null) {
                XposedHelpers.callMethod(stack, "dismissBubbleIfExists", expanded)
            } else {
                bubbleControllerRef?.let { XposedHelpers.callMethod(it, "collapseStack") }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 将当前气泡缩成悬浮球并自动停靠到屏幕左上角或右上角
     */
    private fun dockBubbleToTopCorner(ctx: Context, toLeft: Boolean) {
        try {
            val dm = ctx.resources.displayMetrics
            val sw = dm.widthPixels.toFloat().coerceAtLeast(1080f)
            val topY = (dm.density * 92f)
            val targetX = if (toLeft) 0f else (sw - dm.density * 64f)

            bubblePositionerRef?.let { pos ->
                try {
                    XposedHelpers.callMethod(pos, "setRestingPosition", PointF(targetX, topY))
                } catch (_: Throwable) {
                }
            }
            bubbleControllerRef?.let { ctrl ->
                try {
                    XposedHelpers.callMethod(ctrl, "collapseStack")
                } catch (_: Throwable) {
                }
            }
            bubbleStackViewRef?.let { stack ->
                (stack as? View)?.postDelayed({
                    try {
                        XposedHelpers.callMethod(stack, "moveStackToPosition", targetX, topY)
                    } catch (_: Throwable) {
                    }
                }, 120L)
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookBubbleController(cl: ClassLoader) {
        try {
            val ctrlCls = XposedHelpers.findClass(
                "com.android.wm.shell.bubbles.BubbleController",
                cl
            )

            XposedBridge.hookAllConstructors(ctrlCls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    bubbleControllerRef = param.thisObject
                }
            })

            XposedHelpers.findAndHookMethod(
                ctrlCls,
                "updateWindowFlagsForBackpress",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        reloadPrefs()
                        if (!bubbleAllowTouchOutside) return
                        try {
                            val ctrl = param.thisObject
                            val lp = XposedHelpers.getObjectField(ctrl, "mWmLayoutParams")
                                as? WindowManager.LayoutParams ?: return
                            val wm = XposedHelpers.getObjectField(ctrl, "mWindowManager")
                                as? WindowManager ?: return
                            val stackView = bubbleStackViewRef as? View ?: return
                            if (!stackView.isAttachedToWindow) return

                            lp.flags = (lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) and
                                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv() and
                                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                            wm.updateViewLayout(stackView, lp)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("AospaFlymeHook BubbleController hook failed: ${t.message}")
        }
    }

    private fun hookBubblePositioner(cl: ClassLoader) {
        try {
            val posCls = XposedHelpers.findClass(
                "com.android.wm.shell.bubbles.BubblePositioner",
                cl
            )

            XposedBridge.hookAllConstructors(posCls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    bubblePositionerRef = param.thisObject
                }
            })

            XposedBridge.hookAllMethods(posCls, "getExpandedViewHeight", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    reloadPrefs()
                    if (bubbleHeightScale >= 0.99f) return
                    val orig = (param.result as? Float) ?: return
                    if (orig > 200f) {
                        param.result = orig * bubbleHeightScale
                    }
                }
            })

            XposedBridge.hookAllMethods(posCls, "getMaxExpandedViewHeight", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    reloadPrefs()
                    if (bubbleHeightScale >= 0.99f) return
                    val orig = (param.result as? Int) ?: return
                    if (orig > 200) {
                        param.result = (orig * bubbleHeightScale).toInt()
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("AospaFlymeHook BubblePositioner hook failed: ${t.message}")
        }
    }

    private fun hookStatusBarClock(cl: ClassLoader) {
        try {
            val clockCls = XposedHelpers.findClass(
                "com.android.systemui.statusbar.policy.Clock",
                cl
            )

            XposedHelpers.findAndHookMethod(
                clockCls,
                "getSmallTime",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        reloadPrefs()
                        val view = param.thisObject as? TextView
                        if (view != null) {
                            view.typeface = if (clockBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                            if (fixBoldClipping) {
                                view.includeFontPadding = false
                                view.maxWidth = Int.MAX_VALUE
                                view.ellipsize = null
                                view.isSingleLine = true
                                val lp = view.layoutParams
                                if (lp != null && lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                                    view.layoutParams = lp
                                }
                            }
                            if (clockScale != 1.0f) {
                                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * clockScale)
                            }
                        }
                        if (clockStyle == 0) return

                        val now = Date()
                        val formatted = when (clockStyle) {
                            1 -> SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
                            2 -> SimpleDateFormat("E HH:mm", Locale.CHINA).format(now)
                            3 -> SimpleDateFormat("M/d E HH:mm", Locale.CHINA).format(now)
                            4 -> SimpleDateFormat("a h:mm", Locale.CHINA).format(now)
                            else -> return
                        }
                        param.result = formatted
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("AospaFlymeHook Clock hook failed: ${t.message}")
        }
    }

    private fun hookNotificationCardStyle(cl: ClassLoader) {
        try {
            val rowCls = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                cl
            )

            XposedHelpers.findAndHookMethod(
                rowCls,
                "calculateMaxRadius",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        reloadPrefs()
                        if (notifCardStyle == 0) return
                        val view = param.thisObject as? View ?: return
                        val density = view.resources.displayMetrics.density
                        val dpRadius = when (notifCardStyle) {
                            1 -> 8f
                            2 -> 24f
                            3 -> 34f
                            else -> return
                        }
                        param.result = dpRadius * density
                    }
                }
            )

            XposedBridge.hookAllMethods(rowCls, "applyRoundnessAndInvalidate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (notifCardStyle == 0) return
                    val view = param.thisObject as? View ?: return
                    val targetAlpha = when (notifCardStyle) {
                        2 -> 0.94f
                        3 -> 0.88f
                        else -> 1.0f
                    }
                    if (view.alpha != targetAlpha) {
                        view.alpha = targetAlpha
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("AospaFlymeHook NotificationRow hook failed: ${t.message}")
        }
    }

    private fun registerControlReceiverIfNeeded(appCtx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true

        val filter = IntentFilter().apply {
            addAction(ACTION_RELOAD_CONFIG)
            addAction(ACTION_BUBBLE_COLLAPSE)
            addAction(ACTION_BUBBLE_DISMISS)
            addAction(ACTION_LAUNCH_APP_BUBBLE)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_RELOAD_CONFIG -> {
                        reloadPrefs()
                        qsFrameRef?.post {
                            nativeCcViewRef?.rebuild(ccConfig)
                            qsFrameRef?.let { syncNativeQsOverlay(it, 0) }
                        }
                    }

                    ACTION_BUBBLE_COLLAPSE -> {
                        bubbleControllerRef?.let {
                            try {
                                XposedHelpers.callMethod(it, "collapseStack")
                            } catch (_: Throwable) {
                            }
                        }
                    }

                    ACTION_BUBBLE_DISMISS -> dismissCurrentBubble()
                    ACTION_LAUNCH_APP_BUBBLE -> {
                        val pkg = intent.getStringExtra(EXTRA_PKG)
                        if (!pkg.isNullOrBlank()) launchAppAsNativeBubble(pkg)
                    }
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appCtx.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            XposedBridge.log("AospaFlymeHook registerReceiver error: ${t.message}")
        }
    }
}
