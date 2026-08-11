package com.nxssie.acpssh.acp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Error de protocolo ACP: respuesta de error JSON-RPC o mensaje inesperado. */
class AcpException(message: String, val code: Int? = null) : Exception(message)

/**
 * Cliente ACP (JSON-RPC 2.0 sobre NDJSON) contra el agente remoto. Habla el
 * ciclo de vida del protocolo v1: `initialize` → `session/new` →
 * `session/prompt` (streaming de `session/update`) + requests entrantes del
 * agente (`session/request_permission`), que exigen respuesta del cliente.
 *
 * Un único hilo de lectura despacha respuestas (resuelven [CompletableDeferred]
 * pendientes por id — el agente puede responder desordenado, confirmado contra
 * el adaptador real), notificaciones (a [updates]) y requests entrantes (a
 * [permissionRequests]).
 *
 * Sin timeouts propios: si el agente muere o el SSH se cae, [onEof] avisa al
 * host para que cancele el scope y las awaits pendientes se cancelan con él.
 */
class AcpClient(
    private val framer: NdjsonFramer,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var nextId = 0L
    private val pending = mutableMapOf<String, CompletableDeferred<RpcIncoming.Response>>()

    private val _updates = Channel<SessionUpdate>(Channel.UNLIMITED)
    val updates: Channel<SessionUpdate> = _updates

    private val _permissionRequests = Channel<PermissionRequest>(Channel.UNLIMITED)
    val permissionRequests: Channel<PermissionRequest> = _permissionRequests

    /** Llamado cuando el canal remoto cierra (EOF): el host decide desconectar. */
    var onEof: (() -> Unit)? = null

    private var readerJob: Job? = null

    /** Arranca el hilo de lectura. Debe llamarse una vez, antes del primer request. */
    fun start() {
        readerJob = scope.launch {
            try {
                framer.lines().collect { line -> route(parseRpc(line)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cerrar un tab o matar el agente interrumpe una lectura
                // bloqueante en curso (SSHJ) que puede salir como
                // InterruptedIOException en vez de CancellationException
                // (runInterruptible solo traduce InterruptedException) — sin
                // este catch, esta corrutina vive en el scope raíz (sin
                // CoroutineExceptionHandler) y la excepción tumba toda la app.
                // Tratarla igual que un EOF real: el host decide qué hacer vía onEof.
            } finally {
                // El EOF del canal remoto llega por aquí; la cancelación del
                // scope del host también pasa por este finally.
                onEof?.invoke()
            }
        }
    }

    // --- ciclo de vida ---------------------------------------------------------

    suspend fun initialize(): InitializeResult = request("initialize", encodeToJson(InitializeParams())) { result ->
        val obj = result?.jsonObjectOrNull()
        val info = obj?.get("agentInfo")?.jsonObjectOrNull()
        InitializeResult(
            protocolVersion = obj?.str("protocolVersion")?.toIntOrNull() ?: -1,
            agentName = info?.str("name"),
            agentTitle = info?.str("title"),
            agentVersion = info?.str("version"),
            agentCapabilities = obj,
        )
    }

    suspend fun newSession(cwd: String): NewSessionResult = request("session/new", encodeToJson(NewSessionParams(cwd))) { result ->
        val obj = result?.jsonObjectOrNull()
        NewSessionResult(
            sessionId = obj?.str("sessionId") ?: "",
            modes = SessionModeState.from(obj?.get("modes") as? JsonObject),
            configOptions = (obj?.get("configOptions") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(ConfigOption::from) },
            models = SessionModelState.from(obj?.get("models") as? JsonObject),
        )
    }

    /**
     * `session/load`: retoma [sessionId] (el agente manda todo su historial
     * como `session/update` normales antes de responder). A diferencia de
     * `session/new`, la respuesta no repite el sessionId — ya lo conocemos.
     */
    suspend fun loadSession(sessionId: String, cwd: String): NewSessionResult =
        request("session/load", encodeToJson(LoadSessionParams(sessionId, cwd))) { result ->
            val obj = result?.jsonObjectOrNull()
            NewSessionResult(
                sessionId = sessionId,
                modes = SessionModeState.from(obj?.get("modes") as? JsonObject),
                configOptions = (obj?.get("configOptions") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(ConfigOption::from) },
                models = SessionModelState.from(obj?.get("models") as? JsonObject),
            )
        }

    /**
     * Envía un prompt y espera el fin de turno. Las actualizaciones de sesión
     * (chunks de texto, tool calls, plan…) llegan por [updates] mientras tanto;
     * un error del agente se devuelve en [PromptResult.error] sin lanzar.
     */
    suspend fun prompt(sessionId: String, text: String): PromptResult {
        val params = encodeToJson(
            PromptParams(
                sessionId = sessionId,
                prompt = buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                },
            ),
        )
        return try {
            request("session/prompt", params) { result ->
                val obj = result?.jsonObjectOrNull()
                PromptResult(
                    stopReason = obj?.str("stopReason"),
                    usage = obj?.get("usage") as? JsonObject,
                    error = null,
                )
            }
        } catch (e: AcpException) {
            PromptResult(stopReason = null, usage = null, error = RpcError(e.code ?: -32603, e.message ?: "Unknown error"))
        }
    }

    /** Notificación `session/cancel` (el agente responde `stopReason: cancelled`). */
    suspend fun cancel(sessionId: String) {
        framer.writeLine(RpcOut.notification("session/cancel", encodeToJson(SessionIdParams(sessionId))))
    }

    /**
     * Cambia un [ConfigOption] de la sesión (p. ej. modelo o nivel de
     * "thinking" en `pi-acp`); el agente responde con la lista actualizada,
     * que además suele mandar por separado como `config_option_update`
     * (ver [SessionUpdate.ConfigOptionUpdate]) — ambos caminos actualizan lo
     * mismo, no hace falta elegir uno.
     */
    suspend fun setConfigOption(sessionId: String, configId: String, value: String): List<ConfigOption> =
        request("session/set_config_option", encodeToJson(SetConfigOptionParams(sessionId, configId, value))) { result ->
            (result?.jsonObjectOrNull()?.get("configOptions") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(ConfigOption::from) } ?: emptyList()
        }

    /**
     * Cambia el modelo activo (mecanismo UNSTABLE de `claude-code-acp`, ver
     * [SetSessionModelParams]) — la respuesta no trae nada útil que parsear
     * (solo `_meta`), así que el llamador actualiza su estado de forma
     * optimista si esto no lanza.
     */
    suspend fun setModel(sessionId: String, modelId: String) {
        request("session/set_model", encodeToJson(SetSessionModelParams(sessionId, modelId))) {}
    }

    /**
     * Cambia el modo de sesión (`session/set_mode`, estable en el spec; en
     * `claude-code-acp` son los modos de permiso: default/plan/acceptEdits/
     * bypassPermissions). Respuesta sin payload útil — actualización optimista
     * del llamador; los cambios ajenos llegan como `current_mode_update`.
     */
    suspend fun setMode(sessionId: String, modeId: String) {
        request("session/set_mode", encodeToJson(SetSessionModeParams(sessionId, modeId))) {}
    }

    /** Responde a un [request] de permiso pendiente con la decisión del usuario. */
    suspend fun respondPermission(request: PermissionRequest, outcome: PermissionOutcome) {
        framer.writeLine(RpcOut.response(request.requestId, outcome.toJson()))
    }

    /**
     * Cierra el cliente (cancela el lector; el transporte lo cierra el host).
     *
     * Cancelar [readerJob] dispara el mismo `finally` que un EOF real (ver
     * [start]) — hay que silenciar [onEof] ANTES de cancelar, o un cierre
     * intencionado (p. ej. al cerrar un tab o al reconectar reusando su
     * mismo tabId) se reporta como "conexión perdida" y tumba la sesión
     * nueva que lo reemplazó.
     */
    fun close() {
        onEof = null
        readerJob?.cancel()
        readerJob = null
    }

    // --- interno ---------------------------------------------------------------

    /** Manda un request, espera su respuesta y parsea el result (lanza [AcpException] en error). */
    private suspend fun <T> request(
        method: String,
        params: JsonElement,
        parse: (JsonElement?) -> T,
    ): T {
        val deferred = CompletableDeferred<RpcIncoming.Response>()
        // El id se captura DENTRO del lock y se usa el mismo valor para enviar:
        // si dos requests corren en paralelo, leer la propiedad fuera del lock
        // podría mandar el id de otro.
        val id: Long
        mutex.withLock {
            nextId++
            id = nextId
            pending[id.toString()] = deferred
        }
        framer.writeLine(RpcOut.request(id, method, params))
        val response = deferred.await()
        response.error?.let { throw AcpException(it.message, it.code) }
        return parse(response.result)
    }

    private suspend fun route(message: RpcIncoming?) {
        when (message) {
            is RpcIncoming.Response -> mutex.withLock {
                pending.remove(message.id)?.complete(message)
            }
            is RpcIncoming.Notification -> when (message.method) {
                "session/update" -> {
                    val update = message.params?.get("update") as? JsonObject
                    if (update != null) _updates.trySend(SessionUpdate.from(update))
                }
                // Notificaciones protocol-level ($/…) y demás: ignorar.
            }
            is RpcIncoming.Request -> when (message.method) {
                "session/request_permission" -> {
                    val params = message.params
                    _permissionRequests.trySend(
                        PermissionRequest(
                            requestId = message.id,
                            sessionId = params?.str("sessionId") ?: "",
                            toolCall = AcpToolCallUpdate.from(params?.get("toolCall") as? JsonObject),
                            options = (params?.get("options") as? JsonArray)
                                ?.mapNotNull { option ->
                                    val obj = option as? JsonObject ?: return@mapNotNull null
                                    PermissionOption(
                                        optionId = obj.str("optionId") ?: "",
                                        name = obj.str("name") ?: "",
                                        kind = obj.str("kind"),
                                    )
                                }
                                ?: emptyList(),
                        ),
                    )
                }
                // Métodos que no anunciamos en initialize (fs/*, terminal/*,
                // elicitation/*): responder error evita que el agente se quede
                // esperando una respuesta que nunca llega.
                else -> framer.writeLine(RpcOut.errorResponse(message.id, -32601, "Method not found"))
            }
            null -> Unit
        }
    }
}

private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
