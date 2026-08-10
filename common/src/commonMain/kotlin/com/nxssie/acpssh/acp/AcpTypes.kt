package com.nxssie.acpssh.acp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Versión de protocolo que negocia este cliente (v1, la estable del spec). */
const val ACP_PROTOCOL_VERSION: Int = 1

// --- salida: requests que manda el cliente ------------------------------------

@Serializable
data class InitializeParams(
    val protocolVersion: Int = ACP_PROTOCOL_VERSION,
    val clientCapabilities: JsonObject = buildJsonObject {
        put("fs", buildJsonObject {
            put("readTextFile", false)
            put("writeTextFile", false)
            put("listDirectory", false)
            put("glob", false)
            put("readBinaryFile", false)
        })
        put("status", buildJsonObject { put("statusUpdated", true) })
        put("sampling", buildJsonObject { put("samplingUpdated", true) })
    },
    val clientInfo: JsonObject = buildJsonObject {
        put("name", "acp-ssh-kmp")
        put("version", "0.1.0")
    },
)

@Serializable
data class NewSessionParams(
    val cwd: String,
    val mcpServers: JsonObject = buildJsonObject { },
)

@Serializable
data class PromptParams(val sessionId: String, val prompt: JsonElement)

@Serializable
data class SessionIdParams(val sessionId: String)

/** Respuesta del cliente a `session/request_permission` (outcome del spec). */
sealed interface PermissionOutcome {
    data class Selected(val optionId: String) : PermissionOutcome
    data object Cancelled : PermissionOutcome

    fun toJson(): JsonObject = when (this) {
        is Selected -> buildJsonObject {
            put("outcome", buildJsonObject {
                put("outcome", "selected")
                put("optionId", optionId)
            })
        }
        Cancelled -> buildJsonObject { put("outcome", "cancelled") }
    }
}

// --- entrada: resultados que parsea el cliente ---------------------------------

/** Resultado de `initialize` (los campos libres quedan crudos y accesibles). */
data class InitializeResult(
    val protocolVersion: Int,
    val agentName: String?,
    val agentTitle: String?,
    val agentVersion: String?,
    val agentCapabilities: JsonObject?,
)

/** Resultado de `session/new`. */
data class NewSessionResult(
    val sessionId: String,
    val modes: JsonObject?,
    val configOptions: List<JsonObject>?,
)

/** Resultado de `session/prompt` (fin de turno). */
data class PromptResult(
    val stopReason: String?,
    val usage: JsonObject?,
    val error: RpcError?,
) {
    val cancelled: Boolean get() = stopReason == "cancelled"
    val endTurn: Boolean get() = stopReason == "end_turn"
}

// --- SessionUpdate: notificaciones de la sesión --------------------------------

/**
 * Actualizaciones de sesión que emite el agente (`session/update`). Decodificadas
 * a mano con [from] en vez de un serializer polimórfico de kotlinx: el spec usa
 * un tag `sessionUpdate` + campos aplanados por variante, y los agentes pueden
 * emitir tags futuros que no conocemos — se preservan crudos en [Unknown] en
 * lugar de tumbar la sesión.
 */
sealed interface SessionUpdate {
    data class UserMessageChunk(val chunk: ContentChunk) : SessionUpdate
    data class AgentMessageChunk(val chunk: ContentChunk) : SessionUpdate
    data class AgentThoughtChunk(val chunk: ContentChunk) : SessionUpdate
    data class ToolCall(val toolCall: AcpToolCall) : SessionUpdate
    data class ToolCallUpdate(val toolCallUpdate: AcpToolCallUpdate) : SessionUpdate
    data class Plan(val plan: AcpPlan) : SessionUpdate
    data class AvailableCommandsUpdate(val commands: List<JsonObject>) : SessionUpdate
    data class CurrentModeUpdate(val modeId: String) : SessionUpdate
    data class ConfigOptionUpdate(val configOption: JsonObject) : SessionUpdate
    data class SessionInfoUpdate(val info: JsonObject) : SessionUpdate
    data class UsageUpdate(val usage: JsonObject) : SessionUpdate
    data class Unknown(val tag: String, val raw: JsonObject) : SessionUpdate

