package com.aospa.flymefreeform.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.aospa.flymefreeform.xposed.SystemUiHookEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FreeformSettings(
    // === LSPosed 原生气泡小窗魔改 ===
    val xpBubbleNoScrim: Boolean = true,
    val xpBubbleTouchOutside: Boolean = true,
    val xpBubbleHeightScale: Float = 0.78f,
    // === LSPosed 状态栏 & 通知栏样式 + 粗字体防裁切 ===
    val xpClockStyle: Int = 2,          // 0=默认, 1=带秒, 2=星期+时间, 3=日期+星期, 4=时段+12h
    val xpClockBold: Boolean = true,
    val xpClockScale: Float = 1.0f,     // 0.80f..1.15f 防止粗体被裁切
    val xpFixBoldClipping: Boolean = true,
    val xpNotifCardStyle: Int = 2,      // 0=默认, 1=直角8dp, 2=Flyme柔和24dp, 3=胶囊玻璃34dp
    // === 自定义控制中心（复刻截图同款 + 可自由设计） ===
    val xpNativeQsEnabled: Boolean = false,
    val customControlCenterEnabled: Boolean = false,
    val ccFullTopBarTakeover: Boolean = false,
    val ccThemeMode: Int = 0,           // 0=浅色磨砂(截图同款), 1=深色暗黑, 2=跟随系统
    val ccBlurRadius: Int = 64,         // 0..100 系统原生高斯模糊强度
    val ccCardRadiusDp: Int = 26,       // 8..34 卡片圆角
    val ccCardAlpha: Float = 0.86f,     // 0.55f..1.0f 卡片透明度
    val ccShowMusicCard: Boolean = true,
    val ccShowVerticalSliders: Boolean = true,
    val ccShowQuickToggles: Boolean = true,
    val ccShowNotifications: Boolean = true,
    // === 弹幕通知与边缘手势 ===
    val danmakuNotificationEnabled: Boolean = true,
    val autoCopyOtpEnabled: Boolean = true,
    val notificationBannerEnabled: Boolean = false,
    val cornerLeftLaunchEnabled: Boolean = true,
    val sidebarLaunchEnabled: Boolean = true,
    val edgeDismissGestureEnabled: Boolean = false,
    val edgeDismissOnLeft: Boolean = true,
    val gestureSensitivityDp: Int = 38,
    val showHotzoneDebug: Boolean = false,
    val quickLaunchPackages: Set<String> = DEFAULT_QUICK_APPS,
    val notificationPackages: Set<String> = DEFAULT_NOTIFY_APPS
) {
    companion object {
        val DEFAULT_QUICK_APPS = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "org.telegram.messenger",
            "com.android.chrome",
            "com.eg.android.AlipayGphone",
            "com.xingin.xhs",
            "tv.danmaku.bili"
        )

        val DEFAULT_NOTIFY_APPS = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "org.telegram.messenger",
            "com.alibaba.android.rimet",
            "com.ss.android.lark",
            "com.google.android.apps.messaging",
            "com.android.messaging"
        )
    }
}

class SettingsRepository(private val context: Context) {

