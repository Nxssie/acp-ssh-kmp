package com.nxssie.acpssh

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    private lateinit var host: AndroidSshTerminalHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App UI is always dark regardless of system theme, so force light (white) system bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
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