    companion object {
        fun from(update: JsonObject): SessionUpdate = when (val tag = update["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
            "user_message_chunk" -> UserMessageChunk(ContentChunk.from(update))
            "agent_message_chunk" -> AgentMessageChunk(ContentChunk.from(update))
            "agent_thought_chunk" -> AgentThoughtChunk(ContentChunk.from(update))
            "tool_call" -> ToolCall(AcpToolCall.from(update.obj("toolCall")))
            "tool_call_update" -> ToolCallUpdate(AcpToolCallUpdate.from(update.obj("toolCallUpdate")))
            "plan" -> Plan(AcpPlan.from(update.obj("plan")))
            "available_commands_update" -> AvailableCommandsUpdate(
                update["availableCommands"]?.jsonArray?.mapNotNull { it.jsonObjectOrNull() } ?: emptyList(),
            )
            "current_mode_update" -> CurrentModeUpdate(update["currentModeId"]?.jsonPrimitive?.contentOrNull ?: "")
            "config_option_update" -> ConfigOptionUpdate(update["configOption"]?.jsonObjectOrNull() ?: JsonObject(emptyMap()))
            "session_info_update" -> SessionInfoUpdate(update["info"]?.jsonObjectOrNull() ?: JsonObject(emptyMap()))
            "usage_update" -> UsageUpdate(update["usage"]?.jsonObjectOrNull() ?: JsonObject(emptyMap()))
            else -> Unknown(tag ?: "", update)
        }
    }
}

/** Un bloque de contenido (`content` de un chunk o de un prompt). */
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Unknown(val type: String, val raw: JsonObject) : ContentBlock

    companion object {
        fun from(element: JsonElement?): ContentBlock? {
            val obj = element?.jsonObjectOrNull() ?: return null
            return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> Text(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
                else -> Unknown(obj["type"]?.jsonPrimitive?.contentOrNull ?: "", obj)
            }
        }
    }
}

