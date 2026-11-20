package com.aospa.flymefreeform.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

object RootShell {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun appendLog(message: String) {
        val stamped = "[${timeFormat.format(Date())}] $message"
        _logs.value = (_logs.value + stamped).takeLast(40)
    }

    suspend fun exec(vararg commands: String): ShellResult = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = ProcessBuilder("su").start()
            DataOutputStream(process.outputStream).use { os ->
                for (cmd in commands) {
                    os.writeBytes("$cmd\n")
                }
                os.writeBytes("exit\n")
                os.flush()
            }

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val code = process.waitFor()
            ShellResult(code, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "Root execution error")
        } finally {
            process?.destroy()
        }
    }

    suspend fun isRootAvailable(): Boolean {
        val result = exec("id")
        val ok = result.isSuccess && result.stdout.contains("uid=0")
        appendLog(if (ok) "Root 检测通过" else "Root 检测失败: ${result.stderr}")
        return ok
    }
}
