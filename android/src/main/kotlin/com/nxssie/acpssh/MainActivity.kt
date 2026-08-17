package com.nxssie.acpssh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.nxssie.acpssh.update.UpdateGate

class MainActivity : ComponentActivity() {

    private lateinit var terminalHost: AndroidSshTerminalHost
    private lateinit var acpHost: AndroidAcpHost
    private lateinit var profileStore: SecureStoreProfileStore

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
        terminalHost = AndroidSshTerminalHost(applicationContext)
        profileStore = SecureStoreProfileStore(applicationContext)
        acpHost = AndroidAcpHost(applicationContext, profileStore)
        setContent {
            UpdateGate(profileStore) { forceDarkTheme ->
                App(terminalHost, acpHost, profileStore, forceDarkTheme)
            }
        }
        selectTabFrom(intent)
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
        intent.getStringExtra(EXTRA_SELECT_TAB_ID)?.let { acpHost.selectTab(it) }
    }

    override fun onDestroy() {
        terminalHost.disconnect()
        acpHost.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SELECT_TAB_ID = "select_tab_id"
    }
}
