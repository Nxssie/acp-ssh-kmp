package com.nxssie.acpssh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxssie.acpssh.session.AcpHost
import com.nxssie.acpssh.session.RemoteAgentSession

/**
 * "Sesiones del servidor…": lo que hay vivo en el runDir base del perfil
 * activo, incluyendo lo que este dispositivo no recuerda (otro dispositivo,
 * un "Cerrar tab" que dejó el agente vivo, o una reinstalación). Es la
 * alternativa real a matar sesiones a ciegas con un cron en el host: aquí se
 * ve qué hay y se puede retomar o terminar con conocimiento de causa.
 *
 * Confirmación de dos toques en vez de un `AlertDialog` anidado (no hay
 * precedente de eso en la app — ni siquiera "Cerrar y terminar agente" lo
 * tiene hoy): "Terminar" es irreversible, y "Retomar" sobre una sesión
 * enganchada por otro cliente lo desconecta (el relevo de reader/writer del
 * runDir mata al lector anterior, ver `RemoteAcpProcess`).
 */
@Composable
fun RemoteSessionsDialog(host: AcpHost, onDismiss: () -> Unit) {
    val ui by host.remoteSessions.collectAsState()
    var confirmKill by remember { mutableStateOf<String?>(null) }
    var confirmSteal by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { host.refreshRemoteSessions() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sesiones del servidor") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ui.loading && ui.sessions.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    }
                } else if (ui.sessions.isEmpty() && ui.error == null) {
                    Text(
                        "No hay agentes en el servidor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ui.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                ui.sessions.forEach { session ->
                    Column {
                        Row {
                            Text(
                                session.dirName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                statusLabel(session),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (session.alive) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        Text(
                            detailLine(session),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when (session.dirName) {
                            confirmKill -> Row {
                                Text(
                                    "¿Terminar de verdad? Se pierde la conversación.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { host.killRemoteSession(session.dirName); confirmKill = null }) {
                                    Text("Sí, terminar")
                                }
                                TextButton(onClick = { confirmKill = null }) { Text("No") }
                            }
                            confirmSteal -> Row {
                                Text(
                                    "Otro cliente está enganchado: retomar lo desconecta.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { host.attachRemoteSession(session.dirName); confirmSteal = null }) {
                                    Text("Retomar igual")
                                }
                                TextButton(onClick = { confirmSteal = null }) { Text("Cancelar") }
                            }
                            else -> Row {
                                TextButton(
                                    onClick = {
                                        if (session.attached == true && !session.openHere) {
                                            confirmSteal = session.dirName
                                        } else {
                                            host.attachRemoteSession(session.dirName)
                                        }
                                    },
                                    enabled = session.alive && !session.openHere,
                                ) { Text("Retomar") }
                                TextButton(onClick = { confirmKill = session.dirName }) { Text("Terminar") }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = host::refreshRemoteSessions) { Text("Refrescar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

private fun statusLabel(session: RemoteAgentSession): String = when {
    session.openHere -> "Activa aquí"
    !session.alive -> "Restos"
    session.attached == true -> "Enganchada"
    else -> "Suelta"
}

private fun detailLine(session: RemoteAgentSession): String {
    val pid = session.pid?.let { "pid $it" } ?: "sin pid"
    val idle = session.idleSeconds?.let { " · inactiva ${humanizeSeconds(it)}" }.orEmpty()
    val cwd = session.cwd?.let { " · $it" }.orEmpty()
    return "$pid$idle$cwd"
}

private fun humanizeSeconds(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}min"
    seconds < 86400 -> "${seconds / 3600}h"
    else -> "${seconds / 86400}d"
}
