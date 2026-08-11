package com.nxssie.acpssh.session

import com.nxssie.acpssh.acp.PermissionOutcome
import com.nxssie.acpssh.acp.PermissionRequest
import kotlinx.coroutines.flow.StateFlow

/** Modo de sesión seleccionable desde la pantalla de conexión. */
enum class AcpMode { TERMINAL, CHAT }

/** Lo común a [TerminalHost] y [AcpHost]: el estado de la conexión SSH. */
interface HasConnection {
    val connection: StateFlow<ConnectionState>
}

/**
 * Contrato del cliente de chat ACP (Fase H): una conexión SSH y N tabs de
 * chat, cada uno con su proceso de agente remoto (ver [AcpSessionManager]).
 * La UI (ChatScreen) nunca depende de SSHJ.
 *
 * Las acciones de prompt/permiso/cancelar/toggle operan sobre el tab activo
 * ([activeTabId]); las peticiones de permiso de tabs en background se señalan
 * con badge y se responden al activar ese tab.
 */
interface AcpHost : HasConnection {
    /** Tabs abiertos, en orden de apertura. */
    val tabs: StateFlow<List<AcpTabState>>
    val activeTabId: StateFlow<String?>

    /** Tope de tabs simultáneos (decisión cerrada #3: configurable, default 5). */
    val maxTabs: Int

    fun connect(config: TerminalConfig)
    fun acceptHostKey()
    fun rejectHostKey()

    /** Abre un tab nuevo (mismo perfil que el activo, decisión cerrada #1). */
    fun openTab()

    /** Cierra el tab; el proceso remoto sigue vivo (decisión cerrada #2). */
    fun closeTab(tabId: String)

    /** Cierra el tab y termina el agente remoto (acción explícita). */
    fun killTabAgent(tabId: String)

    fun selectTab(tabId: String)

    /** Envía un prompt en el tab activo (queda busy hasta el fin de turno). */
    fun sendPrompt(text: String)

    /** Responde al `session/request_permission` pendiente del tab activo. */
    fun respondPermission(request: PermissionRequest, outcome: PermissionOutcome)

    /** Expande/colapsa la tarjeta de una tool call en el tab activo. */
    fun toggleToolCall(id: String)

    /** Cancela el turno en curso del tab activo (notificación `session/cancel`). */
    fun cancelTurn()

    fun disconnect()

    /** Último barrido de [refreshRemoteSessions] sobre el runDir base del perfil activo. */
    val remoteSessions: StateFlow<RemoteSessionsUi>

    /** Repuebla [remoteSessions]: hace visibles también los runDirs que este dispositivo no recuerda. */
    fun refreshRemoteSessions()

    /** Adjunta un tab nuevo a un runDir vivo del host que este dispositivo no recuerda. */
    fun attachRemoteSession(dirName: String)

    /** Termina un runDir del host, esté o no abierto como tab aquí. */
    fun killRemoteSession(dirName: String)

    /** Cambia un config option (p. ej. modelo/thinking) del tab activo. */
    fun setConfigOption(configId: String, value: String)

    /** Cambia el modelo activo del tab activo (mecanismo UNSTABLE de `claude-code-acp`). */
    fun setModel(modelId: String)
}
