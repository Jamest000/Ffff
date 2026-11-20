package com.aospa.flymefreeform.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aospa.flymefreeform.core.AppEntry

/**
 * Flyme 风格快捷小窗启动面板（点击应用直接以小窗启动，底部附带一键关闭当前小窗按钮）
 */
class QuickLaunchPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onSelectApp: (String) -> Unit,
    private val onDismissCurrentFreeform: () -> Unit
) {
    private var rootView: View? = null

    fun isShowing(): Boolean = rootView != null

    fun show(apps: List<AppEntry>) {
        if (rootView != null) dismiss()

        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val backdrop = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#66000000"))
            setPadding(dp(24), dp(48), dp(24), dp(48))
            setOnClickListener { dismiss() }
        }

        val cardBg = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#F51C1F26"))
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(dp(18), dp(18), dp(18), dp(16))
            isClickable = true
        }

        // 标题栏
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(12))
        }

        val title = TextView(context).apply {
            text = "小窗快捷启动"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtnBg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#33FFFFFF"))
        }
        val closeBtn = TextView(context).apply {
            text = "关闭面板"
            setTextColor(Color.parseColor("#DCE4F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = closeBtnBg
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { dismiss() }
        }

        header.addView(title)
        header.addView(closeBtn)
        card.addView(header)

        // 应用网格
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(300)
            )
        }

        val grid = GridLayout(context).apply {
            columnCount = 4
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }

        if (apps.isEmpty()) {
            val emptyText = TextView(context).apply {
                text = "尚未在设置中添加快捷小窗应用"
                setTextColor(Color.parseColor("#AAB4C4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(48), dp(16), dp(48))
            }
            scroll.addView(emptyText)
        } else {
            apps.forEach { app ->
                val item = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    }
                    setOnClickListener {
                        dismiss()
                        onSelectApp(app.packageName)
                    }
                }

                val icon = ImageView(context).apply {
                    setImageDrawable(app.icon)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                }

                val label = TextView(context).apply {
                    text = app.label
                    setTextColor(Color.parseColor("#EEF2F8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                }

                item.addView(icon)
                item.addView(label)
                grid.addView(item)
            }
            scroll.addView(grid)
        }

        card.addView(scroll)

        // 底部快速取消当前小窗操作条
        val dismissPillBg = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#33EF5350"))
            setStroke(dp(1), Color.parseColor("#66EF5350"))
        }
        val dismissPill = TextView(context).apply {
            text = "一键取消当前小窗"
            setTextColor(Color.parseColor("#FF8A80"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = dismissPillBg
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            setOnClickListener {
                dismiss()
                onDismissCurrentFreeform()
            }
        }
        card.addView(dismissPill)

        backdrop.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(backdrop, params)
            rootView = backdrop
        } catch (_: Exception) {
            rootView = null
        }
    }

    fun dismiss() {
        val v = rootView ?: return
        rootView = null
        try {
            windowManager.removeView(v)
        } catch (_: Exception) {
        }
    }
}
