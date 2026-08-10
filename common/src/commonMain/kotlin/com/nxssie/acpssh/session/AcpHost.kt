package com.nxssie.acpssh.session

import com.nxssie.acpssh.acp.PermissionOutcome
import com.nxssie.acpssh.acp.PermissionRequest
import kotlinx.coroutines.flow.StateFlow

/** Modo de sesión seleccionable desde la pantalla de conexión. */
enum class AcpMode { TERMINAL, CHAT }

/** Lo común a [TerminalHost] y [AcpHost]: el estado de la conexión SSH y la última config. */
interface HasConnection {
    val connection: StateFlow<ConnectionState>
    fun loadLastConfig(): TerminalConfig?
}

/**
 * Contrato del cliente de chat ACP, hermano de [TerminalHost]: mismo patrón
 * (estados de conexión + TOFU), pero hablando ACP por `exec` en vez de abrir
 * un shell con PTY. La UI (ChatScreen) nunca depende de SSHJ.
 */
interface AcpHost : HasConnection {
    /** Estado de la sesión de chat (mensajes, tool calls, permiso pendiente…). */
    val session: StateFlow<AcpSessionState>

    fun connect(config: TerminalConfig)
    fun acceptHostKey()
    fun rejectHostKey()

    /** Envía un prompt y queda en [AcpSessionState.busy] hasta el fin de turno. */
    fun sendPrompt(text: String)

    /** Responde a un `session/request_permission` pendiente. */
    fun respondPermission(request: PermissionRequest, outcome: PermissionOutcome)

    /** Expande/colapsa la tarjeta de una tool call en el chat. */
    fun toggleToolCall(id: String)

    /** Cancela el turno en curso (notificación `session/cancel`). */
    fun cancelTurn()

    fun disconnect()
    override fun loadLastConfig(): TerminalConfig?
}
