package com.nxssie.acpssh

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.nxssie.acpssh.update.UpdateGate

class MainActivity : ComponentActivity() {

    private lateinit var profileStore: SecureStoreProfileStore

    // Estado Compose (no un simple var): setContent necesita recomponer en
    // cuanto el bind resuelve, y eso solo pasa observando un State.
    private var boundService by mutableStateOf<ConnectionService?>(null)

    // Tab pedido por una notificación (AcpNotifier) que llegó antes de que el
    // bind del servicio resolviera — se aplica en cuanto boundService deja
    // de ser null, ver onCreate/onNewIntent.
    private var pendingSelectTabId: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as ConnectionService.LocalBinder).service
            boundService = service
            pendingSelectTabId?.let { service.acpHost.selectTab(it) }
            pendingSelectTabId = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    // No-op en el callback: si el usuario lo rechaza, AcpNotifier ya
    // comprueba `areNotificationsEnabled()` antes de cada notify() y se
    // limita a no mostrarla — no hace falta ningún estado aquí.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        profileStore = SecureStoreProfileStore(applicationContext)

        // startService (no solo bind): ConnectionService sobrevive a que esta
        // Activity muera (rotación, "quitar de recientes") mientras el
        // proceso siga vivo — sin esto, cerrar la Activity mataba la
        // conexión igual que antes, solo que ahora con una capa de más.
        val intent = Intent(this, ConnectionService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        selectTabFrom(intent = getIntent())
        setContent {
            UpdateGate(profileStore) { forceDarkTheme ->
                val service = boundService
                if (service == null) {
                    Box(Modifier.fillMaxSize()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                } else {
                    App(service.terminalHost, service.acpHost, profileStore, forceDarkTheme)
                }
            }
        }
    }

    // launchMode="singleTop" (necesario para que tocar la notificación de
    // AcpNotifier reuse la Activity ya viva en vez de crear una segunda por
    // encima): sin esto, onCreate no vuelve a correr al tocarla y el tab
    // nunca se seleccionaría.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        selectTabFrom(intent)
    }

    private fun selectTabFrom(intent: Intent) {
        val tabId = intent.getStringExtra(EXTRA_SELECT_TAB_ID) ?: return
        val service = boundService
        if (service != null) service.acpHost.selectTab(tabId) else pendingSelectTabId = tabId
    }

    override fun onDestroy() {
        // Deliberadamente NO se llama a terminalHost.disconnect()/acpHost.disconnect()
        // aquí: ConnectionService sigue vivo (startService, no solo bind) y
        // sostiene la conexión aunque esta Activity muera — ese es el punto
        // de todo este archivo. "Salir" (el botón de la UI) es la única vía
        // de desconexión real, y llama a host.disconnect() directamente.
        unbindService(serviceConnection)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SELECT_TAB_ID = "select_tab_id"
    }
}
