package com.nxssie.acpssh

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.nxssie.acpssh.session.ConnectStatus
import com.nxssie.acpssh.session.PendingHostKey
import com.nxssie.acpssh.session.TerminalHost
import com.nxssie.acpssh.ui.ConnectionScreen
import com.nxssie.acpssh.ui.TerminalScreen

@Composable
fun App(host: TerminalHost) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val connection by host.connection.collectAsState()
        when (connection.status) {
            ConnectStatus.CONNECTED -> TerminalScreen(host)
            ConnectStatus.CONNECTING -> LoadingScreen()
            ConnectStatus.AWAITING_HOST_KEY -> HostKeyDialog(host, connection.pendingHostKey)
            ConnectStatus.DISCONNECTED, ConnectStatus.FAILED -> ConnectionScreen(
                initial = host.loadLastConfig(),
                error = connection.error,
                onConnect = host::connect,
            )
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
private fun HostKeyDialog(host: TerminalHost, pending: PendingHostKey?) {
    if (pending == null) return
    AlertDialog(
        onDismissRequest = host::rejectHostKey,
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
        confirmButton = { TextButton(onClick = host::acceptHostKey) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = host::rejectHostKey) { Text("Rechazar") } },
    )
}
