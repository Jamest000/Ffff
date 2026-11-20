package com.aospa.flymefreeform.notification

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.app.Notification
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aospa.flymefreeform.App
import com.aospa.flymefreeform.overlay.FreeformGestureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveNotificationItem(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long
)

/**
 * 监听通知：
 * 1. 实时同步当前系统活跃通知给「自定义控制中心」
 * 2. 对白名单应用的新消息触发全圆角弹幕通知或悬浮横幅
 */
class FreeformNotificationListener : NotificationListenerService() {

    private var lastKey: String = ""
    private var lastPostTime: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshActiveList()
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshActiveList()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshActiveList()
        if (sbn == null) return
        val settings = App.instance.settings.settings.value
        val rawExtras = sbn.notification?.extras
        val rawTitle = rawExtras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val rawText = rawExtras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (settings.autoCopyOtpEnabled && !sbn.isOngoing && sbn.packageName != packageName) {
            tryExtractAndCopyOtp(rawTitle, rawText)
        }
        if (!settings.notificationBannerEnabled && !settings.danmakuNotificationEnabled) return
        if (!Settings.canDrawOverlays(this)) return

        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if (pkg !in settings.notificationPackages) return

        val notification = sbn.notification ?: return
        if (sbn.isOngoing) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) return

        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

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

    private fun tryExtractAndCopyOtp(title: String, text: String) {
        val combined = "$title $text"
        val hasKeyword = listOf("验证码", "校验码", "动态码", "确认码", "登录码", "verification code", "otp", "passcode")
            .any { combined.contains(it, ignoreCase = true) }
        if (!hasKeyword) return
        val code = Regex("(?<!\\d)(\\d{4,8})(?!\\d)").find(combined)?.groupValues?.getOrNull(1) ?: return
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("OTP", code))
            val toastIntent = Intent(this, FreeformGestureService::class.java).apply {
                action = FreeformGestureService.ACTION_FLASH_TOAST
                putExtra(FreeformGestureService.EXTRA_TEXT, "已自动复制验证码: $code")
            }
            startForegroundService(toastIntent)
        } catch (_: Exception) {
        }
    }

    private fun refreshActiveList() {
        try {
            val list = activeNotifications
                ?.filter { !it.isOngoing && it.packageName != packageName }
                ?.filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
                ?.mapNotNull { sbn ->
                    val extras = sbn.notification.extras
                    val t = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                    val b = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                    if (t.isBlank() && b.isBlank()) return@mapNotNull null
                    ActiveNotificationItem(
                        key = sbn.key,
                        packageName = sbn.packageName,
                        title = t,
                        text = b,
                        postTime = sbn.postTime
                    )
                }
                ?.sortedByDescending { it.postTime }
                ?: emptyList()
            _activeItems.value = list
        } catch (_: Exception) {
        }
    }

    companion object {
        @Volatile
        private var instance: FreeformNotificationListener? = null

        private val _activeItems = MutableStateFlow<List<ActiveNotificationItem>>(emptyList())
        val activeItems: StateFlow<List<ActiveNotificationItem>> = _activeItems.asStateFlow()

        fun clearAllNotifications() {
            try {
                instance?.cancelAllNotifications()
                _activeItems.value = emptyList()
            } catch (_: Exception) {
            }
        }
    }
}
