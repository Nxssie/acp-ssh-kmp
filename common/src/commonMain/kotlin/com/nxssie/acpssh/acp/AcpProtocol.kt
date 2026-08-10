package com.nxssie.acpssh.acp

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Instancia de [Json] compartida para ACP: los agentes pueden omitir campos,
 * enviar `null` donde no debería o añadir campos futuros sin aviso; el spec
 * pide explícitamente `DefaultOnError` en casi todos los campos opcionales, así
 * que aquí se traduce en tolerancia total (unknown keys ignoradas, nulls
 * coaccionados a default).
 */
val AcpJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/** [AcpJson] con pretty-print, para mostrar input/output de tool calls en la UI. */
val AcpPrettyJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = true
}

/** Identificador JSON-RPC normalizado a String (los agentes usan números). */
fun JsonElement?.asRpcId(): String? = when (this) {
    is JsonPrimitive -> contentOrNull ?: toString()
    else -> null
}

/** `false` y `null` cuentan como ausencia de capacidad (el spec los defaultea). */
fun JsonObject.boolField(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull == true

/**
 * Un mensaje entrante del agente. Se detecta el tipo por la forma, no por el
 * método (JSON-RPC 2.0): una notificación no tiene `id`, una respuesta no
 * tiene `method` y un request entrante —p. ej. `session/request_permission`—
 * tiene ambos.
 */
sealed interface RpcIncoming {
    data class Response(val id: String, val result: JsonElement?, val error: RpcError?) : RpcIncoming
    data class Notification(val method: String, val params: JsonObject?) : RpcIncoming
    data class Request(val id: String, val method: String, val params: JsonObject?) : RpcIncoming
}

/** Error JSON-RPC (los códigos estándar + `data` libre del agente). */
data class RpcError(val code: Int, val message: String, val data: JsonElement? = null)

/** Parsea una línea NDJSON; devuelve null si no es un objeto JSON-RPC válido. */
fun parseRpc(line: String): RpcIncoming? {
    val root = runCatching { AcpJson.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return null
    val method = root["method"]?.jsonPrimitive?.contentOrNull
    val id = root["id"].asRpcId()
    return when {
        method != null && id != null -> RpcIncoming.Request(id, method, root["params"]?.jsonObject)
        method != null -> RpcIncoming.Notification(method, root["params"]?.jsonObject)
        id != null -> {
            val error = root["error"]
            RpcIncoming.Response(
                id = id,
                result = root["result"],
                error = error?.let { e ->
                    val obj = e.jsonObject
                    RpcError(
                        code = obj["code"]?.jsonPrimitive?.intOrNull ?: -32603,
                        message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error",
                        data = obj["data"],
                    )
                },
            )
        }
        else -> null
    }
}

/** Construye un mensaje de salida como String NDJSON listo para [NdjsonFramer.writeLine]. */
object RpcOut {
    fun request(id: Long, method: String, params: JsonElement?): String {
        val sb = StringBuilder("""{"jsonrpc":"2.0","id":$id,"method":""")
        sb.append(quote(method))
        if (params != null) {
            sb.append(""","params":""")
            sb.append(params)
        }
        sb.append('}')
        return sb.toString()
    }

    fun notification(method: String, params: JsonElement?): String {
        val sb = StringBuilder("""{"jsonrpc":"2.0","method":""")
        sb.append(quote(method))
        if (params != null) {
            sb.append(""","params":""")
            sb.append(params)
        }
        sb.append('}')
        return sb.toString()
    }

    fun response(id: String, result: JsonElement?): String =
        """{"jsonrpc":"2.0","id":$id,"result":${result ?: "null"}}"""

    fun errorResponse(id: String, code: Int, message: String): String =
        """{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":${quote(message)}}}"""

    private fun quote(s: String): String = AcpJson.encodeToString(String.serializer(), s)
}

/** Serializa un DTO con [AcpJson] a [JsonElement] (para los parámetros de salida). */
inline fun <reified T> encodeToJson(value: T): JsonElement = AcpJson.encodeToJsonElement(value)

// --- helpers de JsonElement para los decoders lenient -------------------------

fun JsonObject.optionalString(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it != "null" }

fun JsonObject.optionalLong(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

fun JsonObject.optionalFloat(name: String): Float? = this[name]?.jsonPrimitive?.floatOrNull

fun JsonObject.optionalJsonArray(name: String): List<JsonElement>? =
    this[name]?.let { if (it is JsonNull) null else it.jsonArray }

fun JsonObject.optionalJsonObject(name: String): JsonObject? =
    this[name]?.let { if (it is JsonNull) null else it.jsonObject }
