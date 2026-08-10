package com.nxssie.acpssh.session

import com.nxssie.acpssh.acp.AcpClient
import com.nxssie.acpssh.acp.AcpExecTransport
import com.nxssie.acpssh.acp.PermissionOutcome
import com.nxssie.acpssh.acp.PermissionRequest
import com.nxssie.acpssh.acp.RemoteAcpProcess
import com.nxssie.acpssh.acp.readAllToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Snapshot de un tab de chat para la UI: id estable + estado de su sesión ACP. */
data class AcpTabState(val tabId: String, val session: AcpSessionState)

/**
 * Gestor multi-sesión ACP (Fase H): UNA conexión SSH compartida y un proceso
 * de agente remoto por tab.
 *
 * Por qué un proceso por tab y no varias `session/new` sobre un único agente:
 * el wire lleva `sessionId` pero el cliente no hace demux por sesión y no se
 * ha verificado que `claude-code-acp` soporte sesiones concurrentes. Con un
 * [AcpSession] por tab (cada uno con su propio runDir/FIFOs, así dos tabs del
 * mismo perfil no comparten pipes) un agente que crashea no afecta a los demás
 * y se reutiliza tal cual el fix de lector huérfano por PID de la Fase B.
 *
 * Los tabIds sobreviven a [disconnect]: al reconectar se reabren los mismos
 * runDirs y, gracias al arranque idempotente de la Fase B (`ALREADY_RUNNING`),
 * cada tab se reanuda contra el MISMO proceso de agente si sigue vivo. Cerrar
 * un tab deja su proceso remoto corriendo (decisión cerrada #2); matarlo es la
 * acción explícita [killTabAgent].
 *
 * Un EOF de canal inesperado en cualquier tab implica que la conexión SSH
 * compartida se cayó: se desconecta todo ([ConnectStatus.DISCONNECTED] con
 * error), manteniendo los tabIds para que el reconnect los reabra.
 */
