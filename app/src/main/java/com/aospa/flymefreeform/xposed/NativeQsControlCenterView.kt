package com.aospa.flymefreeform.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NativeCcStyleConfig(
    val themeMode: Int = 0,       // 0=浅色磨砂, 1=深色暗黑, 2=跟随系统
    val cardRadiusDp: Int = 26,
    val cardAlpha: Float = 0.88f,
    val showMusicCard: Boolean = true,
    val showVerticalSliders: Boolean = true,
    val showQuickToggles: Boolean = true
)

/**
 * 直接注入到 AOSPA 17 SystemUI mQsFrame 内部的原生丝滑控制中心视图（v4.3）：
 * - 实时读取系统 MediaSession 显示真实歌名、歌手、专辑封面与播放状态
 * - 100% 复用系统原生 120Hz 下拉动画、系统高斯模糊和下方原生通知列表
 */
@SuppressLint("ViewConstructor")
class NativeQsControlCenterView(
    context: Context,
    initialConfig: NativeCcStyleConfig
) : LinearLayout(context) {

    private var config: NativeCcStyleConfig = initialConfig
    private var torchOn = false
    private var dateLabel: TextView? = null
    private var trackTitleView: TextView? = null
    private var trackArtistView: TextView? = null
    private var albumArtView: ImageView? = null
    private var albumFallbackBadge: TextView? = null
    private var playPauseBtn: TextView? = null

    init {
        orientation = VERTICAL
        isClickable = true
        rebuild(initialConfig)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    fun refreshDynamicInfo() {
        dateLabel?.text = SimpleDateFormat("M月d日E", Locale.CHINA).format(Date())
        refreshMediaInfo()
    }

    private fun refreshMediaInfo() {
        val titleView = trackTitleView ?: return
        val artistView = trackArtistView ?: return
        try {
            val msm = context.getSystemService(MediaSessionManager::class.java)
            val controllers = msm?.getActiveSessions(null).orEmpty()
            val active = controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers.firstOrNull()

            if (active != null) {
                val meta = active.metadata
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                val art: Bitmap? = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                val isPlaying = active.playbackState?.state == PlaybackState.STATE_PLAYING

                titleView.text = title.ifBlank { "正在播放媒体" }
                artistView.text = artist.ifBlank { active.packageName }
                playPauseBtn?.text = if (isPlaying) "⏸" else "▶"

                if (art != null) {
                    albumArtView?.setImageBitmap(art)
                    albumArtView?.visibility = View.VISIBLE
                    albumFallbackBadge?.visibility = View.GONE
                } else {
                    albumArtView?.visibility = View.GONE
                    albumFallbackBadge?.visibility = View.VISIBLE
                }
            } else {
                titleView.text = "暂无播放"
                artistView.text = "打开音乐后自动显示封面"
                playPauseBtn?.text = "⏯"
                albumArtView?.visibility = View.GONE
                albumFallbackBadge?.visibility = View.VISIBLE
            }
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun rebuild(newConfig: NativeCcStyleConfig) {
        config = newConfig
        removeAllViews()

        setPadding(dp(16), dp(8), dp(16), dp(12))

        val isDark = when (config.themeMode) {
            1 -> true
            2 -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            else -> false
        }

        val alphaByte = (config.cardAlpha.coerceIn(0.55f, 1.0f) * 255).toInt()
        val cardColor = if (isDark) {
            Color.argb(alphaByte, 32, 35, 42)
        } else {
            Color.argb(alphaByte, 245, 246, 249)
        }
        val textPrimary = if (isDark) Color.WHITE else Color.parseColor("#181A1F")
        val textSecondary = if (isDark) Color.parseColor("#B0B7C3") else Color.parseColor("#5C626E")
        val sliderActiveColor = if (isDark) Color.WHITE else Color.parseColor("#111317")
        val sliderTrackColor = if (isDark) Color.argb(160, 58, 62, 72) else Color.argb(225, 236, 238, 242)
        val radiusPx = dp(config.cardRadiusDp.coerceIn(8, 34)).toFloat()

        fun makeBg(r: Float = radiusPx, color: Int = cardColor): GradientDrawable {
            return GradientDrawable().apply {
                cornerRadius = r
                setColor(color)
            }
        }

        // 1. 顶部栏：日期 + 设置/微信录音圆按钮
        val topBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(12))
        }

        val dateView = TextView(context).apply {
            text = SimpleDateFormat("M月d日E", Locale.CHINA).format(Date())
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        dateLabel = dateView
        topBar.addView(dateView)

        fun circleBtn(symbol: String, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = symbol
                setTextColor(textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                background = makeBg(dp(999).toFloat())
                layoutParams = LayoutParams(dp(38), dp(38)).apply { marginStart = dp(10) }
                setOnClickListener { onClick() }
            }
        }

        topBar.addView(circleBtn("⚙") {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
            }
        })
        topBar.addView(circleBtn("🎙") {
            try {
                context.sendBroadcast(Intent(SystemUiHookEntry.ACTION_TOGGLE_WECHAT_REC))
            } catch (_: Throwable) {
            }
        })
        addView(topBar)

        // 2. 左侧 2x2 实时音乐卡 + 右侧双竖向粗条滑块（音量 & 亮度）
        if (config.showMusicCard || config.showVerticalSliders) {
            val heroRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(164)).apply {
                    bottomMargin = dp(12)
                }
            }

            if (config.showMusicCard) {
                val musicCard = LinearLayout(context).apply {
                    orientation = VERTICAL
                    background = makeBg()
                    setPadding(dp(14), dp(14), dp(14), dp(10))
                    layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1.15f).apply {
                        if (config.showVerticalSliders) marginEnd = dp(12)
                    }
                }

                // 封面容器（支持真实圆角专辑封面 + 默认音符占位）
                val coverFrame = FrameLayout(context).apply {
                    layoutParams = LayoutParams(dp(46), dp(46))
                }
                val artImg = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    visibility = View.GONE
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                        }
                    }
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                val albumBadge = TextView(context).apply {
                    text = "♪"
                    setTextColor(if (isDark) Color.BLACK else Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    gravity = Gravity.CENTER
                    background = makeBg(dp(12).toFloat(), sliderActiveColor)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                coverFrame.addView(albumBadge)
                coverFrame.addView(artImg)
                albumArtView = artImg
                albumFallbackBadge = albumBadge
                musicCard.addView(coverFrame)

                val titleTv = TextView(context).apply {
                    text = "媒体控制"
                    setTextColor(textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(10), 0, dp(2))
                }
                val artistTv = TextView(context).apply {
                    text = "切歌 / 播放暂停"
                    setTextColor(textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                trackTitleView = titleTv
                trackArtistView = artistTv
                musicCard.addView(titleTv)
                musicCard.addView(artistTv)

                val controls = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
                }
                val am = context.getSystemService(AudioManager::class.java)
                fun mediaKey(code: Int) {
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    postDelayed({ refreshMediaInfo() }, 240L)
                }

                val prevBtn = TextView(context).apply {
                    text = "⏮"
                    setTextColor(textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    gravity = Gravity.CENTER
                    layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                    setOnClickListener { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
                }
                val ppBtn = TextView(context).apply {
                    text = "⏯"
                    setTextColor(textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    gravity = Gravity.CENTER
                    layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                    setOnClickListener { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
                }
                val nextBtn = TextView(context).apply {
                    text = "⏭"
                    setTextColor(textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    gravity = Gravity.CENTER
                    layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                    setOnClickListener { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
                }
                playPauseBtn = ppBtn
                controls.addView(prevBtn)
                controls.addView(ppBtn)
                controls.addView(nextBtn)
                musicCard.addView(controls)
                heroRow.addView(musicCard)
            }

            if (config.showVerticalSliders) {
                val am = context.getSystemService(AudioManager::class.java)
                val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
                val curVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 7
                val curBright = try {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                } catch (_: Throwable) {
                    128
                }

                fun verticalSlider(
                    iconSym: String,
                    initRatio: Float,
                    endMargin: Int,
                    onChange: (Float) -> Unit
                ): FrameLayout {
                    var ratio = initRatio.coerceIn(0.05f, 1f)
                    val track = FrameLayout(context).apply {
                        background = makeBg(radiusPx, sliderTrackColor)
                        clipToOutline = true
                        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.55f).apply {
                            marginEnd = endMargin
                        }
                    }

                    val fill = View(context).apply {
                        background = makeBg(radiusPx * 0.7f, sliderActiveColor)
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            dp((164 * ratio).toInt())
                        ).apply { gravity = Gravity.BOTTOM }
                    }
                    track.addView(fill)

                    track.addView(TextView(context).apply {
                        text = iconSym
                        setTextColor(if (isDark) Color.BLACK else Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            dp(44)
                        ).apply { gravity = Gravity.BOTTOM }
                    })

                    track.setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                val h = v.height.coerceAtLeast(1)
                                ratio = (1f - (event.y / h)).coerceIn(0.04f, 1f)
                                val lp = fill.layoutParams as FrameLayout.LayoutParams
                                lp.height = (h * ratio).toInt().coerceAtLeast(dp(22))
                                fill.layoutParams = lp
                                onChange(ratio)
                                true
                            }

                            else -> false
                        }
                    }
                    return track
                }

                heroRow.addView(
                    verticalSlider("♪", curVol.toFloat() / maxVol, dp(10)) { r ->
                        val v = (r * maxVol).toInt().coerceIn(0, maxVol)
                        am?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    }
                )
                heroRow.addView(
                    verticalSlider("☀", curBright / 255f, 0) { r ->
                        val b = (r * 255).toInt().coerceIn(8, 255)
                        try {
                            Settings.System.putInt(
                                context.contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS,
                                b
                            )
                        } catch (_: Throwable) {
                        }
                    }
                )
            }

            addView(heroRow)
        }

        // 3. 大圆角胶囊快捷开关（WLAN / 蓝牙 / 手电筒）
        if (config.showQuickToggles) {
            fun pill(title: String, sym: String, active: Boolean = false, onClick: () -> Unit): LinearLayout {
                val bg = if (active) Color.parseColor("#1A73E8") else cardColor
                val fg = if (active) Color.WHITE else textPrimary
                return LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = makeBg(dp(999).toFloat(), bg)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    layoutParams = LayoutParams(0, dp(52), 1f)
                    setOnClickListener { onClick() }

                    addView(TextView(context).apply {
                        text = sym
                        setTextColor(fg)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        gravity = Gravity.CENTER
                        layoutParams = LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) }
                    })
                    addView(TextView(context).apply {
                        text = title
                        setTextColor(fg)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                    })
                }
            }

            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            val wifi = pill("WLAN", "📶") {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) {
                }
            }
            (wifi.layoutParams as LayoutParams).marginEnd = dp(10)

            val bt = pill("蓝牙", "ᛒ") {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) {
                }
            }
            (bt.layoutParams as LayoutParams).marginEnd = dp(10)

            val torch = pill(if (torchOn) "电筒开" else "手电筒", "🔦", torchOn) {
                try {
                    val cm = context.getSystemService(CameraManager::class.java)
                    val id = cm.cameraIdList.firstOrNull()
                    if (id != null) {
                        torchOn = !torchOn
                        cm.setTorchMode(id, torchOn)
                        rebuild(config)
                    }
                } catch (_: Throwable) {
                }
            }

            row.addView(wifi)
            row.addView(bt)
            row.addView(torch)
            addView(row)
        }

        // 4. 底部极简分隔短横条
        addView(View(context).apply {
            background = makeBg(dp(4).toFloat(), textSecondary)
            alpha = 0.45f
            layoutParams = LayoutParams(dp(44), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
            }
        })

        refreshMediaInfo()
    }
}
