package com.nxssie.acpssh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.nxssie.acpssh.profile.ProfileStore
import com.nxssie.acpssh.profile.SavedCommand
import com.nxssie.acpssh.profile.newProfileId
import com.nxssie.acpssh.session.AcpMode

/**
 * Gestor de comandos guardados (Fase G): lista con renombrar/borrar y alta
 * inline (label + comando + modo: Ambos/Terminal/Chat). El modo restringe en
 * qué selector aparece; "Ambos" (null) lo hace reutilizable entre modos.
 */
@Composable
fun CommandManagerDialog(
    store: ProfileStore,
    mode: AcpMode,
    onDismiss: () -> Unit,
) {
    var commands by remember { mutableStateOf(store.listCommands()) }
    var newLabel by remember { mutableStateOf("") }
    var newCommand by remember { mutableStateOf("") }
    // null = Ambos modos.
    var newMode by remember { mutableStateOf<AcpMode?>(null) }
    var renameTarget by remember { mutableStateOf<SavedCommand?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Comandos guardados") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (commands.isEmpty()) {
                    Text(
                        "Sin comandos guardados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                commands.forEach { command ->
                    Column {
                        Row {
                            Text(
                                command.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                when (command.mode) {
                                    AcpMode.TERMINAL -> "Terminal"
                                    AcpMode.CHAT -> "Chat"
                                    null -> "Ambos"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            command.command,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(onClick = { renameTarget = command }) { Text("Renombrar") }
                            TextButton(
                                onClick = {
                                    store.deleteCommand(command.id)
                                    commands = store.listCommands()
                                },
                            ) { Text("Borrar") }
                        }
                    }
                }

                HorizontalDivider()

                Text("Nuevo comando", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    label = { Text("Nombre (vacío = el propio comando)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newCommand,
                    onValueChange = { newCommand = it },
                    label = { Text("Comando") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = newMode == null,
                        onClick = { newMode = null },
                        label = { Text("Ambos") },
                    )
                    FilterChip(
                        selected = newMode == AcpMode.TERMINAL,
                        onClick = { newMode = AcpMode.TERMINAL },
                        label = { Text("Terminal") },
                    )
                    FilterChip(
                        selected = newMode == AcpMode.CHAT,
                        onClick = { newMode = AcpMode.CHAT },
                        label = { Text("Chat") },
                    )
                }
                OutlinedButton(
                    onClick = {
                        store.saveCommand(
                            SavedCommand(
                                id = newProfileId(),
                                label = newLabel.trim().ifEmpty { newCommand.trim().take(40) },
                                command = newCommand.trim(),
                                mode = newMode,
                            ),
                        )
                        newLabel = ""
                        newCommand = ""
                        commands = store.listCommands()
                    },
                    enabled = newCommand.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Guardar comando")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )

    renameTarget?.let { command ->
        RenameDialog(
            initial = command.label,
            title = "Renombrar comando",
            onConfirm = { newName ->
                store.saveCommand(command.copy(label = newName))
                commands = store.listCommands()
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}
