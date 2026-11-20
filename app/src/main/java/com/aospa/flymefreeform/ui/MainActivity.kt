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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aospa.flymefreeform.App
import com.aospa.flymefreeform.core.AppCatalog
import com.aospa.flymefreeform.core.AppEntry
import com.aospa.flymefreeform.core.RootFreeformEngine
import com.aospa.flymefreeform.core.RootShell
import com.aospa.flymefreeform.notification.FreeformNotificationListener
import com.aospa.flymefreeform.overlay.FreeformGestureService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 授权结果在页面刷新时自动反映 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = App.instance.settings
    val settings by repo.settings.collectAsState()
    val logs by RootShell.logs.collectAsState()

    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var freeformEnabled by remember { mutableStateOf<Boolean?>(null) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifListenerOk by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var serviceRunning by remember { mutableStateOf(FreeformGestureService.instance != null) }

    var installedApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var pickerMode by remember { mutableStateOf<String?>(null) } // "quick" | "notify" | null

    fun refreshStatus() {
        overlayOk = Settings.canDrawOverlays(context)
        notifListenerOk = isNotificationListenerEnabled(context)
        serviceRunning = FreeformGestureService.instance != null
        scope.launch {
            rootOk = RootShell.isRootAvailable()
            if (rootOk == true) {
                freeformEnabled = RootFreeformEngine.isFreeformSettingEnabled()
            }
        }
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
                        Text("Flyme小窗 · AOSPA", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Root 原生自由窗口 + 手势取消小窗",
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
            // 1. 环境与权限看板
            SectionCard(title = "环境与权限检查") {
                StatusRow("Root 权限", rootOk?.let { if (it) "已获取" else "未获取" } ?: "检测中...")
                StatusRow(
                    "系统自由窗口开关",
                    freeformEnabled?.let { if (it) "已开启" else "未开启" } ?: "待检测"
                )
                StatusRow("悬浮窗权限", if (overlayOk) "已授权" else "未授权")
                StatusRow("通知监听权限（直开小窗）", if (notifListenerOk) "已授权" else "未授权")
                StatusRow("手势服务状态", if (serviceRunning) "运行中" else "未启动")

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                val ok = RootFreeformEngine.ensureFreeformEnabled()
                                freeformEnabled = ok
                                if (Settings.canDrawOverlays(context)) {
                                    FreeformGestureService.start(context)
                                    serviceRunning = true
                                }
                            }
                        }
                    ) {
                        Text("一键开启并启动")
                    }
                    OutlinedButton(
                        onClick = {
                            if (serviceRunning) {
                                FreeformGestureService.stop(context)
                                serviceRunning = false
                            } else if (Settings.canDrawOverlays(context)) {
                                FreeformGestureService.start(context)
                                serviceRunning = true
                            }
                        }
                    ) {
                        Text(if (serviceRunning) "停止服务" else "启动服务")
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
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
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

                TextButton(
                    modifier = Modifier.align(Alignment.End),
                    onClick = { refreshStatus() }
                ) {
                    Text("刷新状态")
                }
            }

            // 2. 取消/关闭小窗手势（核心功能）
            SectionCard(title = "取消小窗手势（默认全开）") {
                ToggleRow(
                    title = "顶部悬浮条上滑取消小窗",
                    subtitle = "检测到小窗时顶部自动出现细条，向上轻滑即关闭当前小窗",
                    checked = settings.topBarDismissSwipeUpEnabled
                ) { v -> repo.update { it.copy(topBarDismissSwipeUpEnabled = v) } }

                ToggleRow(
                    title = "顶部悬浮条双击取消小窗",
                    subtitle = "连续点击两次顶部小窗细条，直接关闭当前小窗",
                    checked = settings.topBarDismissDoubleTapEnabled
                ) { v -> repo.update { it.copy(topBarDismissDoubleTapEnabled = v) } }

                ToggleRow(
                    title = "顶部悬浮条下滑展开全屏",
                    subtitle = "向下拉顶部细条，将当前小窗切换回全屏显示",
                    checked = settings.topBarExpandSwipeDownEnabled
                ) { v -> repo.update { it.copy(topBarExpandSwipeDownEnabled = v) } }

                ToggleRow(
                    title = "屏幕边缘手势一键取消小窗",
                    subtitle = "在屏幕中上侧边缘向内滑或下滑，无需点叉直接取消小窗",
                    checked = settings.edgeDismissGestureEnabled
                ) { v -> repo.update { it.copy(edgeDismissGestureEnabled = v) } }

                if (settings.edgeDismissGestureEnabled) {
                    ToggleRow(
                        title = "边缘取消热区放在左侧",
                        subtitle = "关闭则放到屏幕右侧（与侧边栏呼出错开）",
                        checked = settings.edgeDismissOnLeft
                    ) { v -> repo.update { it.copy(edgeDismissOnLeft = v) } }
                }
            }

            // 3. 呼出小窗手势与通知直开
            SectionCard(title = "呼出小窗与通知直开") {
                ToggleRow(
                    title = "左下角斜向内滑呼出小窗菜单",
                    subtitle = "Flyme 经典底角手势，斜向上内滑弹出快捷应用面板",
                    checked = settings.cornerLeftLaunchEnabled
                ) { v -> repo.update { it.copy(cornerLeftLaunchEnabled = v) } }

                ToggleRow(
                    title = "右下角斜向内滑呼出小窗菜单",
                    subtitle = "右手单手从右下角斜向内滑呼出快捷面板",
                    checked = settings.cornerRightLaunchEnabled
                ) { v -> repo.update { it.copy(cornerRightLaunchEnabled = v) } }

                ToggleRow(
                    title = "侧边栏滑出呼出小窗菜单",
                    subtitle = "屏幕侧边中段透明细条向内滑呼出快捷面板",
                    checked = settings.sidebarLaunchEnabled
                ) { v -> repo.update { it.copy(sidebarLaunchEnabled = v) } }

                ToggleRow(
                    title = "悬浮通知横幅直开小窗",
                    subtitle = "收到白名单应用消息时顶部弹横幅，点击或下拉直开小窗",
                    checked = settings.notificationBannerEnabled
                ) { v -> repo.update { it.copy(notificationBannerEnabled = v) } }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = { pickerMode = "quick" }
                    ) {
                        Text("快捷应用 (${settings.quickLaunchPackages.size})")
                    }
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = { pickerMode = "notify" }
                    ) {
                        Text("通知白名单 (${settings.notificationPackages.size})")
                    }
                }
            }

            // 4. 尺寸与手势微调
            SectionCard(title = "小窗比例与手势微调") {
                Text("默认小窗宽度：${(settings.windowWidthRatio * 100).roundToInt()}%")
                Slider(
                    value = settings.windowWidthRatio,
                    onValueChange = { v -> repo.update { it.copy(windowWidthRatio = v) } },
                    valueRange = 0.65f..0.92f
                )

                Text("默认小窗高度：${(settings.windowHeightRatio * 100).roundToInt()}%")
                Slider(
                    value = settings.windowHeightRatio,
                    onValueChange = { v -> repo.update { it.copy(windowHeightRatio = v) } },
                    valueRange = 0.45f..0.75f
                )

                Text("手势触发距离：${settings.gestureSensitivityDp} dp（越小越灵敏）")
                Slider(
                    value = settings.gestureSensitivityDp.toFloat(),
                    onValueChange = { v -> repo.update { it.copy(gestureSensitivityDp = v.roundToInt()) } },
                    valueRange = 36f..88f
                )

                ToggleRow(
                    title = "显示手势热区色块（调试用）",
                    subtitle = "蓝=底角呼出、绿=侧边呼出、红=边缘取消、橙=顶部取消条",
                    checked = settings.showHotzoneDebug
                ) { v -> repo.update { it.copy(showHotzoneDebug = v) } }
            }

            // 5. 快速验证与日志
            SectionCard(title = "一键测试与 Root 日志") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val testPkg = settings.quickLaunchPackages.firstOrNull { pkg ->
                                installedApps.any { it.packageName == pkg }
                            } ?: installedApps.firstOrNull()?.packageName ?: return@FilledTonalButton
                            scope.launch {
                                RootFreeformEngine.launchInFreeform(
                                    context,
                                    testPkg,
                                    settings.windowWidthRatio,
                                    settings.windowHeightRatio
                                )
                            }
                        }
                    ) {
                        Text("测试启动小窗")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                RootFreeformEngine.dismissTopFreeformTask()
                            }
                        }
                    ) {
                        Text("测试取消小窗")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (logs.isEmpty()) "暂无日志" else logs.takeLast(8).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickerMode != null) {
        val isQuick = pickerMode == "quick"
        val selected = if (isQuick) settings.quickLaunchPackages else settings.notificationPackages
        AppPickerDialog(
            title = if (isQuick) "选择快捷小窗应用" else "选择通知直开小窗白名单",
            apps = installedApps,
            selectedPackages = selected,
            onToggle = { pkg ->
                repo.update { cur ->
                    val target = if (isQuick) cur.quickLaunchPackages else cur.notificationPackages
                    val next = if (pkg in target) target - pkg else target + pkg
                    if (isQuick) cur.copy(quickLaunchPackages = next)
                    else cur.copy(notificationPackages = next)
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
