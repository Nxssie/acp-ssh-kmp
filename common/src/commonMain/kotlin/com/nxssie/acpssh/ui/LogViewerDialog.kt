package com.nxssie.acpssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxssie.acpssh.log.AppLog
import com.nxssie.acpssh.log.LogEntry
import com.nxssie.acpssh.log.LogLevel

/**
 * Registro de diagnóstico en vivo: errores de conexión/agente ya vienen
 * clasificados por origen (ver [com.nxssie.acpssh.acp.ErrorOrigin]) en
 * [LogEntry.message], y el detalle completo (stack trace, error crudo del
 * agente) queda colapsado hasta que se toca la fila — así se puede ver "qué
 * error devolvió" sin adb, y copiarlo para reportarlo.
 */
@Composable
fun LogViewerDialog(onDismiss: () -> Unit) {
    val entries by AppLog.entries.collectAsState()
    val clipboard = LocalClipboardManager.current
    var expandedIndex by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registro de diagnóstico") },
        text = {
            if (entries.isEmpty()) {
                Text(
                    "Sin entradas todavía.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(entries.asReversed()) { reversedIndex, entry ->
                        val index = entries.size - 1 - reversedIndex
                        LogRow(
                            entry = entry,
                            expanded = expandedIndex == index,
                            onToggle = { expandedIndex = if (expandedIndex == index) null else index },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(entries.joinToString("\n") { it.toPlainLine() }))
            }) { Text("Copiar todo") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = AppLog::clear) { Text("Limpiar") }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
}

@Composable
private fun LogRow(entry: LogEntry, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LevelDot(entry.level)
            Text(
                "[${entry.tag}]",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                entry.message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (expanded && entry.detail != null) {
            Text(
                entry.detail,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 30,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun LevelDot(level: LogLevel) {
    Box(
        Modifier
            .size(8.dp)
            .padding(end = 4.dp)
            .background(levelColor(level), CircleShape),
    )
}

private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> Color(0xFFD32F2F)
    LogLevel.WARN -> Color(0xFFF9A825)
    LogLevel.INFO -> Color(0xFF1565C0)
    LogLevel.DEBUG -> Color(0xFF9E9E9E)
}

private fun LogEntry.toPlainLine(): String {
    val head = "[$level] [$tag] $message"
    return if (detail != null) "$head\n$detail" else head
}
