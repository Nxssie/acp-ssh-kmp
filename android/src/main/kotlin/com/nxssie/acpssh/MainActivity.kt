package com.nxssie.acpssh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    private lateinit var terminalHost: AndroidSshTerminalHost
    private lateinit var acpHost: AndroidAcpHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        terminalHost = AndroidSshTerminalHost(applicationContext)
        acpHost = AndroidAcpHost(applicationContext)
        setContent {
            App(terminalHost, acpHost)
        }
    }

    override fun onDestroy() {
        terminalHost.disconnect()
        acpHost.disconnect()
        super.onDestroy()
    }
}
