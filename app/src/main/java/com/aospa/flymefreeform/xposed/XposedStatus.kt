package com.aospa.flymefreeform.xposed

object XposedStatus {
    // 激活后会被 SystemUiHookEntry 自动 Hook 返回 true
    fun isModuleActive(): Boolean = false
}
