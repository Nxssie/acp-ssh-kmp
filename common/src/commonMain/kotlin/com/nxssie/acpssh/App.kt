package com.nxssie.acpssh

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.nxssie.acpssh.profile.ConnectionProfile
import com.nxssie.acpssh.profile.ProfileStore
import com.nxssie.acpssh.session.AcpHost
import com.nxssie.acpssh.session.AcpMode
import com.nxssie.acpssh.session.ConnectStatus
import com.nxssie.acpssh.session.HasConnection
import com.nxssie.acpssh.session.PendingHostKey
import com.nxssie.acpssh.session.TerminalHost
import com.nxssie.acpssh.ui.ChatScreen
import com.nxssie.acpssh.ui.ConnectionScreen
import com.nxssie.acpssh.ui.ProfilesScreen
import com.nxssie.acpssh.ui.TerminalScreen

/** Pantallas del flujo de conexión: lista de perfiles o formulario (nuevo/edición). */
private sealed interface ConnectionUi {
    data object Profiles : ConnectionUi
    data class Form(val editing: ConnectionProfile?) : ConnectionUi
}

/**
 * Punto de entrada común: lista de perfiles guardados (Fase G) → conectar en
 * modo Terminal o Chat; el modo Chat monta los tabs de [AcpHost] (Fase I).
 * Sin perfiles guardados se entra directo al formulario.
 */
@Composable
fun App(terminalHost: TerminalHost, acpHost: AcpHost, store: ProfileStore) {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            color = MaterialTheme.colorScheme.background,
        ) {
            var mode by rememberSaveable { mutableStateOf(AcpMode.TERMINAL) }
            var screen by remember {
                mutableStateOf<ConnectionUi>(
                    if (store.listProfiles().isEmpty()) ConnectionUi.Form(null) else ConnectionUi.Profiles,
                )
            }
            val active: HasConnection = if (mode == AcpMode.TERMINAL) terminalHost else acpHost
            val connection by active.connection.collectAsState()

            fun connect(profile: ConnectionProfile) {
                val config = store.resolve(profile) ?: return
                store.setLastProfileId(profile.id)
                if (mode == AcpMode.TERMINAL) terminalHost.connect(config) else acpHost.connect(config)
            }

            when (connection.status) {
                ConnectStatus.CONNECTED -> when (mode) {
                    AcpMode.TERMINAL -> TerminalScreen(terminalHost)
                    AcpMode.CHAT -> ChatScreen(acpHost)
                }
                ConnectStatus.CONNECTING -> LoadingScreen()
                ConnectStatus.AWAITING_HOST_KEY -> HostKeyDialog(
                    pending = connection.pendingHostKey,
                    onAccept = {
                        if (mode == AcpMode.TERMINAL) terminalHost.acceptHostKey() else acpHost.acceptHostKey()
                    },
                    onReject = {
                        if (mode == AcpMode.TERMINAL) terminalHost.rejectHostKey() else acpHost.rejectHostKey()
                    },
                )
                ConnectStatus.DISCONNECTED, ConnectStatus.FAILED -> when (val s = screen) {
                    ConnectionUi.Profiles -> ProfilesScreen(
                        store = store,
                        mode = mode,
                        onModeChange = { mode = it },
                        error = connection.error,
                        onConnect = { profile -> connect(profile) },
                        onNew = { screen = ConnectionUi.Form(null) },
                        onEdit = { screen = ConnectionUi.Form(it) },
                    )
                    is ConnectionUi.Form -> ConnectionScreen(
                        editing = s.editing,
                        mode = mode,
                        onModeChange = { mode = it },
                        store = store,
                        onConnect = { profile ->
                            screen = ConnectionUi.Profiles
                            connect(profile)
                        },
                        onCancel = { screen = ConnectionUi.Profiles },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun HostKeyDialog(
    pending: PendingHostKey?,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    if (pending == null) return
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Verificar clave del host") },
        text = {
            Column {
                Text("Primera conexión a este servidor. Confirma que la huella coincide con la del servidor:")
                Text(pending.algorithm, style = MaterialTheme.typography.bodySmall)
                Text(
                    pending.fingerprint,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Rechazar") } },
    )
}
