package com.nxssie.acpssh.log

import android.util.Log

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

private const val LOGCAT_TAG = "AcpSshKmp"

actual fun mirrorToPlatformLog(level: LogLevel, tag: String, message: String) {
    val line = "[$tag] $message"
    when (level) {
        LogLevel.DEBUG -> Log.d(LOGCAT_TAG, line)
        LogLevel.INFO -> Log.i(LOGCAT_TAG, line)
        LogLevel.WARN -> Log.w(LOGCAT_TAG, line)
        LogLevel.ERROR -> Log.e(LOGCAT_TAG, line)
    }
}
