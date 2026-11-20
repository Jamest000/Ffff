package com.aospa.flymefreeform.core

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.WindowManager
import kotlinx.coroutines.delay

data class FreeformTaskInfo(
    val taskId: Int,
    val packageName: String
)

object RootFreeformEngine {

    /**
     * 开启 AOSPA / AOSP 原生自由窗口系统开关
     */
    suspend fun ensureFreeformEnabled(): Boolean {
        val res = RootShell.exec(
            "settings put global enable_freeform_support 1",
            "settings put global force_resizable_activities 1",
            "settings put global enable_non_resizable_multi_window 1",
            "settings put global development_settings_enabled 1"
        )
        RootShell.appendLog(
            if (res.isSuccess) "已启用系统自由窗口 (Freeform) 开关"
            else "启用自由窗口开关失败: ${res.stderr}"
        )
        return res.isSuccess
    }

    suspend fun isFreeformSettingEnabled(): Boolean {
        val res = RootShell.exec("settings get global enable_freeform_support")
        return res.isSuccess && res.stdout.trim() == "1"
    }

    /**
     * 根据当前屏幕分辨率与用户比例计算默认小窗区域
     */
    fun computeDefaultBounds(
        context: Context,
        widthRatio: Float,
        heightRatio: Float
    ): Rect {
        val wm = context.getSystemService(WindowManager::class.java)
        val screenBounds = wm.currentWindowMetrics.bounds
        val sw = screenBounds.width().coerceAtLeast(1080)
        val sh = screenBounds.height().coerceAtLeast(1920)

        val w = (sw * widthRatio.coerceIn(0.55f, 0.94f)).toInt()
        val h = (sh * heightRatio.coerceIn(0.40f, 0.80f)).toInt()
        val left = (sw - w) / 2
        val top = (sh * 0.14f).toInt().coerceAtLeast(120)
        return Rect(left, top, left + w, (top + h).coerceAtMost(sh - 120))
    }

    /**
     * 以自由窗口模式 (windowingMode = 5) 启动指定应用并调整位置与尺寸
     */
    suspend fun launchInFreeform(
        context: Context,
        packageName: String,
        widthRatio: Float = 0.80f,
        heightRatio: Float = 0.56f
    ): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val component = launchIntent?.component?.flattenToShortString()
        val bounds = computeDefaultBounds(context, widthRatio, heightRatio)

        val startCmd = if (component != null) {
            "am start --windowingMode 5 --activityType 1 -f 0x10000000 -n '$component'"
        } else {
            "am start --windowingMode 5 --activityType 1 -a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_LAUNCHER} -p '$packageName'"
        }

        val res = RootShell.exec(startCmd)
        if (!res.isSuccess) {
            RootShell.appendLog("启动小窗失败($packageName): ${res.stderr}")
            return false
        }
        RootShell.appendLog("已以小窗启动: $packageName")

        // 等待任务栈创建后按指定比例调整尺寸（兼容 Android 14~17）
        delay(280)
        val tasks = getActiveFreeformTasks()
        val target = tasks.firstOrNull { it.packageName == packageName } ?: tasks.firstOrNull()
        if (target != null) {
            RootShell.exec(
                "am task resize ${target.taskId} ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"
            )
        }
        return true
    }

    /**
     * 查询当前所有运行中的自由窗口 (Freeform) 任务 ID
     * 兼容 AOSP Android 14/15/16/17 的 dumpsys activity activities 输出
     */
    suspend fun getActiveFreeformTasks(): List<FreeformTaskInfo> {
        val res = RootShell.exec("dumpsys activity activities")
        if (!res.isSuccess) return emptyList()

        val result = mutableListOf<FreeformTaskInfo>()
        val seenIds = mutableSetOf<Int>()
        val lines = res.stdout.lines()

        val taskIdRegex = Regex("""Task\{[0-9a-fA-F]+\s+#(\d+)\s+type=standard""")
        val altTaskRegex = Regex("""\* Task\{[0-9a-fA-F]+\s+#(\d+)""")
        val pkgRegex = Regex("""A=\d+:([a-zA-Z0-9_.]+)""")
        val cmpRegex = Regex("""([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.$]+""")

        for (i in lines.indices) {
            val line = lines[i]
            val match = taskIdRegex.find(line) ?: altTaskRegex.find(line) ?: continue
            val taskId = match.groupValues[1].toIntOrNull() ?: continue
            if (taskId in seenIds) continue

            // 检查当前 Task 块及其后 6 行是否包含 freeform 窗口模式标记
            val blockEnd = (i + 6).coerceAtMost(lines.lastIndex)
            val block = lines.subList(i, blockEnd + 1).joinToString(" ")
            val isFreeform = block.contains("mode=freeform", ignoreCase = true) ||
                block.contains("windowingMode=freeform", ignoreCase = true) ||
                block.contains("mWindowingMode=5", ignoreCase = true)

            if (isFreeform) {
                val pkg = pkgRegex.find(block)?.groupValues?.getOrNull(1)
                    ?: cmpRegex.find(block)?.groupValues?.getOrNull(1)
                    ?: "unknown"
                seenIds.add(taskId)
                result.add(FreeformTaskInfo(taskId, pkg))
            }
        }
        return result
    }

    /**
     * 取消/关闭当前最顶层的小窗任务
     */
    suspend fun dismissTopFreeformTask(): Boolean {
        val tasks = getActiveFreeformTasks()
        val top = tasks.firstOrNull()
        if (top == null) {
            RootShell.appendLog("未检测到活跃小窗任务")
            return false
        }

        // 优先使用温和的任务移除命令，不强杀后台进程
        val res = RootShell.exec(
            "am task remove ${top.taskId} || cmd activity task remove ${top.taskId}"
        )
        if (res.isSuccess) {
            RootShell.appendLog("已取消小窗: #${top.taskId} (${top.packageName})")
            return true
        }

        RootShell.appendLog("取消小窗失败(#${top.taskId}): ${res.stderr}")
        return false
    }

    /**
     * 一键关闭所有运行中的小窗任务
     */
    suspend fun dismissAllFreeformTasks(): Int {
        val tasks = getActiveFreeformTasks()
        if (tasks.isEmpty()) return 0
        var closed = 0
        for (task in tasks) {
            val res = RootShell.exec("am task remove ${task.taskId}")
            if (res.isSuccess) closed++
        }
        RootShell.appendLog("已批量取消 $closed 个小窗")
        return closed
    }

    /**
     * 将当前顶层小窗展开为全屏模式 (windowingMode = 1)
     */
    suspend fun expandTopFreeformToFullscreen(): Boolean {
        val top = getActiveFreeformTasks().firstOrNull() ?: return false
        val res = RootShell.exec("am task setWindowingMode ${top.taskId} 1")
        if (res.isSuccess) {
            RootShell.appendLog("已将小窗转为全屏: #${top.taskId} (${top.packageName})")
            return true
        }
        // 备用：全屏重拉主 Activity
        val launchIntent = RootShell.exec(
            "am start --windowingMode 1 -p '${top.packageName}'"
        )
        return launchIntent.isSuccess
    }
}
