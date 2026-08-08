package com.nxssie.acpssh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    private lateinit var host: AndroidSshTerminalHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        host = AndroidSshTerminalHost(applicationContext)
        setContent {
            App(host)
        }
    }

    override fun onDestroy() {
        host.disconnect()
        super.onDestroy()
    }
}
