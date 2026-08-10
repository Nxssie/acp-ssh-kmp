package com.nxssie.acpssh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxssie.acpssh.crypto.generateEd25519SshKey
import com.nxssie.acpssh.io.rememberPemExporter
import com.nxssie.acpssh.io.rememberPemImporter
import com.nxssie.acpssh.profile.ProfileStore
import com.nxssie.acpssh.profile.SavedKey
import com.nxssie.acpssh.profile.newProfileId

/**
 * Gestor de claves SSH (Fase G): generar Ed25519 / importar / exportar /
 * renombrar / borrar. El PEM nunca se muestra salvo tras "Mostrar" (fricción
 * deliberada); una vez visible se puede copiar (decisión cerrada #4). La
 * línea pública solo existe para claves generadas en la app.
 */
@Composable
fun KeyManagerDialog(
    store: ProfileStore,
    onDismiss: () -> Unit,
) {
    var keys by remember { mutableStateOf(store.listKeys()) }
    var revealTarget by remember { mutableStateOf<SavedKey?>(null) }
    var publicTarget by remember { mutableStateOf<SavedKey?>(null) }
    var renameTarget by remember { mutableStateOf<SavedKey?>(null) }

    val exportPem = rememberPemExporter()
    val importPem = rememberPemImporter { pem ->
        store.saveKey(
            SavedKey(
                id = newProfileId(),
                label = "Importada ${keys.size + 1}",
                privateKeyPem = pem.trim(),
            ),
        )
        keys = store.listKeys()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Claves SSH") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (keys.isEmpty()) {
                    Text(
                        "Sin claves guardadas. Genera una nueva o importa un .pem.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                keys.forEach { key ->
                    KeyRow(
                        key = key,
                        onReveal = { revealTarget = key },
                        onShowPublic = { publicTarget = key },
                        onExport = { exportPem("${key.label}.pem", key.privateKeyPem) },
                        onRename = { renameTarget = key },
                        onDelete = {
                            store.deleteKey(key.id)
                            keys = store.listKeys()
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val generated = generateEd25519SshKey(comment = "acp-ssh-kmp")
                            store.saveKey(
                                SavedKey(
                                    id = newProfileId(),
                                    label = "Ed25519 ${keys.size + 1}",
                                    privateKeyPem = generated.privateKeyPem,
                                    publicKeyLine = generated.publicKeyLine,
                                ),
                            )
                            keys = store.listKeys()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Generar Ed25519")
                    }
                    OutlinedButton(onClick = importPem, modifier = Modifier.weight(1f)) {
                        Text("Importar .pem")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )

    revealTarget?.let { key ->
        SecretTextDialog(
            title = "Clave privada — ${key.label}",
            text = key.privateKeyPem,
            onDismiss = { revealTarget = null },
        )
    }
    publicTarget?.let { key ->
        val public = key.publicKeyLine
        if (public != null) {
            SecretTextDialog(
                title = "Clave pública — ${key.label}",
                text = public,
                note = "Añádela a ~/.ssh/authorized_keys en el servidor.",
                onDismiss = { publicTarget = null },
            )
        } else {
            AlertDialog(
                onDismissRequest = { publicTarget = null },
                title = { Text("Clave pública — ${key.label}") },
                text = {
                    Text(
                        "Esta clave se importó sin línea pública y no se muestra. " +
                            "Genera una clave en la app para obtenerla.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                confirmButton = { TextButton(onClick = { publicTarget = null }) { Text("Cerrar") } },
            )
        }
    }
    renameTarget?.let { key ->
        RenameDialog(
            initial = key.label,
            title = "Renombrar clave",
            onConfirm = { newLabel ->
                store.saveKey(key.copy(label = newLabel))
                keys = store.listKeys()
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyRow(
    key: SavedKey,
    onReveal: () -> Unit,
    onShowPublic: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        Text(key.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = onReveal) { Text("Mostrar") }
            TextButton(onClick = onShowPublic) { Text("Pública") }
            TextButton(onClick = onExport) { Text("Exportar") }
            TextButton(onClick = onRename) { Text("Renombrar") }
            TextButton(onClick = onDelete) { Text("Borrar") }
        }
    }
}

/** Texto secreto/copiable: seleccionable y con copia explícita al portapapeles. */
@Composable
private fun SecretTextDialog(
    title: String,
    text: String,
    note: String? = null,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                SelectionContainer {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copiar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

/** Diálogo de renombrado compartido por claves y comandos. */
@Composable
internal fun RenameDialog(
    initial: String,
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
