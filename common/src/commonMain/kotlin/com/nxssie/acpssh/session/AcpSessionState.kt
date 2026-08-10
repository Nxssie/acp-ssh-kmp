package com.nxssie.acpssh.session

import com.nxssie.acpssh.acp.AcpPlan
import com.nxssie.acpssh.acp.AcpPrettyJson
import com.nxssie.acpssh.acp.AcpToolCall
import com.nxssie.acpssh.acp.AcpToolCallUpdate
import com.nxssie.acpssh.acp.ContentBlock
import com.nxssie.acpssh.acp.PermissionRequest
import com.nxssie.acpssh.acp.SessionUpdate
import com.nxssie.acpssh.acp.ToolCallContent
import com.nxssie.acpssh.acp.ToolCallStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

enum class ChatRole { USER, AGENT, THOUGHT }

/** Una burbuja del chat: mensaje del usuario o bloque de texto del agente. */
data class ChatBubble(
    val id: String,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
)

data class DiffUi(val path: String, val oldText: String?, val newText: String)

/** Tarjeta de tool call: nombre, estado y contenido (input/output/diffs) colapsable. */
data class ToolCallUi(
    val id: String,
    val title: String,
    val kind: String?,
    val status: String?,
    val input: String?,
    val output: String?,
    val diffs: List<DiffUi> = emptyList(),
    val expanded: Boolean = false,
)

data class PlanEntryUi(val content: String, val status: String?)

/** `session/request_permission` pendiente de respuesta del usuario. */
data class PermissionUi(
    val request: PermissionRequest,
    val title: String,
    val kind: String?,
)

data class AcpSessionState(
    val sessionId: String? = null,
    val agentName: String? = null,
    val messages: List<ChatBubble> = emptyList(),
    val toolCalls: List<ToolCallUi> = emptyList(),
    val plan: List<PlanEntryUi> = emptyList(),
    val pendingPermission: PermissionUi? = null,
    /** Un turno (prompt) en vuelo: el input se bloquea y se muestra cancelar. */
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Reducer puro de la sesión de chat: traduce las [SessionUpdate] del agente y
 * los eventos locales (prompt enviado, fin de turno) a [AcpSessionState].
 * Vive en commonMain sin depender de la UI ni del transporte, testeable en
 * commonTest con actualizaciones sintéticas.
 */
class AcpSessionStore {

    private val _state = MutableStateFlow(AcpSessionState())
    val state: StateFlow<AcpSessionState> = _state

    fun onSessionStarted(agentName: String?, sessionId: String) {
        _state.update { it.copy(agentName = agentName, sessionId = sessionId, error = null) }
    }

    fun onUpdate(update: SessionUpdate) {
        _state.update { state ->
            when (update) {
                is SessionUpdate.AgentMessageChunk -> appendChunk(state, ChatRole.AGENT, update.chunk.messageId, update.chunk.content)
                is SessionUpdate.AgentThoughtChunk -> appendChunk(state, ChatRole.THOUGHT, update.chunk.messageId, update.chunk.content)
                // El eco del usuario lo añade localmente onUserPrompt; ignorar
                // el del agente evita burbujas duplicadas.
                is SessionUpdate.UserMessageChunk -> state
                is SessionUpdate.ToolCall -> state.copy(
                    toolCalls = state.toolCalls + update.toolCall.toUi(),
                )
                is SessionUpdate.ToolCallUpdate -> mergeToolCall(state, update.toolCallUpdate)
                is SessionUpdate.Plan -> state.copy(
                    plan = update.plan.entries.map { PlanEntryUi(it.content, it.status) },
                )
                // Modo, configuración, comandos disponibles, uso: la v1 del
                // chat no los renderiza; se ignoran sin romper la sesión.
                else -> state
            }
        }
    }

    fun onUserPrompt(text: String) {
        _state.update { state ->
            state.copy(
                messages = state.messages + ChatBubble(
                    id = "user-${state.messages.size}",
                    role = ChatRole.USER,
                    text = text,
                ),
                busy = true,
                error = null,
            )
        }
    }

    /** Fin de turno: deja de marcar el último mensaje del agente como streaming. */
    fun onTurnEnd(error: String? = null) {
        _state.update { state ->
            state.copy(
                busy = false,
                error = error,
                messages = state.messages.map { it.copy(streaming = false) },
            )
        }
    }

    fun onPermission(pending: PermissionUi?) {
        _state.update { it.copy(pendingPermission = pending) }
    }

    fun toggleToolCall(id: String) {
        _state.update { state ->
            state.copy(
                toolCalls = state.toolCalls.map {
                    if (it.id == id) it.copy(expanded = !it.expanded) else it
                },
            )
        }
    }

    fun onError(message: String) {
        _state.update { it.copy(busy = false, error = message) }
    }

    fun reset() {
        _state.value = AcpSessionState()
    }

    private fun appendChunk(
        state: AcpSessionState,
        role: ChatRole,
        messageId: String?,
        content: ContentBlock?,
    ): AcpSessionState {
        val text = (content as? ContentBlock.Text)?.text ?: return state
        if (text.isEmpty()) return state
        val id = messageId ?: "auto-${role.name.lowercase()}-${state.messages.size}"
        val messages = state.messages.toMutableList()
        val last = messages.lastOrNull()
        if (last != null && last.role == role && last.id == id) {
            messages[messages.size - 1] = last.copy(text = last.text + text, streaming = true)
        } else {
            messages.add(ChatBubble(id = id, role = role, text = text, streaming = true))
        }
        return state.copy(messages = messages)
    }

    private fun mergeToolCall(state: AcpSessionState, update: AcpToolCallUpdate): AcpSessionState {
        val existing = state.toolCalls.firstOrNull { it.id == update.toolCallId } ?: return state
        val merged = existing.copy(
            title = update.title ?: existing.title,
            kind = update.kind ?: existing.kind,
            status = update.status ?: existing.status,
            input = update.rawInput?.pretty() ?: existing.input,
            output = update.rawOutput?.pretty() ?: existing.output,
            diffs = update.content.mapNotNull { (it as? ToolCallContent.Diff)?.let { d -> DiffUi(d.path, d.oldText, d.newText) } }
                .ifEmpty { existing.diffs },
        )
        return state.copy(toolCalls = state.toolCalls.map { if (it.id == update.toolCallId) merged else it })
    }

    private fun AcpToolCall.toUi() = ToolCallUi(
        id = toolCallId,
        title = title,
        kind = kind,
        status = status ?: ToolCallStatus.PENDING,
        input = rawInput?.pretty(),
        output = rawOutput?.pretty(),
        diffs = content.mapNotNull { (it as? ToolCallContent.Diff)?.let { d -> DiffUi(d.path, d.oldText, d.newText) } },
    )

    private fun JsonElement.pretty(): String? = AcpPrettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), this)
}
