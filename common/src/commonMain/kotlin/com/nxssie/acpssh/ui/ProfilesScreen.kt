package com.nxssie.acpssh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nxssie.acpssh.profile.ConnectionProfile
import com.nxssie.acpssh.profile.ProfileStore
import com.nxssie.acpssh.profile.newProfileId
import com.nxssie.acpssh.session.AcpMode

/**
 * Lista de perfiles guardados (Fase G): conectar / editar / duplicar / borrar,
 * y "Nueva conexión". Un perfil cuya clave fue borrada no se puede conectar
 * (se señala en la tarjeta). El último perfil usado se marca con ★.
 */
@Composable
fun ProfilesScreen(
    store: ProfileStore,
    mode: AcpMode,
    onModeChange: (AcpMode) -> Unit,
    error: String?,
    onConnect: (ConnectionProfile) -> Unit,
    onNew: () -> Unit,
    onEdit: (ConnectionProfile) -> Unit,
) {
    var profiles by remember { mutableStateOf(store.listProfiles()) }
    val keyIds = remember(profiles) { store.listKeys().map { it.id }.toSet() }
    val lastProfileId = remember(profiles) { store.loadLastProfileId() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Conexiones", style = MaterialTheme.typography.titleLarge)

        ModeChips(mode, onModeChange)

        if (profiles.isEmpty()) {
            Text(
                "No hay conexiones guardadas. Crea la primera.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        profiles.forEach { profile ->
            ProfileCard(
                profile = profile,
                isLast = profile.id == lastProfileId,
                resolvable = profile.keyId in keyIds,
                onConnect = { onConnect(profile) },
                onEdit = { onEdit(profile) },
                onDuplicate = {
                    store.saveProfile(
                        profile.copy(id = newProfileId(), label = profile.label + " (copia)"),
                    )
                    profiles = store.listProfiles()
                },
                onDelete = {
                    store.deleteProfile(profile.id)
                    profiles = store.listProfiles()
                },
            )
        }

        Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
            Text("Nueva conexión")
        }

        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProfileCard(
    profile: ConnectionProfile,
    isLast: Boolean,
    resolvable: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (isLast) {
                    Text("★", color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "${profile.username}@${profile.host}:${profile.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!resolvable) {
                Text(
                    "⚠ La clave de este perfil fue eliminada",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onConnect, enabled = resolvable) { Text("Conectar") }
                TextButton(onClick = onEdit) { Text("Editar") }
                TextButton(onClick = onDuplicate) { Text("Duplicar") }
                TextButton(onClick = onDelete) { Text("Borrar") }
            }
        }
    }
}

/** Selector Terminal | Chat compartido por la lista de perfiles y el formulario. */
@Composable
internal fun ModeChips(mode: AcpMode, onModeChange: (AcpMode) -> Unit) {
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
