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

/**
 * Referencia al orden cronológico real de aparición: [AcpSessionState.messages]
 * y [AcpSessionState.toolCalls] son dos listas separadas (mensajes se van
 * actualizando por chunk, tool calls por id), así que sin esto la UI no tiene
 * forma de saber en qué orden intercalarlos — solo puede mostrar "todos los
 * mensajes, luego todas las tool calls", perdiendo el orden real de llegada.
 *
 * [Msg] guarda el ÍNDICE en [AcpSessionState.messages], no el id: el id de
 * respaldo para chunks sin messageId (`"auto-agent"`/`"auto-thought"`) es fijo
 * y se reutiliza en cada turno nuevo, así que dos burbujas distintas pueden
 * compartir id — el índice sí es estable (solo se agrega al final o se muta
 * en el lugar la última entrada, nunca se reordena ni se borra).
 * [Tool] sí puede usar id: `toolCallId` es único de verdad, nunca se reusa.
 */
sealed interface TimelineRef {
    data class Msg(val index: Int) : TimelineRef
    data class Tool(val id: String) : TimelineRef
}

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
    /** Orden real de aparición de [messages]/[toolCalls], para renderizarlos intercalados. */
    val timeline: List<TimelineRef> = emptyList(),
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
                // El agente real (claude-code-acp) NUNCA manda esto en un turno en
                // vivo (verificado): solo aparece al reponer historial vía
                // `session/load`, donde es la única forma de ver qué dijo el
                // usuario en la sesión original. Si algún agente sí lo mandara en
                // vivo, se duplicaría con el eco local de onUserPrompt — riesgo
                // aceptado, no observado contra el agente real.
                is SessionUpdate.UserMessageChunk -> appendChunk(state, ChatRole.USER, update.chunk.messageId, update.chunk.content)
                is SessionUpdate.ToolCall -> state.copy(
                    toolCalls = state.toolCalls + update.toolCall.toUi(),
                    timeline = state.timeline + TimelineRef.Tool(update.toolCall.toolCallId),
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
            val id = "user-${state.messages.size}"
            state.copy(
                messages = state.messages + ChatBubble(id = id, role = ChatRole.USER, text = text),
                timeline = state.timeline + TimelineRef.Msg(state.messages.size),
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
        // El agente real (claude-code-acp) no manda messageId en absoluto en sus
        // chunks — el id de respaldo debe ser ESTABLE entre chunks de la misma
        // racha (nunca derivado de state.messages.size, que cambia con cada
        // burbuja nueva y por eso partía cada respuesta en una burbuja por chunk).
        val id = messageId ?: "auto-${role.name.lowercase()}"
        val messages = state.messages.toMutableList()
        val last = messages.lastOrNull()
        // `streaming` distingue "seguir esta racha" de "esta racha ya cerró"
        // (onTurnEnd la apaga): sin este chequeo, el id fijo de respaldo pegaría
        // el primer chunk del turno siguiente a la burbuja ya finalizada del
        // turno anterior en vez de abrir una nueva.
        val isNewBubble = last == null || last.role != role || last.id != id || !last.streaming
        if (isNewBubble) {
            messages.add(ChatBubble(id = id, role = role, text = text, streaming = true))
        } else {
            messages[messages.size - 1] = last.copy(text = last.text + text, streaming = true)
        }
        return state.copy(
            messages = messages,
            timeline = if (isNewBubble) state.timeline + TimelineRef.Msg(messages.size - 1) else state.timeline,
        )
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
