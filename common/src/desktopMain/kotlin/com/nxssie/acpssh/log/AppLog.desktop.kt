package com.nxssie.acpssh.log

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun mirrorToPlatformLog(level: LogLevel, tag: String, message: String) {
    val line = "[${level.name}] [$tag] $message"
    if (level == LogLevel.ERROR || level == LogLevel.WARN) System.err.println(line) else println(line)
}