    @SuppressLint("WorldReadableFiles")
    private val prefs: SharedPreferences = try {
        @Suppress("DEPRECATION")
        context.getSharedPreferences(SystemUiHookEntry.PREFS_NAME, Context.MODE_WORLD_READABLE)
    } catch (_: SecurityException) {
        context.getSharedPreferences(SystemUiHookEntry.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<FreeformSettings> = _settings.asStateFlow()

    private fun load(): FreeformSettings {
        return FreeformSettings(
            xpBubbleNoScrim = prefs.getBoolean("xp_bubble_no_scrim", true),
            xpBubbleTouchOutside = prefs.getBoolean("xp_bubble_touch_outside", true),
            xpBubbleHeightScale = prefs.getFloat("xp_bubble_height_scale", 0.78f),
            xpClockStyle = prefs.getInt("xp_clock_style", 2),
            xpClockBold = prefs.getBoolean("xp_clock_bold", true),
            xpClockScale = prefs.getFloat("xp_clock_scale", 1.0f),
            xpFixBoldClipping = prefs.getBoolean("xp_fix_bold_clipping", true),
            xpNotifCardStyle = prefs.getInt("xp_notif_card_style", 2),
            xpNativeQsEnabled = prefs.getBoolean("xp_native_qs_v45", false),
            customControlCenterEnabled = prefs.getBoolean("cc_enabled_v4", false),
            ccFullTopBarTakeover = prefs.getBoolean("cc_full_top_bar", false),
            ccThemeMode = prefs.getInt("cc_theme_mode", 0),
            ccBlurRadius = prefs.getInt("cc_blur_radius", 64),
            ccCardRadiusDp = prefs.getInt("cc_card_radius_dp", 26),
            ccCardAlpha = prefs.getFloat("cc_card_alpha", 0.86f),
            ccShowMusicCard = prefs.getBoolean("cc_show_music", true),
            ccShowVerticalSliders = prefs.getBoolean("cc_show_sliders", true),
            ccShowQuickToggles = prefs.getBoolean("cc_show_toggles", true),
            ccShowNotifications = prefs.getBoolean("cc_show_notifs", true),
            danmakuNotificationEnabled = prefs.getBoolean("danmaku_notify", true),
            autoCopyOtpEnabled = prefs.getBoolean("auto_copy_otp", true),
            notificationBannerEnabled = prefs.getBoolean("notify_banner_v2", false),
            cornerLeftLaunchEnabled = prefs.getBoolean("corner_left_launch", true),
            sidebarLaunchEnabled = prefs.getBoolean("sidebar_launch", true),
            edgeDismissGestureEnabled = prefs.getBoolean("edge_dismiss_v41", false),
            edgeDismissOnLeft = prefs.getBoolean("edge_dismiss_left", true),
            gestureSensitivityDp = prefs.getInt("gesture_sensitivity_dp", 38),
            showHotzoneDebug = prefs.getBoolean("show_hotzone_debug", false),
            quickLaunchPackages = prefs.getStringSet("quick_apps", null)?.toSet() ?: FreeformSettings.DEFAULT_QUICK_APPS,
            notificationPackages = prefs.getStringSet("notify_apps", null)?.toSet()
                ?: FreeformSettings.DEFAULT_NOTIFY_APPS
        )
    }

    fun update(transform: (FreeformSettings) -> FreeformSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().apply {
            putBoolean("xp_bubble_no_scrim", next.xpBubbleNoScrim)
            putBoolean("xp_bubble_touch_outside", next.xpBubbleTouchOutside)
            putFloat("xp_bubble_height_scale", next.xpBubbleHeightScale)
            putInt("xp_clock_style", next.xpClockStyle)
            putBoolean("xp_clock_bold", next.xpClockBold)
            putFloat("xp_clock_scale", next.xpClockScale)
            putBoolean("xp_fix_bold_clipping", next.xpFixBoldClipping)
            putInt("xp_notif_card_style", next.xpNotifCardStyle)
            putBoolean("xp_native_qs_v45", next.xpNativeQsEnabled)
            putBoolean("cc_enabled_v4", next.customControlCenterEnabled)
            putBoolean("cc_full_top_bar", next.ccFullTopBarTakeover)
            putInt("cc_theme_mode", next.ccThemeMode)
            putInt("cc_blur_radius", next.ccBlurRadius)
            putInt("cc_card_radius_dp", next.ccCardRadiusDp)
            putFloat("cc_card_alpha", next.ccCardAlpha)
            putBoolean("cc_show_music", next.ccShowMusicCard)
            putBoolean("cc_show_sliders", next.ccShowVerticalSliders)
            putBoolean("cc_show_toggles", next.ccShowQuickToggles)
            putBoolean("cc_show_notifs", next.ccShowNotifications)
            putBoolean("danmaku_notify", next.danmakuNotificationEnabled)
            putBoolean("auto_copy_otp", next.autoCopyOtpEnabled)
            putBoolean("notify_banner_v2", next.notificationBannerEnabled)
            putBoolean("corner_left_launch", next.cornerLeftLaunchEnabled)
            putBoolean("sidebar_launch", next.sidebarLaunchEnabled)
            putBoolean("edge_dismiss_v41", next.edgeDismissGestureEnabled)
            putBoolean("edge_dismiss_left", next.edgeDismissOnLeft)
            putInt("gesture_sensitivity_dp", next.gestureSensitivityDp)
            putBoolean("show_hotzone_debug", next.showHotzoneDebug)
            putStringSet("quick_apps", next.quickLaunchPackages)
            putStringSet("notify_apps", next.notificationPackages)
            apply()
        }
        try {
            context.sendBroadcast(Intent(SystemUiHookEntry.ACTION_RELOAD_CONFIG))
        } catch (_: Exception) {
        }
    }
}
