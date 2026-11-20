package com.aospa.flymefreeform.core

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.WindowManager
import kotlinx.coroutines.delay

data class FreeformTaskInfo(
    val taskId: Int,
    val packageName: String,
    val bounds: Rect? = null
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

    fun getScreenBounds(context: Context): Rect {
        val wm = context.getSystemService(WindowManager::class.java)
        val b = wm.currentWindowMetrics.bounds
        return Rect(0, 0, b.width().coerceAtLeast(1080), b.height().coerceAtLeast(1920))
    }

    /**
     * 计算默认正常小窗区域
     */
    fun computeDefaultBounds(
        context: Context,
        widthRatio: Float,
        heightRatio: Float
    ): Rect {
        val screen = getScreenBounds(context)
        val sw = screen.width()
        val sh = screen.height()

        val w = (sw * widthRatio.coerceIn(0.55f, 0.94f)).toInt()
        val h = (sh * heightRatio.coerceIn(0.40f, 0.80f)).toInt()
        val left = (sw - w) / 2
        val top = (sh * 0.14f).toInt().coerceAtLeast(120)
        return Rect(left, top, left + w, (top + h).coerceAtMost(sh - 120))
    }

    /**
     * 计算右上角迷你小窗区域（Flyme 贴边缩小风格）
     */
    fun computeMiniTopRightBounds(context: Context): Rect {
        val screen = getScreenBounds(context)
        val sw = screen.width()
        val sh = screen.height()

        val miniW = (sw * 0.34f).toInt().coerceAtLeast(320)
        val miniH = (sh * 0.24f).toInt().coerceAtLeast(420)
        val rightMargin = (sw * 0.025f).toInt().coerceAtLeast(20)
        val topMargin = (sh * 0.055f).toInt().coerceAtLeast(110)

        val right = sw - rightMargin
        val left = (right - miniW).coerceAtLeast(rightMargin)
        return Rect(left, topMargin, right, topMargin + miniH)
    }

    /**
     * 判断一个自由窗口是否位于屏幕右上角且尚未缩小
     */
    fun isInTopRightTriggerZone(context: Context, bounds: Rect?): Boolean {
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return false
        val screen = getScreenBounds(context)
        val sw = screen.width()
        val sh = screen.height()

        // 已经是迷你尺寸则不再重复触发
        val isAlreadyMini = bounds.width() <= (sw * 0.44f).toInt()
        if (isAlreadyMini) return false

        val inRightHalf = bounds.right >= (sw * 0.88f).toInt() || bounds.centerX() >= (sw * 0.62f).toInt()
        val nearTop = bounds.top <= (sh * 0.20f).toInt()
        return inRightHalf && nearTop
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
     * 查询当前所有运行中的自由窗口 (Freeform) 任务 ID 及其坐标区域
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
        val rectRegex = Regex("""Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)""")
        val bracketRectRegex = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")

        for (i in lines.indices) {
            val line = lines[i]
            val match = taskIdRegex.find(line) ?: altTaskRegex.find(line) ?: continue
            val taskId = match.groupValues[1].toIntOrNull() ?: continue
            if (taskId in seenIds) continue

            val blockEnd = (i + 10).coerceAtMost(lines.lastIndex)
            val block = lines.subList(i, blockEnd + 1).joinToString(" ")
            val isFreeform = block.contains("mode=freeform", ignoreCase = true) ||
                block.contains("windowingMode=freeform", ignoreCase = true) ||
                block.contains("mWindowingMode=5", ignoreCase = true)

            if (isFreeform) {
                val pkg = pkgRegex.find(block)?.groupValues?.getOrNull(1)
                    ?: cmpRegex.find(block)?.groupValues?.getOrNull(1)
                    ?: "unknown"

                val rm = rectRegex.find(block) ?: bracketRectRegex.find(block)
                val parsedBounds = rm?.let {
                    val l = it.groupValues[1].toIntOrNull() ?: return@let null
                    val t = it.groupValues[2].toIntOrNull() ?: return@let null
                    val r = it.groupValues[3].toIntOrNull() ?: return@let null
                    val b = it.groupValues[4].toIntOrNull() ?: return@let null
                    Rect(l, t, r, b)
                }

                seenIds.add(taskId)
                result.add(FreeformTaskInfo(taskId, pkg, parsedBounds))
            }
        }
        return result
    }

    /**
     * 将当前小窗缩小并贴靠至右上角（迷你小窗模式）
     */
    suspend fun shrinkTaskToTopRight(context: Context, taskId: Int? = null): Boolean {
        val targetId = taskId ?: getActiveFreeformTasks().firstOrNull()?.taskId ?: return false
        val mini = computeMiniTopRightBounds(context)
        val res = RootShell.exec(
            "am task resize $targetId ${mini.left} ${mini.top} ${mini.right} ${mini.bottom}"
        )
        if (res.isSuccess) {
            RootShell.appendLog("已缩为右上角迷你小窗: #$targetId")
            return true
        }
        RootShell.appendLog("缩小迷你小窗失败(#$targetId): ${res.stderr}")
        return false
    }

    /**
     * 将右上角迷你小窗还原为正常小窗尺寸
     */
    suspend fun restoreTaskToNormal(
        context: Context,
        widthRatio: Float,
        heightRatio: Float,
        taskId: Int? = null
    ): Boolean {
        val targetId = taskId ?: getActiveFreeformTasks().firstOrNull()?.taskId ?: return false
        val normal = computeDefaultBounds(context, widthRatio, heightRatio)
        val res = RootShell.exec(
            "am task resize $targetId ${normal.left} ${normal.top} ${normal.right} ${normal.bottom}"
        )
        if (res.isSuccess) {
            RootShell.appendLog("已还原为正常小窗: #$targetId")
            return true
        }
        return false
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
     * 将当前顶层小窗展开为全屏模式 (windowingMode = 1)
     */
    suspend fun expandTopFreeformToFullscreen(): Boolean {
        val top = getActiveFreeformTasks().firstOrNull() ?: return false
        val res = RootShell.exec("am task setWindowingMode ${top.taskId} 1")
        if (res.isSuccess) {
            RootShell.appendLog("已将小窗转为全屏: #${top.taskId} (${top.packageName})")
            return true
        }
        val launchIntent = RootShell.exec(
            "am start --windowingMode 1 -p '${top.packageName}'"
        )
        return launchIntent.isSuccess
    }
}
