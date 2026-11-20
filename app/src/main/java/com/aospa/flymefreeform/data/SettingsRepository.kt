package com.aospa.flymefreeform.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FreeformSettings(
    val serviceEnabled: Boolean = true,
    // 呼出小窗手势（默认全开）
    val cornerLeftLaunchEnabled: Boolean = true,
    val cornerRightLaunchEnabled: Boolean = true,
    val sidebarLaunchEnabled: Boolean = true,
    val sidebarOnRight: Boolean = true,
    // 取消/关闭小窗手势（默认全开）
    val topBarDismissSwipeUpEnabled: Boolean = true,
    val topBarDismissDoubleTapEnabled: Boolean = true,
    val topBarExpandSwipeDownEnabled: Boolean = true,
    val edgeDismissGestureEnabled: Boolean = true,
    val edgeDismissOnLeft: Boolean = true,
    // 悬浮通知直开小窗（默认开启）
    val notificationBannerEnabled: Boolean = true,
    // 尺寸与手感参数（针对小米13 1080x2400 屏幕优化默认值）
    val windowWidthRatio: Float = 0.80f,
    val windowHeightRatio: Float = 0.56f,
    val gestureSensitivityDp: Int = 56,
    val showHotzoneDebug: Boolean = false,
    // 应用白名单
    val quickLaunchPackages: Set<String> = DEFAULT_QUICK_APPS,
    val notificationPackages: Set<String> = DEFAULT_NOTIFY_APPS
) {
    companion object {
        val DEFAULT_QUICK_APPS = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "org.telegram.messenger",
            "com.android.chrome",
            "com.google.android.calculator",
            "com.android.calculator2",
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

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("flyme_freeform_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<FreeformSettings> = _settings.asStateFlow()

    private fun load(): FreeformSettings {
        return FreeformSettings(
            serviceEnabled = prefs.getBoolean("service_enabled", true),
            cornerLeftLaunchEnabled = prefs.getBoolean("corner_left_launch", true),
            cornerRightLaunchEnabled = prefs.getBoolean("corner_right_launch", true),
            sidebarLaunchEnabled = prefs.getBoolean("sidebar_launch", true),
            sidebarOnRight = prefs.getBoolean("sidebar_on_right", true),
            topBarDismissSwipeUpEnabled = prefs.getBoolean("top_bar_swipe_up", true),
            topBarDismissDoubleTapEnabled = prefs.getBoolean("top_bar_double_tap", true),
            topBarExpandSwipeDownEnabled = prefs.getBoolean("top_bar_swipe_down", true),
            edgeDismissGestureEnabled = prefs.getBoolean("edge_dismiss", true),
            edgeDismissOnLeft = prefs.getBoolean("edge_dismiss_left", true),
            notificationBannerEnabled = prefs.getBoolean("notify_banner", true),
            windowWidthRatio = prefs.getFloat("window_width_ratio", 0.80f),
            windowHeightRatio = prefs.getFloat("window_height_ratio", 0.56f),
            gestureSensitivityDp = prefs.getInt("gesture_sensitivity_dp", 56),
            showHotzoneDebug = prefs.getBoolean("show_hotzone_debug", false),
            quickLaunchPackages = prefs.getStringSet("quick_apps", null)?.toSet()
                ?: FreeformSettings.DEFAULT_QUICK_APPS,
            notificationPackages = prefs.getStringSet("notify_apps", null)?.toSet()
                ?: FreeformSettings.DEFAULT_NOTIFY_APPS
        )
    }

    fun update(transform: (FreeformSettings) -> FreeformSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().apply {
            putBoolean("service_enabled", next.serviceEnabled)
            putBoolean("corner_left_launch", next.cornerLeftLaunchEnabled)
            putBoolean("corner_right_launch", next.cornerRightLaunchEnabled)
            putBoolean("sidebar_launch", next.sidebarLaunchEnabled)
            putBoolean("sidebar_on_right", next.sidebarOnRight)
            putBoolean("top_bar_swipe_up", next.topBarDismissSwipeUpEnabled)
            putBoolean("top_bar_double_tap", next.topBarDismissDoubleTapEnabled)
            putBoolean("top_bar_swipe_down", next.topBarExpandSwipeDownEnabled)
            putBoolean("edge_dismiss", next.edgeDismissGestureEnabled)
            putBoolean("edge_dismiss_left", next.edgeDismissOnLeft)
            putBoolean("notify_banner", next.notificationBannerEnabled)
            putFloat("window_width_ratio", next.windowWidthRatio)
            putFloat("window_height_ratio", next.windowHeightRatio)
            putInt("gesture_sensitivity_dp", next.gestureSensitivityDp)
            putBoolean("show_hotzone_debug", next.showHotzoneDebug)
            putStringSet("quick_apps", next.quickLaunchPackages)
            putStringSet("notify_apps", next.notificationPackages)
            apply()
        }
    }
}
