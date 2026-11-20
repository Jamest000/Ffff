package com.aospa.flymefreeform.notification

import android.app.KeyguardManager
import android.app.Notification
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aospa.flymefreeform.App
import com.aospa.flymefreeform.overlay.FreeformGestureService

/**
 * 监听白名单应用的新通知，触发顶部悬浮横幅实现“通知直开小窗”（免 LSPosed）
 */
class FreeformNotificationListener : NotificationListenerService() {

    private var lastKey: String = ""
    private var lastPostTime: Long = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val settings = App.instance.settings.settings.value
        if (!settings.notificationBannerEnabled) return
        if (!Settings.canDrawOverlays(this)) return

        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if (pkg !in settings.notificationPackages) return

        // 过滤常驻通知与分组摘要通知
        val notification = sbn.notification ?: return
        if (sbn.isOngoing) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        // 锁屏时不弹窗保护隐私
        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) return

        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        // 简单去重（1.2秒内相同通知不重复弹窗）
        val now = System.currentTimeMillis()
        val key = "$pkg|$title|$text"
        if (key == lastKey && now - lastPostTime < 1200L) return
        lastKey = key
        lastPostTime = now

        val intent = Intent(this, FreeformGestureService::class.java).apply {
            action = FreeformGestureService.ACTION_SHOW_BANNER
            putExtra(FreeformGestureService.EXTRA_PACKAGE_NAME, pkg)
            putExtra(FreeformGestureService.EXTRA_TITLE, title)
            putExtra(FreeformGestureService.EXTRA_TEXT, text)
        }
        try {
            startForegroundService(intent)
        } catch (_: Exception) {
        }
    }
}
