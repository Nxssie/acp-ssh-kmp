package com.nxssie.acpssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nxssie.acpssh.session.ConnectStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Mantiene [terminalHost]/[acpHost] vivos independientemente del ciclo de
 * vida de [MainActivity] — sin esto, en background Android aplica
 * restricciones de red (Doze/App Standby) a procesos normales que hacían
 * fallar varios intentos del auto-reconnect solo por no estar en foreground
 * (visible como "ya va por el intento 4/5" al reabrir la app tras
 * backgroundearla un rato: los 3 primeros fallaron por la restricción, no
 * por la red real).
 *
 * Sube a foreground (con notificación, tipo `dataSync`: la conexión SSH/ACP
 * es exactamente eso, transferencia de datos con un host remoto — no
 * `connectedDevice`, que Android exige condicionar a permisos de
 * Bluetooth/USB/NFC que esta app no usa ni necesita) solo mientras a) tiene
 * una conexión activa y b) el usuario no la desconectó a mano; baja a
 * background (sin notificación) el resto del tiempo. `dataSync` tiene un
 * tope de ejecución de 6h por ventana de 24h en Android 15+ — aceptable
 * para "dejar una tarea corriendo un rato", no pensado para días sin mirar
 * el móvil.
 *
 * Se arranca con `startService` (no solo `bindService`): sobrevive a que la
 * Activity se destruya (rotación, "quitar de recientes") mientras el
 * proceso siga vivo — justo el caso que antes perdía la conexión al salir
 * de la app.
 */
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    lateinit var terminalHost: AndroidSshTerminalHost
        private set
    lateinit var acpHost: AndroidAcpHost
        private set

    inner class LocalBinder : Binder() {
        val service: ConnectionService get() = this@ConnectionService
    }

    override fun onCreate() {
        super.onCreate()
        val profileStore = SecureStoreProfileStore(applicationContext)
        terminalHost = AndroidSshTerminalHost(applicationContext)
        acpHost = AndroidAcpHost(applicationContext, profileStore)
        createNotificationChannel()
        watchJob = scope.launch {
            combine(terminalHost.connection, acpHost.connection) { t, a -> t.status to a.status }
                .collect { (terminalStatus, acpStatus) ->
                    val active = isActive(terminalStatus) || isActive(acpStatus)
                    if (active) startForegroundNotification() else stopForegroundNotification()
                }
        }
    }

    /** "Activo" incluye RECONNECTING: es justo cuando más falta hace estar en foreground. */
    private fun isActive(status: ConnectStatus): Boolean = when (status) {
        ConnectStatus.CONNECTED, ConnectStatus.CONNECTING,
        ConnectStatus.RECONNECTING, ConnectStatus.AWAITING_HOST_KEY,
        -> true
        ConnectStatus.DISCONNECTED, ConnectStatus.FAILED -> false
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    /**
     * No detiene el servicio ni desconecta los hosts al perder el último
     * cliente vinculado (MainActivity.onDestroy) — ese es justo el punto:
     * la Activity puede morir (rotación, quitar de recientes) sin que la
     * conexión muera con ella. Solo `disconnect()` explícito de cada host
     * (botón "Salir") corta la conexión de verdad.
     */
    override fun onUnbind(intent: Intent?): Boolean = true

    override fun onDestroy() {
        watchJob?.cancel()
        runCatching { terminalHost.disconnect() }
        runCatching { acpHost.disconnect() }
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Conexión activa",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("nxssie-terminal conectado")
            .setContentText("Manteniendo la sesión activa en segundo plano")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    // ServiceCompat.startForeground ya rama por versión de API por dentro
    // (el tipo solo se usa de API 29 en adelante; en las anteriores lo ignora
    // y llama al startForeground de siempre) — no hace falta bifurcar aquí.
    private fun startForegroundNotification() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun stopForegroundNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val CHANNEL_ID = "connection-active"
        private const val NOTIFICATION_ID = 1002
    }
}
