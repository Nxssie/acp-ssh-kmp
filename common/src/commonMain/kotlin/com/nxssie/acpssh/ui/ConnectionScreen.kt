package com.nxssie.acpssh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nxssie.acpssh.profile.ChatAgentKind
import com.nxssie.acpssh.profile.ConnectionProfile
import com.nxssie.acpssh.profile.ProfileStore
import com.nxssie.acpssh.profile.SavedCommand
import com.nxssie.acpssh.profile.newProfileId
import com.nxssie.acpssh.session.AcpMode

/**
 * Formulario de conexión (Fase G): crea o edita un [ConnectionProfile]. La
 * clave se elige de una lista gestionada (nunca se pinta el PEM). El tipo de
 * conexión (Terminal/Chat) se fija aquí, por perfil ([ConnectionProfile.mode])
 * — no hay un selector global que lo sobreescriba después. En Chat, Claude
 * Agent y Pi Agent están siempre disponibles como primera opción (sin pasar
 * por "Gestionar comandos"); el comando custom sigue existiendo como vía
 * avanzada y, si se elige uno, manda sobre el agente seleccionado. El comando
 * tiene un default explícito por modo ("shell (default)" en Terminal, "Claude
 * Agent (default)"/"Pi Agent (default)" en Chat, decisión cerrada #5) en vez
 * de ir precargado.
 *
 * Al conectar se guarda el perfil (upsert por id) y se marca como último usado.
 */
