package com.nxssie.acpssh.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Estado del flujo de autoupdate mostrado sobre el contenido normal de la app. */
private sealed interface UpdateState {
    data object Hidden : UpdateState
    data class Available(val release: UpdateChecker.LatestRelease) : UpdateState
    data class NeedsInstallPermission(val release: UpdateChecker.LatestRelease) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Envuelve [content]: al componerse, consulta GitHub Releases (Fase K) y, si
 * hay una versión más nueva que la instalada, la ofrece encima como diálogo
 * — sin bloquear el resto de la UI mientras tanto (Hidden es el caso común).
 */
@Composable
fun UpdateGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Hidden) }

    LaunchedEffect(Unit) {
        val currentVersionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val release = UpdateChecker.checkForUpdate(currentVersionCode)
        if (release != null) state = UpdateState.Available(release)
    }

    fun startDownload(release: UpdateChecker.LatestRelease) {
        state = UpdateState.Downloading(0f)
        scope.launch {
            runCatching {
                val apk = UpdateChecker.downloadApk(context, release) { progress ->
                    state = UpdateState.Downloading(progress)
                }
                UpdateChecker.installApk(context, apk)
                state = UpdateState.Hidden
            }.onFailure {
                state = UpdateState.Failed(it.message ?: "Error desconocido")
            }
        }
    }

    content()

    when (val s = state) {
        UpdateState.Hidden -> Unit

        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { state = UpdateState.Hidden },
            title = { Text("Actualización disponible") },
            text = { Text("Hay una versión nueva (${s.release.tag}). ¿Descargarla e instalarla?") },
            confirmButton = {
                TextButton(onClick = {
                    if (context.packageManager.canRequestPackageInstalls()) {
                        startDownload(s.release)
                    } else {
                        state = UpdateState.NeedsInstallPermission(s.release)
                    }
                }) { Text("Actualizar") }
            },
            dismissButton = { TextButton(onClick = { state = UpdateState.Hidden }) { Text("Más tarde") } },
        )

        is UpdateState.NeedsInstallPermission -> AlertDialog(
            onDismissRequest = { state = UpdateState.Hidden },
            title = { Text("Permiso necesario") },
            text = { Text("Para instalar la actualización, permite \"Instalar apps desconocidas\" para esta app y vuelve a intentarlo.") },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                    state = UpdateState.Available(s.release)
                }) { Text("Ir a ajustes") }
            },
            dismissButton = { TextButton(onClick = { state = UpdateState.Hidden }) { Text("Cancelar") } },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Descargando actualización…") },
            text = {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (s.progress >= 0f) {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { state = UpdateState.Hidden },
            title = { Text("No se pudo actualizar") },
            text = { Text(s.message, color = MaterialTheme.colorScheme.error) },
            confirmButton = { TextButton(onClick = { state = UpdateState.Hidden }) { Text("Cerrar") } },
        )
    }
}