/** Chunk de contenido de un mensaje: un bloque + el id del mensaje que agrupa. */
data class ContentChunk(val content: ContentBlock?, val messageId: String?) {
    companion object {
        fun from(update: JsonObject): ContentChunk = ContentChunk(
            content = ContentBlock.from(update["content"]),
            messageId = update["messageId"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

// --- tool calls ---------------------------------------------------------------

/** Estados de una tool call (valores wire; el spec permite añadir más). */
object ToolCallStatus {
    const val PENDING = "pending"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
    const val FAILED = "failed"

    fun label(wire: String?): String = when (wire) {
        PENDING -> "Pendiente"
        IN_PROGRESS -> "En curso"
        COMPLETED -> "Hecho"
        FAILED -> "Error"
        else -> wire?.replace('_', ' ') ?: "Pendiente"
    }
}

/** Categoría de tool call (wire); solo afecta al icono, nunca al parseo. */
object ToolKind {
    const val READ = "read"
    const val EDIT = "edit"
    const val DELETE = "delete"
    const val MOVE = "move"
    const val SEARCH = "search"
    const val EXECUTE = "execute"
    const val THINK = "think"
    const val FETCH = "fetch"
    const val SWITCH_MODE = "switch_mode"

    fun icon(wire: String?): String = when (wire) {
        READ, SEARCH -> "🔍"
        EDIT, DELETE, MOVE -> "✏️"
        EXECUTE -> "▶️"
        THINK -> "🧠"
        FETCH -> "🌐"
        SWITCH_MODE -> "⚙️"
        else -> "🛠️"
    }
}

/** Tool call emitida por `session/update` con tag `tool_call`. */
data class AcpToolCall(
    val toolCallId: String,
    val title: String,
    val kind: String?,
    val status: String?,
    val content: List<ToolCallContent>,
    val rawInput: JsonElement?,
    val rawOutput: JsonElement?,
) {
    companion object {
        fun from(obj: JsonObject?): AcpToolCall {
            val raw = obj ?: JsonObject(emptyMap())
            return AcpToolCall(
                toolCallId = raw["toolCallId"]?.jsonPrimitive?.contentOrNull ?: "",
                title = raw["title"]?.jsonPrimitive?.contentOrNull ?: "",
                kind = raw["kind"]?.jsonPrimitive?.contentOrNull,
                status = raw["status"]?.jsonPrimitive?.contentOrNull,
                content = raw["content"]?.jsonArray?.mapNotNull { ToolCallContent.from(it) } ?: emptyList(),
                rawInput = raw["rawInput"],
                rawOutput = raw["rawOutput"],
            )
        }
    }
}

/** Actualización de una tool call (mismo shape que [AcpToolCall], todo opcional). */
data class AcpToolCallUpdate(
    val toolCallId: String,
    val title: String?,
    val kind: String?,
    val status: String?,
    val content: List<ToolCallContent>,
    val rawInput: JsonElement?,
    val rawOutput: JsonElement?,
) {
    companion object {
        fun from(obj: JsonObject?): AcpToolCallUpdate {
            val raw = obj ?: JsonObject(emptyMap())
            return AcpToolCallUpdate(
                toolCallId = raw["toolCallId"]?.jsonPrimitive?.contentOrNull ?: "",
                title = raw["title"]?.jsonPrimitive?.contentOrNull,
                kind = raw["kind"]?.jsonPrimitive?.contentOrNull,
                status = raw["status"]?.jsonPrimitive?.contentOrNull,
                content = raw["content"]?.jsonArray?.mapNotNull { ToolCallContent.from(it) } ?: emptyList(),
                rawInput = raw["rawInput"],
                rawOutput = raw["rawOutput"],
            )
        }
    }
}

/** Contenido de una tool call: bloque estándar, diff de archivo o terminal. */
sealed interface ToolCallContent {
    data class ContentBlockContent(val block: ContentBlock) : ToolCallContent
    data class Diff(val path: String, val oldText: String?, val newText: String) : ToolCallContent
    data class Unknown(val type: String, val raw: JsonObject) : ToolCallContent

    companion object {
        fun from(element: JsonElement?): ToolCallContent? {
            val obj = element?.jsonObjectOrNull() ?: return null
            return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "content" -> obj["content"]?.let { ContentBlock.from(it) }?.let { ContentBlockContent(it) }
                "diff" -> Diff(
                    path = obj["path"]?.jsonPrimitive?.contentOrNull ?: "",
                    oldText = obj["oldText"]?.jsonPrimitive?.contentOrNull,
                    newText = obj["newText"]?.jsonPrimitive?.contentOrNull ?: "",
                )
                else -> Unknown(obj["type"]?.jsonPrimitive?.contentOrNull ?: "", obj)
            }
        }
    }
}

// --- plan ---------------------------------------------------------------------

/** Plan del agente (`session/update` tag `plan`): lista completa que se reemplaza. */
data class AcpPlan(val entries: List<PlanEntry>) {
    companion object {
        fun from(obj: JsonObject?): AcpPlan {
            val raw = obj ?: JsonObject(emptyMap())
            return AcpPlan(
                entries = raw["entries"]?.jsonArray?.mapNotNull { PlanEntry.from(it) } ?: emptyList(),
            )
        }
    }
}

data class PlanEntry(val content: String, val priority: String?, val status: String?) {
    companion object {
        fun from(element: JsonElement?): PlanEntry? {
            val obj = element?.jsonObjectOrNull() ?: return null
            return PlanEntry(
                content = obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
                priority = obj["priority"]?.jsonPrimitive?.contentOrNull,
                status = obj["status"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

object PlanEntryStatus {
    const val PENDING = "pending"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"

    fun icon(wire: String?): String = when (wire) {
        COMPLETED -> "✓"
        IN_PROGRESS -> "▶"
        else -> "·"
    }
}

// --- session/request_permission (request entrante del agente) -------------------

data class PermissionRequest(
    val requestId: String,
    val sessionId: String,
    val toolCall: AcpToolCallUpdate,
    val options: List<PermissionOption>,
)

data class PermissionOption(val optionId: String, val name: String, val kind: String?) {
    val isReject: Boolean get() = kind?.startsWith("reject") == true
}

// --- helpers --------------------------------------------------------------------

private fun JsonElement.jsonObjectOrNull(): JsonObject? = (this as? JsonObject)

private fun JsonObject.obj(name: String): JsonObject? = this[name]?.jsonObjectOrNull()