@Composable
fun ConnectionScreen(
    editing: ConnectionProfile?,
    store: ProfileStore,
    onConnect: (ConnectionProfile) -> Unit,
    onCancel: () -> Unit,
) {
    // El estado del formulario se reinicia al cambiar de perfil editado.
    key(editing?.id ?: "new") {
        ConnectionForm(editing, store, onConnect, onCancel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionForm(
    editing: ConnectionProfile?,
    store: ProfileStore,
    onConnect: (ConnectionProfile) -> Unit,
    onCancel: () -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(editing?.label ?: "") }
    var host by rememberSaveable { mutableStateOf(editing?.host ?: "") }
    var port by rememberSaveable { mutableStateOf((editing?.port ?: 22).toString()) }
    var username by rememberSaveable { mutableStateOf(editing?.username ?: "") }
    var selectedKeyId by rememberSaveable { mutableStateOf(editing?.keyId) }
    // Solo importa como valor por defecto para un perfil NUEVO — al editar uno
    // existente siempre parte de su mode ya guardado.
    var mode by rememberSaveable { mutableStateOf(editing?.mode ?: (store.loadLastMode() ?: AcpMode.TERMINAL)) }
    var selectedCommandId by rememberSaveable { mutableStateOf(editing?.commandId) }
    var selectedChatAgentKind by rememberSaveable { mutableStateOf(editing?.chatAgentKind) }
    var showAllCommands by rememberSaveable { mutableStateOf(false) }

    var keys by remember { mutableStateOf(store.listKeys()) }
    var commands by remember { mutableStateOf(store.listCommands()) }
    var showKeyManager by remember { mutableStateOf(false) }
    var showCommandManager by remember { mutableStateOf(false) }

    // Si la selección ya no existe (borrada) o no hay, cae a la primera clave.
    val effectiveKeyId = selectedKeyId?.takeIf { id -> keys.any { it.id == id } }
        ?: keys.firstOrNull()?.id

    val effectiveChatAgentKind = selectedChatAgentKind ?: ChatAgentKind.CLAUDE
    val defaultCommandLabel = if (mode == AcpMode.TERMINAL) "shell (default)" else "${effectiveChatAgentKind.label} (default)"
    val visibleCommands = commands.filter { showAllCommands || it.mode == null || it.mode == mode }
    val effectiveCommand = selectedCommandId?.let { id -> commands.firstOrNull { it.id == id } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (editing == null) "Nueva conexión" else "Editar conexión",
            style = MaterialTheme.typography.titleLarge,
        )

        ModeChips(mode) { mode = it }

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Nombre (vacío = usuario@host)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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

        KeySelector(
            keys = keys,
            selectedKeyId = effectiveKeyId,
            onSelect = { selectedKeyId = it },
            onManage = { showKeyManager = true },
        )

        if (mode == AcpMode.CHAT) {
            ChatAgentSelector(
                selected = effectiveChatAgentKind,
                onSelect = {
                    selectedChatAgentKind = it
                    // Un comando custom heredado de antes de que existiera este
                    // selector (o de una elección avanzada previa) manda sobre
                    // chatAgentKind en la resolución — sin limpiarlo aquí, elegir
                    // un chip no cambiaría el agente que realmente se lanza.
                    selectedCommandId = null
                },
            )
        }

        CommandSelector(
            label = if (mode == AcpMode.CHAT) "Comando remoto (avanzado)" else "Comando remoto",
            commands = visibleCommands,
            hiddenCount = commands.size - visibleCommands.size,
            selected = effectiveCommand,
            defaultLabel = defaultCommandLabel,
            showAll = showAllCommands,
            onToggleShowAll = { showAllCommands = it },
            onSelect = { selectedCommandId = it?.id },
            onManage = { showCommandManager = true },
        )

        Button(
            onClick = {
                val keyId = effectiveKeyId ?: return@Button
                val profile = ConnectionProfile(
                    id = editing?.id ?: newProfileId(),
                    label = label.trim().ifEmpty { "${username.trim()}@${host.trim()}" },
                    host = host.trim(),
                    port = port.toIntOrNull() ?: 22,
                    username = username.trim(),
                    keyId = keyId,
                    mode = mode,
                    commandId = effectiveCommand?.id,
                    chatAgentKind = selectedChatAgentKind,
                    acpRunDir = editing?.acpRunDir,
                    acpCwd = editing?.acpCwd,
                )
                store.saveProfile(profile)
                store.setLastProfileId(profile.id)
                store.setLastMode(mode)
                onConnect(profile)
            },
            enabled = effectiveKeyId != null && host.isNotBlank() && username.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Guardar y conectar")
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Volver")
        }
    }

    if (showKeyManager) {
        KeyManagerDialog(
            store = store,
            onDismiss = {
                showKeyManager = false
                keys = store.listKeys()
            },
        )
    }
    if (showCommandManager) {
        CommandManagerDialog(
            store = store,
            mode = mode,
            onDismiss = {
                showCommandManager = false
                commands = store.listCommands()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeySelector(
    keys: List<com.nxssie.acpssh.profile.SavedKey>,
    selectedKeyId: String?,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = keys.firstOrNull { it.id == selectedKeyId }?.label
                ?: "Sin claves — crea o importa una",
            onValueChange = {},
            readOnly = true,
            label = { Text("Clave privada") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            keys.forEach { key ->
                DropdownMenuItem(
                    text = { Text(key.label) },
                    onClick = {
                        onSelect(key.id)
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Gestionar claves…") },
                onClick = {
                    expanded = false
                    onManage()
                },
            )
        }
    }
}

/** Selector Terminal | Chat: fija el tipo de ESTE perfil, no un modo global. */
@Composable
private fun ModeChips(mode: AcpMode, onModeChange: (AcpMode) -> Unit) {
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
}

/** Selector de primer nivel en Chat: Claude Agent / Pi Agent, sin pasar por "Gestionar comandos". */
@Composable
private fun ChatAgentSelector(selected: ChatAgentKind, onSelect: (ChatAgentKind) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChatAgentKind.entries.forEach { kind ->
            FilterChip(
                selected = selected == kind,
                onClick = { onSelect(kind) },
                label = { Text(kind.label) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandSelector(
    label: String,
    commands: List<SavedCommand>,
    hiddenCount: Int,
    selected: SavedCommand?,
    defaultLabel: String,
    showAll: Boolean,
    onToggleShowAll: (Boolean) -> Unit,
    onSelect: (SavedCommand?) -> Unit,
    onManage: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.label ?: defaultLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(defaultLabel) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            commands.forEach { command ->
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(command.label, modifier = Modifier.weight(1f))
                            command.mode?.let {
                                Text(
                                    if (it == AcpMode.TERMINAL) "Terminal" else "Chat",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(command)
                        expanded = false
                    },
                )
            }
            if (hiddenCount > 0 || showAll) {
                DropdownMenuItem(
                    text = { Text(if (showAll) "Ver solo de este modo" else "Ver todos ($hiddenCount más)") },
                    onClick = { onToggleShowAll(!showAll) },
                )
            }
            DropdownMenuItem(
                text = { Text("Gestionar comandos…") },
                onClick = {
                    expanded = false
                    onManage()
                },
            )
        }
    }
}
