package com.aospa.flymefreeform.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Flyme 风格弹幕通知：全圆角半透明胶囊从屏幕右侧平滑飘向左侧，
 * 支持 3 条轨道防重叠，点击弹幕可直接以小窗打开对应应用。
 */
class DanmakuNotificationView(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onClickOpenFreeform: (String) -> Unit
) {
    private val activeViews = mutableSetOf<View>()
    private var nextLane = 0

    fun show(
        packageName: String,
        appName: String,
        appIcon: Drawable?,
        title: String,
        text: String
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val screenWidth = windowManager.currentWindowMetrics.bounds.width().coerceAtLeast(1080)
        val lane = nextLane % 3
        nextLane++
        val laneY = dp(86 + lane * 44)

        val pillBg = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(Color.parseColor("#DD1B1F27"))
            setStroke(dp(1), Color.parseColor("#44FFFFFF"))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg
            setPadding(dp(10), dp(6), dp(14), dp(6))
            elevation = dp(6).toFloat()
        }

        if (appIcon != null) {
            val icon = ImageView(context).apply {
                setImageDrawable(appIcon)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginEnd = dp(8)
                }
            }
            container.addView(icon)
        }

        val displayMsg = buildString {
            if (title.isNotBlank()) {
                append(title)
                if (text.isNotBlank()) append("：")
            } else {
                append(appName)
                if (text.isNotBlank()) append("：")
            }
            append(text.ifBlank { "新消息" })
        }

        val label = TextView(context).apply {
            this.text = displayMsg
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            maxWidth = (screenWidth * 0.78f).toInt()
            ellipsize = TextUtils.TruncateAt.END
        }
        container.addView(label)

        // 先测量实际宽度以精准设置进出场坐标
        container.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dp(48), View.MeasureSpec.AT_MOST)
        )
        val viewWidth = container.measuredWidth.coerceAtLeast(dp(140))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth
            y = laneY
        }

        try {
            windowManager.addView(container, params)
            activeViews.add(container)
        } catch (_: Exception) {
            return
        }

        val animator = ValueAnimator.ofInt(screenWidth, -viewWidth - dp(24)).apply {
            duration = 6200L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                if (!activeViews.contains(container)) return@addUpdateListener
                params.x = anim.animatedValue as Int
                try {
                    windowManager.updateViewLayout(container, params)
                } catch (_: Exception) {
                    cancel()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    removeViewSafe(container)
                }
            })
        }

        container.setOnClickListener {
            animator.cancel()
            removeViewSafe(container)
            onClickOpenFreeform(packageName)
        }

        animator.start()
    }

    fun clearAll() {
        activeViews.toList().forEach { removeViewSafe(it) }
    }

    private fun removeViewSafe(view: View) {
        if (activeViews.remove(view)) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
    }
}
