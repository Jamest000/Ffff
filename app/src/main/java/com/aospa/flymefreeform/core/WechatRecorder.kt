package com.aospa.flymefreeform.core

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 免 Hook 微信的通话录音控制器（零封号风险）：
 * 优先尝试 VOICE_COMMUNICATION / MIC 高质量 AAC 录音保存至 /sdcard/Music/WeChatRecords/
 */
object WechatRecorder {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _lastSavedPath = MutableStateFlow("")
    val lastSavedPath: StateFlow<String> = _lastSavedPath.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    fun toggle(context: Context): String {
        return if (_isRecording.value) {
            stop()
        } else {
            start(context)
        }
    }

    fun start(context: Context): String {
        if (_isRecording.value) return "正在录音中"
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "WeChatRecords"
            )
            if (!dir.exists()) dir.mkdirs()

            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val outFile = File(dir, "WeChat_$ts.m4a")
            currentFile = outFile

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            recorder = mr
            _isRecording.value = true
            "已开始录音（建议开扬声器效果更清晰）"
        } catch (e: Exception) {
            stopInternal()
            "启动录音失败：请授予麦克风权限 (${e.message})"
        }
    }

    fun stop(): String {
        val saved = currentFile?.absolutePath.orEmpty()
        stopInternal()
        if (saved.isNotBlank()) {
            _lastSavedPath.value = saved
            return "录音已保存：$saved"
        }
        return "已停止录音"
    }

    private fun stopInternal() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        _isRecording.value = false
    }
}
