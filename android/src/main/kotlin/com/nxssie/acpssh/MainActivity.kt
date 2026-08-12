package com.nxssie.acpssh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nxssie.acpssh.update.UpdateGate

class MainActivity : ComponentActivity() {

    private lateinit var terminalHost: AndroidSshTerminalHost
    private lateinit var acpHost: AndroidAcpHost
    private lateinit var profileStore: SecureStoreProfileStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        terminalHost = AndroidSshTerminalHost(applicationContext)
        profileStore = SecureStoreProfileStore(applicationContext)
        acpHost = AndroidAcpHost(applicationContext, profileStore)
        setContent {
            UpdateGate(profileStore) {
                App(terminalHost, acpHost, profileStore)
            }
        }
    }

    override fun onDestroy() {
        terminalHost.disconnect()
        acpHost.disconnect()
        super.onDestroy()
    }
}
