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
import androidx.compose.runtime.LaunchedEffect
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
        // El fondo del Surface llega hasta el borde real de la pantalla (sin
        // windowInsetsPadding aquí): en Android, con enableEdgeToEdge(), la
        // franja de la barra de estado/navegación es transparente y se ve lo
        // que haya debajo. Si el padding fuera del Surface, ese hueco
        // quedaría en el fondo nativo de la Activity (blanco, ver
        // AndroidManifest.xml) en vez del color del tema actual — el padding
        // va en el contenido, no en el fondo.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                // El tipo de conexión (Terminal/Chat) vive en el propio perfil
                // (ConnectionProfile.mode) — no hay selector global: conectar a
                // un perfil de Terminal siempre usa terminalHost, sin importar
                // qué se hubiera elegido antes para otro perfil. activeMode solo
                // registra qué host observar mientras hay una conexión en curso;
                // el valor inicial es irrelevante hasta que connect() lo fija.
                var activeMode by rememberSaveable { mutableStateOf(AcpMode.TERMINAL) }
                var screen by remember {
                    mutableStateOf<ConnectionUi>(
                        if (store.listProfiles().isEmpty()) ConnectionUi.Form(null) else ConnectionUi.Profiles,
                    )
                }
                val active: HasConnection = if (activeMode == AcpMode.TERMINAL) terminalHost else acpHost
                val connection by active.connection.collectAsState()

                fun connect(profile: ConnectionProfile) {
                    val config = store.resolve(profile) ?: return
                    store.setLastProfileId(profile.id)
                    activeMode = profile.mode
                    if (profile.mode == AcpMode.TERMINAL) terminalHost.connect(config) else acpHost.connect(config)
                }

                // Auto-reconecta al último perfil al arrancar el proceso (Android
                // puede matarlo en background): sin esto, cada reinicio del proceso
                // vuelve siempre a la lista de perfiles y hay que tocar "Conectar" a
                // mano aunque el agente remoto siga vivo y la sesión ACP sea
                // retomable (AcpSessionManager + session/load). Solo corre una vez
                // por composición (LaunchedEffect(Unit)): no reintenta si el usuario
                // desconecta manualmente después.
                LaunchedEffect(Unit) {
                    val lastId = store.loadLastProfileId()
                    val profile = lastId?.let { id -> store.listProfiles().firstOrNull { it.id == id } }
                    if (profile != null) connect(profile)
                }

                when (connection.status) {
                    ConnectStatus.CONNECTED -> when (activeMode) {
                        AcpMode.TERMINAL -> TerminalScreen(terminalHost)
                        AcpMode.CHAT -> ChatScreen(acpHost)
                    }
                    ConnectStatus.CONNECTING -> LoadingScreen()
                    ConnectStatus.AWAITING_HOST_KEY -> HostKeyDialog(
                        pending = connection.pendingHostKey,
                        onAccept = {
                            if (activeMode == AcpMode.TERMINAL) terminalHost.acceptHostKey() else acpHost.acceptHostKey()
                        },
                        onReject = {
                            if (activeMode == AcpMode.TERMINAL) terminalHost.rejectHostKey() else acpHost.rejectHostKey()
                        },
                    )
                    ConnectStatus.DISCONNECTED, ConnectStatus.FAILED -> when (val s = screen) {
                        ConnectionUi.Profiles -> ProfilesScreen(
                            store = store,
                            error = connection.error,
                            onConnect = { profile -> connect(profile) },
                            onNew = { screen = ConnectionUi.Form(null) },
                            onEdit = { screen = ConnectionUi.Form(it) },
                        )
                        is ConnectionUi.Form -> ConnectionScreen(
                            editing = s.editing,
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
