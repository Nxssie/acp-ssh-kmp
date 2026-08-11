package com.nxssie.acpssh.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Una entrada del log de diagnóstico. [detail] es texto largo opcional (stack
 * trace, error crudo del agente) que la UI colapsa por defecto — [message]
 * debe caber en una línea, lo que se ve sin expandir nada.
 */
data class LogEntry(
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val detail: String? = null,
)

/**
 * Log de diagnóstico en memoria, común a todos los hosts (SSH, ACP): sin esto,
 * un error de conexión/protocolo solo se veía como el último `state.error` en
 * pantalla — se perdía en cuanto pasaba el siguiente evento y no había forma
 * de inspeccionarlo en el propio dispositivo sin `adb logcat`. Además de
 * quedar en [entries] (consumible por [com.nxssie.acpssh.ui.LogViewerDialog]),
 * cada entrada se espeja al log nativo de la plataforma (ver [mirrorToPlatformLog])
 * para depurar con las herramientas de siempre cuando sí hay acceso al host.
 */
object AppLog {
    private const val MAX_ENTRIES = 300

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    fun d(tag: String, message: String, detail: String? = null) = log(LogLevel.DEBUG, tag, message, detail)
    fun i(tag: String, message: String, detail: String? = null) = log(LogLevel.INFO, tag, message, detail)
    fun w(tag: String, message: String, detail: String? = null) = log(LogLevel.WARN, tag, message, detail)
    fun e(tag: String, message: String, detail: String? = null) = log(LogLevel.ERROR, tag, message, detail)

    private fun log(level: LogLevel, tag: String, message: String, detail: String?) {
        val entry = LogEntry(currentTimeMillis(), level, tag, message, detail)
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
        mirrorToPlatformLog(level, tag, message)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}

expect fun currentTimeMillis(): Long

/** Espejo al log nativo de la plataforma (logcat en Android, stderr en desktop). */
expect fun mirrorToPlatformLog(level: LogLevel, tag: String, message: String)
