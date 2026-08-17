package com.nxssie.acpssh.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.nxssie.acpssh.MainActivity
import com.nxssie.acpssh.R

/**
 * Notificación best-effort para "el agente ACP de un tab necesita tu
 * respuesta" (`session/request_permission` pendiente). Best-effort porque no
 * hay foreground service: cubre "el proceso sigue vivo en background pero no
 * estás mirando la pantalla" — si Android ya mató el proceso, no hay nada
 * corriendo que pueda disparar esto (ver README/AcpSessionManager sobre el
 * mismo límite ya conocido de la persistencia de sesión).
 */
class AcpNotifier(private val context: Context) {

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Permiso pendiente del agente",
                NotificationManager.IMPORTANCE_HIGH,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /**
     * `STARTED` (no `RESUMED`): basta con que la Activity sea visible
     * (multi-ventana, split-screen) para no necesitar la notificación —
     * `RESUMED` excluiría ese caso sin motivo.
     */
    private val isForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun notifyPermissionPending(tabId: String, agentName: String?, summary: String) {
        if (isForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            return
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SELECT_TAB_ID, tabId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            tabId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${agentName ?: "El agente"} necesita tu permiso")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(tabId.hashCode(), notification) }
    }

    /** El usuario ya respondió (desde este dispositivo u otra vía) o abrió el tab: retira el aviso. */
    fun cancel(tabId: String) {
        NotificationManagerCompat.from(context).cancel(tabId.hashCode())
    }

    companion object {
        private const val CHANNEL_ID = "acp-permission"
    }
}