class AcpSessionManager(
    private val scope: CoroutineScope,
    private val connectSsh: suspend (config: TerminalConfig, onHostKey: (PendingHostKey) -> Unit) -> AcpExecTransport,
    val maxTabs: Int = DEFAULT_MAX_TABS,
) {

    private class TabEntry(
        val tabId: String,
        val runDir: String,
        val store: AcpSessionStore,
    ) {
        var acpSession: AcpSession? = null
        var client: AcpClient? = null
        var job: Job? = null

        /** Cierre intencionado: su EOF no debe desconectar el resto de tabs. */
        var closing: Boolean = false
    }

    private val _connection = MutableStateFlow(ConnectionState(ConnectStatus.DISCONNECTED))
    val connection: StateFlow<ConnectionState> = _connection

    private val _tabs = MutableStateFlow<List<AcpTabState>>(emptyList())
    val tabs: StateFlow<List<AcpTabState>> = _tabs

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, TabEntry>()
    private val tabIds = mutableListOf<String>()
    private var nextTabNumber = 1
    private var transport: AcpExecTransport? = null
    private var connectedConfig: TerminalConfig? = null
    private var connectJob: Job? = null

    // --- conexión --------------------------------------------------------------

    fun connect(config: TerminalConfig) {
        connectJob?.cancel()
        _connection.value = ConnectionState(ConnectStatus.CONNECTING)
        connectJob = scope.launch {
            disconnectInternal()
            connectedConfig = config
            try {
                val t = connectSsh(config) { pending ->
                    _connection.value = ConnectionState(ConnectStatus.AWAITING_HOST_KEY, pendingHostKey = pending)
                }
                mutex.withLock { transport = t }
                _connection.value = ConnectionState(ConnectStatus.CONNECTED)
                val pendingTabs = mutex.withLock { tabIds.toList() }
                if (pendingTabs.isEmpty()) {
                    openTabInternal(newTabId())
                } else {
                    // Reconnect: mismos tabIds → mismos runDirs → mismo proceso
                    // remoto si sigue vivo (arranque idempotente, Fase B).
                    pendingTabs.forEach { openTabInternal(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutex.withLock {
                    runCatching { transport?.close() }
                    transport = null
                }
                _connection.value = ConnectionState(ConnectStatus.FAILED, error = e.message ?: e.toString())
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        _connection.value = ConnectionState(ConnectStatus.DISCONNECTED)
        scope.launch { disconnectInternal() }
    }

    /** Cierra tabs y transporte pero conserva [tabIds] para reanudarlos al reconectar. */
    private suspend fun disconnectInternal() {
        val (t, es) = mutex.withLock {
            val t = transport
            transport = null
            val es = entries.values.toList()
            entries.values.forEach { it.closing = true }
            entries.clear()
            t to es
        }
        es.forEach { entry ->
            entry.job?.cancel()
            runCatching { entry.acpSession?.close() }
        }
        runCatching { t?.close() }
        _tabs.value = emptyList()
        _activeTabId.value = null
    }

    // --- tabs ------------------------------------------------------------------

    fun openTab() {
        if (_connection.value.status != ConnectStatus.CONNECTED) return
        if (_tabs.value.size >= maxTabs) return
        scope.launch { openTabInternal(newTabId()) }
    }

    /** Cierra el tab SIN matar el proceso remoto (reconectable, decisión #2). */
    fun closeTab(tabId: String) {
        scope.launch { closeTabInternal(tabId) }
    }

    /** Cierra el tab Y termina el agente remoto (acción explícita del usuario). */
    fun killTabAgent(tabId: String) {
        scope.launch {
            val runDir = mutex.withLock { entries[tabId]?.runDir }
            closeTabInternal(tabId)
            val t = mutex.withLock { transport } ?: return@launch
            if (runDir != null) {
                runCatching {
                    val channel = t.exec(RemoteAcpProcess.killCommand(runDir))
                    try {
                        channel.readAllToString()
                    } finally {
                        channel.close()
                    }
                }
            }
        }
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.tabId == tabId }) _activeTabId.value = tabId
    }

    private suspend fun newTabId(): String = mutex.withLock { "tab-${nextTabNumber++}" }

    private suspend fun openTabInternal(tabId: String) {
        val config = connectedConfig ?: return
        val t = mutex.withLock { transport } ?: return
        val base = config.acpRunDir?.takeIf { it.isNotBlank() } ?: AcpSession.DEFAULT_RUN_DIR
        val runDir = "$base/$tabId"
        val store = AcpSessionStore()
        val entry = TabEntry(tabId, runDir, store)
        val job = SupervisorJob(scope.coroutineContext.job)
        entry.job = job
        mutex.withLock {
            if (tabId !in tabIds) tabIds.add(tabId)
            entries[tabId] = entry
        }
        scope.launch(job) { store.state.collect { state -> publishTab(tabId, state) } }
        if (_activeTabId.value == null) _activeTabId.value = tabId
        try {
            val session = AcpSession(t, config.copy(acpRunDir = runDir), scope)
            val result = session.start()
            entry.acpSession = session
            entry.client = result.client
            result.client.onEof = { scope.launch { handleTabEof(tabId) } }
            scope.launch(job) { for (u in result.client.updates) store.onUpdate(u) }
            scope.launch(job) {
                for (p in result.client.permissionRequests) {
                    store.onPermission(
                        PermissionUi(
                            request = p,
                            title = p.toolCall.title ?: "Solicitud de permiso",
                            kind = p.toolCall.kind,
                        ),
                    )
                }
            }
            store.onSessionStarted(
                agentName = result.initialize.agentTitle ?: result.initialize.agentName,
                sessionId = result.newSession.sessionId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Un tab que no arranca (agente no instalado, handshake agotado) no
            // tumba la conexión ni los demás tabs: se queda con su error.
            store.onError(e.message ?: e.toString())
        }
    }

    private suspend fun closeTabInternal(tabId: String) {
        val entry = mutex.withLock {
            val e = entries[tabId] ?: return@withLock null
            e.closing = true
            entries.remove(tabId)
            tabIds.remove(tabId)
            e
        } ?: return
        entry.job?.cancel()
        runCatching { entry.acpSession?.close() }
        _tabs.update { list -> list.filter { it.tabId != tabId } }
        if (_activeTabId.value == tabId) {
            _activeTabId.value = _tabs.value.firstOrNull()?.tabId
        }
    }

    private suspend fun handleTabEof(tabId: String) {
        val entry = mutex.withLock { entries[tabId] } ?: return
        if (entry.closing) return
        if (_connection.value.status == ConnectStatus.CONNECTED ||
            _connection.value.status == ConnectStatus.CONNECTING
        ) {
            _connection.value = ConnectionState(ConnectStatus.DISCONNECTED, error = "Conexión perdida")
        }
        disconnectInternal()
    }

    private fun publishTab(tabId: String, state: AcpSessionState) {
        _tabs.update { list ->
            val index = list.indexOfFirst { it.tabId == tabId }
            val item = AcpTabState(tabId, state)
            if (index >= 0) list.toMutableList().apply { this[index] = item } else list + item
        }
    }

    // --- acciones sobre el tab activo -------------------------------------------

    fun sendPrompt(text: String) {
        scope.launch {
            val entry = mutex.withLock { entries[_activeTabId.value] } ?: return@launch
            val client = entry.client
            val state = entry.store.state.value
            val sessionId = state.sessionId
            if (client == null || sessionId == null) {
                // La sesión sigue arrancando (o falló al arrancar): sin esto el
                // prompt se pierde en silencio y el usuario no sabe por qué no
                // hubo respuesta.
                entry.store.onError("El agente todavía no está listo para recibir mensajes.")
                return@launch
            }
            if (state.busy || text.isBlank()) return@launch
            entry.store.onUserPrompt(text)
            val result = client.prompt(sessionId, text)
            entry.store.onTurnEnd(error = result.error?.message)
        }
    }

    fun respondPermission(request: PermissionRequest, outcome: PermissionOutcome) {
        scope.launch {
            val entry = mutex.withLock { entries[_activeTabId.value] } ?: return@launch
            runCatching { entry.client?.respondPermission(request, outcome) }
            entry.store.onPermission(null)
        }
    }

    fun cancelTurn() {
        scope.launch {
            val entry = mutex.withLock { entries[_activeTabId.value] } ?: return@launch
            val client = entry.client ?: return@launch
            val state = entry.store.state.value
            val sessionId = state.sessionId ?: return@launch
            val pending = state.pendingPermission
            runCatching { client.cancel(sessionId) }
            // El spec exige responder Cancelled a los permisos pendientes al cancelar.
            if (pending != null) {
                runCatching { client.respondPermission(pending.request, PermissionOutcome.Cancelled) }
            }
            entry.store.onPermission(null)
            entry.store.onTurnEnd()
        }
    }

    fun toggleToolCall(id: String) {
        scope.launch {
            mutex.withLock { entries[_activeTabId.value] }?.store?.toggleToolCall(id)
        }
    }

    companion object {
        const val DEFAULT_MAX_TABS = 5
    }
}
