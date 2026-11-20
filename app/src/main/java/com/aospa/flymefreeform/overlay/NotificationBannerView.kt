package com.aospa.flymefreeform.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 顶部悬浮通知横幅：点击或向下轻拉直接以小窗打开对应应用，向上轻滑立即忽略
 */
class NotificationBannerView(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onOpenInFreeform: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private val autoDismissRunnable = Runnable { dismiss() }

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        packageName: String,
        appName: String,
        appIcon: Drawable?,
        title: String,
        text: String
    ) {
        dismiss()

        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val bg = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.parseColor("#F21F232B"))
            setStroke(dp(1), Color.parseColor("#38FFFFFF"))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(dp(14), dp(12), dp(14), dp(12))
            elevation = dp(8).toFloat()
        }

        val iconView = ImageView(context).apply {
            if (appIcon != null) setImageDrawable(appIcon)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(12)
            }
        }
        container.addView(iconView)

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(context).apply {
            this.text = if (title.isNotBlank()) "$appName · $title" else appName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val bodyView = TextView(context).apply {
            this.text = text.ifBlank { "点击或下拉以小窗回复" }
            setTextColor(Color.parseColor("#C7D0DF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }

        val hintView = TextView(context).apply {
            this.text = "点击 / 下拉直开小窗 · 上滑关闭"
            setTextColor(Color.parseColor("#8AB4F8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(0, dp(4), 0, 0)
        }

        textColumn.addView(titleView)
        textColumn.addView(bodyView)
        textColumn.addView(hintView)
        container.addView(textColumn)

        var downY = 0f
        var downX = 0f
        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    handler.removeCallbacks(autoDismissRunnable)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dy = event.rawY - downY
                    val dx = event.rawX - downX
                    when {
                        // 上滑忽略
                        dy < -dp(24) -> dismiss()
                        // 下拉或点击直接开小窗
                        dy > dp(24) || (abs(dx) < dp(16) && abs(dy) < dp(16)) -> {
                            dismiss()
                            onOpenInFreeform(packageName)
                        }

                        else -> handler.postDelayed(autoDismissRunnable, 3000L)
                    }
                    true
                }

                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)
            horizontalMargin = 0.04f
        }

        try {
            windowManager.addView(container, params)
            currentView = container
            handler.postDelayed(autoDismissRunnable, 4500L)
        } catch (_: Exception) {
            currentView = null
        }
    }

    fun dismiss() {
        handler.removeCallbacks(autoDismissRunnable)
        val v = currentView ?: return
        currentView = null
        try {
            windowManager.removeView(v)
        } catch (_: Exception) {
        }
    }
}
