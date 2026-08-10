package com.nxssie.acpssh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxssie.acpssh.crypto.generateEd25519SshKey
import com.nxssie.acpssh.io.rememberPemExporter
import com.nxssie.acpssh.io.rememberPemImporter
import com.nxssie.acpssh.session.AcpMode
import com.nxssie.acpssh.session.TerminalConfig

@Composable
fun ConnectionScreen(
    initial: TerminalConfig?,
    error: String?,
    mode: AcpMode,
    onModeChange: (AcpMode) -> Unit,
    onConnect: (TerminalConfig) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(initial?.host ?: "") }
    var port by rememberSaveable { mutableStateOf((initial?.port ?: 22).toString()) }
    var username by rememberSaveable { mutableStateOf(initial?.username ?: "") }
    var pem by rememberSaveable { mutableStateOf(initial?.privateKeyPem ?: "") }
    var command by rememberSaveable {
        mutableStateOf(initial?.remoteCommand ?: if (mode == AcpMode.TERMINAL) "tmux new -As claude-terminal" else "claude-code-acp")
    }
    var generatedPublicKey by rememberSaveable { mutableStateOf(initial?.publicKeyLine) }

    val exportPem = rememberPemExporter()
    val importPem = rememberPemImporter { imported ->
        if (imported != pem) generatedPublicKey = null
        pem = imported
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Conexión SSH", style = MaterialTheme.typography.titleLarge)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = mode == AcpMode.TERMINAL,
                onClick = { onModeChange(AcpMode.TERMINAL) },
                label = { Text("Terminal") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = mode == AcpMode.CHAT,
                onClick = { onModeChange(AcpMode.CHAT) },
                label = { Text("Chat ACP") },
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Puerto") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Usuario") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pem,
            onValueChange = {
                // Si el usuario pega/edita otra clave a mano, la pública mostrada
                // (de la última generada) ya no corresponde: se oculta.
                if (it != pem) generatedPublicKey = null
                pem = it
            },
            label = { Text("Clave privada (PEM)") },
            placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY----- …") },
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp),
        )
        OutlinedButton(
            onClick = {
                val generated = generateEd25519SshKey(comment = "acp-ssh-kmp")
                pem = generated.privateKeyPem
                generatedPublicKey = generated.publicKeyLine
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Generar nueva clave Ed25519")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = importPem,
                modifier = Modifier.weight(1f),
            ) {
                Text("Importar .pem")
            }
            OutlinedButton(
                onClick = { exportPem("acp-ssh-kmp.pem", pem) },
                enabled = pem.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Exportar .pem")
            }
        }
        val publicKey = generatedPublicKey
        if (publicKey != null) {
            Text(
                "Clave pública (añádela a ~/.ssh/authorized_keys en el servidor):",
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionContainer {
                Text(
                    publicKey,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = {
                Text(
                    if (mode == AcpMode.TERMINAL) "Comando remoto (vacío = shell)"
                    else "Comando de arranque del agente ACP (remoto)",
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                onConnect(
                    TerminalConfig(
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 22,
                        username = username.trim(),
                        privateKeyPem = pem.trim(),
                        remoteCommand = command.trim().ifEmpty { null },
                        publicKeyLine = generatedPublicKey,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Conectar")
        }
    }
}
