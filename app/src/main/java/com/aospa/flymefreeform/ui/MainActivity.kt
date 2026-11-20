package com.aospa.flymefreeform.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aospa.flymefreeform.App
import com.aospa.flymefreeform.core.AppCatalog
import com.aospa.flymefreeform.core.AppEntry
import com.aospa.flymefreeform.core.RootShell
import com.aospa.flymefreeform.core.WechatRecorder
import kotlinx.coroutines.launch
import com.aospa.flymefreeform.notification.FreeformNotificationListener
import com.aospa.flymefreeform.overlay.FreeformGestureService
import com.aospa.flymefreeform.xposed.SystemUiHookEntry
import com.aospa.flymefreeform.xposed.XposedStatus
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) notifPermLauncher.launch(needed.toTypedArray())
        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (darkTheme) {
                dynamicDarkColorScheme(this)
            } else {
                dynamicLightColorScheme(this)
            }
            MaterialTheme(colorScheme = colorScheme) {
                MainScreen()
            }
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?: return false
    val cn = ComponentName(context, FreeformNotificationListener::class.java).flattenToString()
    return flat.contains(cn)
}

private fun isPackageInstalled(context: Context, pkg: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = App.instance.settings
    val settings by repo.settings.collectAsState()

    val xposedActive = remember { XposedStatus.isModuleActive() }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifListenerOk by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var serviceRunning by remember { mutableStateOf(FreeformGestureService.instance != null) }
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var bcrInstalled by remember { mutableStateOf(isPackageInstalled(context, "com.chiller3.bcr")) }
    val isWechatRecording by WechatRecorder.isRecording.collectAsState()
    val lastRecPath by WechatRecorder.lastSavedPath.collectAsState()

    var installedApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var pickerMode by remember { mutableStateOf<String?>(null) }

    fun refreshStatus() {
        overlayOk = Settings.canDrawOverlays(context)
        notifListenerOk = isNotificationListenerEnabled(context)
        serviceRunning = FreeformGestureService.instance != null
        bcrInstalled = isPackageInstalled(context, "com.chiller3.bcr")
        scope.launch { rootOk = RootShell.isRootAvailable() }
    }

    LaunchedEffect(Unit) {
        refreshStatus()
        installedApps = AppCatalog.loadLaunchableApps(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Flyme小窗 · AOSPA 17", fontWeight = FontWeight.SemiBold)
                        Text(
                            "气泡魔改 · 自定义控制中心 · 弹幕 · 粗体防裁切",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 模块与权限状态
            SectionCard(title = "模块与权限状态") {
                StatusRow("Root 权限", rootOk?.let { if (it) "已获取" else "未获取（请在 Magisk/KSU 授权）" } ?: "检测中...")
                StatusRow("LSPosed 模块激活", if (xposedActive) "已激活" else "未激活（请在 LSPosed 勾选系统界面并重启）")
                StatusRow("悬浮窗权限（弹幕/手势）", if (overlayOk) "已授权" else "未授权")
                StatusRow("通知监听权限（弹幕通知）", if (notifListenerOk) "已授权" else "未授权")
                StatusRow("弹幕与手势服务", if (serviceRunning) "运行中" else "未启动")

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (Settings.canDrawOverlays(context)) {
                                FreeformGestureService.start(context)
                                serviceRunning = true
                                Toast.makeText(context, "弹幕与手势服务已启动", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                )
                            }
                        }
                    ) {
                        Text("启动弹幕与手势服务")
                    }
                    OutlinedButton(
                        onClick = {
                            if (serviceRunning) {
                                FreeformGestureService.stop(context)
                                serviceRunning = false
                            } else {
                                refreshStatus()
                            }
                        }
                    ) {
                        Text(if (serviceRunning) "停止" else "刷新")
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    ) {
                        Text("授权悬浮窗")
                    }
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    ) {
                        Text("授权通知监听")
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            val res = RootShell.exec("pidof com.android.systemui | xargs kill -9 || am crash com.android.systemui")
                            if (!res.isSuccess) {
                                Toast.makeText(context, "重载失败，请先在 Root 管理器给本应用授权", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("一键重载 SystemUI（调完样式 2 秒生效，免重启手机）")
                }
            }

            // 2. 原生气泡小窗魔改（LSPosed Hook SystemUI）
            SectionCard(title = "Android 17 原生气泡魔改（LSPosed）") {
                ToggleRow(
                    title = "去掉气泡展开时的背景变暗遮罩",
                    subtitle = "展开气泡时底层页面保持原亮度，视觉更通透",
                    checked = settings.xpBubbleNoScrim
                ) { v -> repo.update { it.copy(xpBubbleNoScrim = v) } }

                ToggleRow(
                    title = "允许同时操作底层应用（取消点外收起）",
                    subtitle = "点击气泡外部空白处不再强制收起，可边看气泡边操作底层 App",
                    checked = settings.xpBubbleTouchOutside
                ) { v -> repo.update { it.copy(xpBubbleTouchOutside = v) } }

                Spacer(Modifier.height(4.dp))
                Text("气泡展开高度比例：${(settings.xpBubbleHeightScale * 100).roundToInt()}%")
                Slider(
                    value = settings.xpBubbleHeightScale,
                    onValueChange = { v -> repo.update { it.copy(xpBubbleHeightScale = v) } },
                    valueRange = 0.50f..1.00f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (!xposedActive) {
                                Toast.makeText(context, "LSPosed 尚未激活，请勾选系统界面并重启", Toast.LENGTH_SHORT).show()
                            } else {
                                context.sendBroadcast(Intent(SystemUiHookEntry.ACTION_BUBBLE_COLLAPSE))
                                Toast.makeText(context, "已发送收起气泡指令", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("测试收起气泡")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (!xposedActive) {
                                Toast.makeText(context, "LSPosed 尚未激活，请勾选系统界面并重启", Toast.LENGTH_SHORT).show()
                            } else {
                                context.sendBroadcast(Intent(SystemUiHookEntry.ACTION_BUBBLE_DISMISS))
                                Toast.makeText(context, "已发送关闭气泡指令", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("测试关闭气泡")
                    }
                }
            }

            // 3. 状态栏与通知栏多种样式（LSPosed Hook SystemUI）
            SectionCard(title = "状态栏与通知栏样式美化（LSPosed）") {
                Text(
                    "状态栏时钟样式",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                val clockOptions = listOf(
                    0 to "系统默认",
                    1 to "带秒数",
                    2 to "星期+时间",
                    3 to "日期+星期",
                    4 to "中文时段"
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    clockOptions.forEach { (id, label) ->
                        FilterChip(
                            selected = settings.xpClockStyle == id,
                            onClick = { repo.update { it.copy(xpClockStyle = id) } },
                            label = { Text(label) }
                        )
                    }
                }

                ToggleRow(
                    title = "状态栏时钟加粗",
                    subtitle = "让左上角时间更醒目清晰",
                    checked = settings.xpClockBold
                ) { v -> repo.update { it.copy(xpClockBold = v) } }

                ToggleRow(
                    title = "粗字体防显示不全保护",
                    subtitle = "放开状态栏宽度限制并去除多余内边距，避免加粗或换字体后截断",
                    checked = settings.xpFixBoldClipping
                ) { v -> repo.update { it.copy(xpFixBoldClipping = v) } }

                Text("状态栏时钟字号微调：${(settings.xpClockScale * 100).roundToInt()}%")
                Slider(
                    value = settings.xpClockScale,
                    onValueChange = { v -> repo.update { it.copy(xpClockScale = v) } },
                    valueRange = 0.80f..1.15f
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    "下拉通知卡片风格（修改后下次展开通知栏生效）",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                val cardOptions = listOf(
                    0 to "系统默认",
                    1 to "直角硬朗 8dp",
                    2 to "Flyme 柔和 24dp",
                    3 to "超大胶囊玻璃 34dp"
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    cardOptions.forEach { (id, label) ->
                        FilterChip(
                            selected = settings.xpNotifCardStyle == id,
                            onClick = { repo.update { it.copy(xpNotifCardStyle = id) } },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // 3.5 自定义控制中心设计器（截图同款 + 自己设计）
            SectionCard(title = "自定义控制中心设计器（右上角下拉呼出）") {
                ToggleRow(
                    title = "独立悬浮控制中心（右上角逐帧跟手下拉）",
                    subtitle = "不改动系统原生通知栏，避免悬浮通知冲突；关闭则完全使用系统原生控制中心",
                    subtitle = "手指下滑实时跟手展开 + 实时渐变模糊，底部上滑同样跟手收起",
                    checked = settings.customControlCenterEnabled
                ) { v -> repo.update { it.copy(customControlCenterEnabled = v) } }

                ToggleRow(
                    title = "接管整条顶部状态栏（替代原生下拉入口）",
                    subtitle = "开启后顶部任意位置下滑都拉出你的控制中心；关闭则仅右上角触发",
                    checked = settings.ccFullTopBarTakeover
                ) { v -> repo.update { it.copy(ccFullTopBarTakeover = v) } }

                Text("面板主题风格", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                val themeOpts = listOf(0 to "浅色磨砂（截图同款）", 1 to "深色暗黑", 2 to "跟随系统")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    themeOpts.forEach { (id, label) ->
                        FilterChip(
                            selected = settings.ccThemeMode == id,
                            onClick = { repo.update { it.copy(ccThemeMode = id) } },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text("背景原生高斯模糊强度：${settings.ccBlurRadius}")
                Slider(
                    value = settings.ccBlurRadius.toFloat(),
                    onValueChange = { v -> repo.update { it.copy(ccBlurRadius = v.roundToInt()) } },
                    valueRange = 0f..100f
                )

                Text("卡片圆角大小：${settings.ccCardRadiusDp} dp")
                Slider(
                    value = settings.ccCardRadiusDp.toFloat(),
                    onValueChange = { v -> repo.update { it.copy(ccCardRadiusDp = v.roundToInt()) } },
                    valueRange = 8f..34f
                )

                Text("卡片不透明度：${(settings.ccCardAlpha * 100).roundToInt()}%")
                Slider(
                    value = settings.ccCardAlpha,
                    onValueChange = { v -> repo.update { it.copy(ccCardAlpha = v) } },
                    valueRange = 0.55f..1.0f
                )

                ToggleRow("显示左侧音乐卡片", "2x2 媒体播放与切歌控制", settings.ccShowMusicCard) { v ->
                    repo.update { it.copy(ccShowMusicCard = v) }
                }
                ToggleRow("显示右侧双竖条滑块", "竖向粗条音量与亮度滑块", settings.ccShowVerticalSliders) { v ->
                    repo.update { it.copy(ccShowVerticalSliders = v) }
                }
                ToggleRow("显示大胶囊快捷开关", "WLAN、蓝牙、手电筒与微信通话一键录音", settings.ccShowQuickToggles) { v ->
                    repo.update { it.copy(ccShowQuickToggles = v) }
                }
                ToggleRow("显示毛玻璃通知列表", "同步系统通知与底部「全部清除」", settings.ccShowNotifications) { v ->
                    repo.update { it.copy(ccShowNotifications = v) }
                }

                Spacer(Modifier.height(6.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (Settings.canDrawOverlays(context)) {
                            context.startForegroundService(
                                Intent(context, FreeformGestureService::class.java)
                                    .setAction(FreeformGestureService.ACTION_OPEN_CONTROL_CENTER)
                            )
                        } else {
                            Toast.makeText(context, "请先授予悬浮窗权限才能预览控制中心", Toast.LENGTH_SHORT).show()
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }
                ) {
                    Text("立即预览我设计的控制中心")
                }
            }

            // 4. 弹幕通知与边缘取消气泡手势
            SectionCard(title = "全圆角弹幕通知与手势控制") {
                ToggleRow(
                    title = "验证码自动识别复制（纯本地）",
                    subtitle = "收到短信或 App 验证码时自动复制到剪贴板并弹胶囊提示",
                    checked = settings.autoCopyOtpEnabled
                ) { v -> repo.update { it.copy(autoCopyOtpEnabled = v) } }

                ToggleRow(
                    title = "Flyme 风格全圆角弹幕通知",
                    subtitle = "白名单消息以圆角胶囊弹幕横飘提醒，点击直开应用",
                    checked = settings.danmakuNotificationEnabled
                ) { v -> repo.update { it.copy(danmakuNotificationEnabled = v) } }

                ToggleRow(
                    title = "同时显示顶部悬浮通知横幅",
                    subtitle = "默认关闭，喜欢卡片横幅可开启",
                    checked = settings.notificationBannerEnabled
                ) { v -> repo.update { it.copy(notificationBannerEnabled = v) } }

                ToggleRow(
                    title = "左下角斜滑主动呼出小窗菜单（Q 弹动画）",
                    subtitle = "从左下角斜向内上滑弹出小窗菜单，点击应用直呼原生气泡（右下角留给 Gemini）",
                    checked = settings.cornerLeftLaunchEnabled
                ) { v -> repo.update { it.copy(cornerLeftLaunchEnabled = v) } }

                ToggleRow(
                    title = "右侧边白条滑出 / 轻点呼出小窗菜单",
                    subtitle = "右侧中段细条向左滑或轻点弹出 Q 弹快捷小窗面板",
                    checked = settings.sidebarLaunchEnabled
                ) { v -> repo.update { it.copy(sidebarLaunchEnabled = v) } }

                ToggleRow(
                    title = "备用：左侧红条手势控制原生气泡",
                    subtitle = "向内滑/轻点直接关闭当前气泡，向下滑收成圆球（右下角留给 Gemini）",
                    checked = settings.edgeDismissGestureEnabled
                ) { v -> repo.update { it.copy(edgeDismissGestureEnabled = v) } }

                ToggleRow(
                    title = "显示热区调试色块",
                    subtitle = "打开后高亮显示左侧手势触发区域",
                    checked = settings.showHotzoneDebug
                ) { v -> repo.update { it.copy(showHotzoneDebug = v) } }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = { pickerMode = "quick" }
                    ) {
                        Text("快捷小窗 (${settings.quickLaunchPackages.size})")
                    }
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = { pickerMode = "notify" }
                    ) {
                        Text("弹幕白名单 (${settings.notificationPackages.size})")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val intent = Intent(context, FreeformGestureService::class.java).apply {
                                action = FreeformGestureService.ACTION_SHOW_BANNER
                                putExtra(FreeformGestureService.EXTRA_PACKAGE_NAME, context.packageName)
                                putExtra(FreeformGestureService.EXTRA_TITLE, "测试弹幕通知")
                                putExtra(FreeformGestureService.EXTRA_TEXT, "AOSPA 17 全圆角弹幕运行正常！")
                            }
                            context.startForegroundService(intent)
                        }
                    ) {
                        Text("测试弹幕通知")
                    }
                }
            }

            // 5. 微信通话录音 & 电话双向录音（BCR）
            SectionCard(title = "微信录音 & 电话双向录音") {
                StatusRow("微信通话录音状态", if (isWechatRecording) "正在录音中..." else "待机")
                if (lastRecPath.isNotBlank()) {
                    Text(
                        text = "上次保存：$lastRecPath",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val msg = WechatRecorder.toggle(context)
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                ) {
                    Text(if (isWechatRecording) "停止微信通话录音并保存" else "开始微信通话录音（免 Hook 零封号）")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                StatusRow("普通电话录音（BCR 模块）", if (bcrInstalled) "已安装" else "未安装")
                Spacer(Modifier.height(6.dp))
                if (bcrInstalled) {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            context.packageManager.getLaunchIntentForPackage("com.chiller3.bcr")
                                ?.let { context.startActivity(it) }
                        }
                    ) {
                        Text("打开 BCR 通话录音设置")
                    }
                } else {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/chenxiaolong/BCR/releases"))
                            )
                        }
                    ) {
                        Text("前往 GitHub 下载 BCR 通话录音模块")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickerMode != null) {
        val isQuick = pickerMode == "quick"
        AppPickerDialog(
            title = if (isQuick) "选择快捷呼出小窗应用" else "选择弹幕通知白名单应用",
            apps = installedApps,
            selectedPackages = if (isQuick) settings.quickLaunchPackages else settings.notificationPackages,
            onToggle = { pkg ->
                repo.update { cur ->
                    val target = if (isQuick) cur.quickLaunchPackages else cur.notificationPackages
                    val next = if (pkg in target) target - pkg else target + pkg
                    if (isQuick) cur.copy(quickLaunchPackages = next) else cur.copy(notificationPackages = next)
                }
            },
            onDismiss = { pickerMode = null }
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (value.contains("已") || value == "运行中") {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AppPickerDialog(
    title: String,
    apps: List<AppEntry>,
    selectedPackages: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索应用名或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        val isChecked = app.packageName in selectedPackages
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(app.packageName) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { onToggle(app.packageName) }
                            )
                            Column(modifier = Modifier.padding(start = 6.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}
