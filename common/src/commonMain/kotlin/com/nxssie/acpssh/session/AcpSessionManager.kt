package com.nxssie.acpssh.session

import com.nxssie.acpssh.acp.AcpClient
import com.nxssie.acpssh.acp.AcpExecTransport
import com.nxssie.acpssh.acp.PermissionOutcome
import com.nxssie.acpssh.acp.PermissionRequest
import com.nxssie.acpssh.acp.RemoteAcpProcess
import com.nxssie.acpssh.acp.readAllToString
import com.nxssie.acpssh.profile.SavedTabSession
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
    /**
     * Tabs con sesión ACP persistidos del perfil (Fase F+): sobreviven a que
     * Android mate el proceso en background. Sin [TerminalConfig.profileId]
     * (o sin implementación real, valor por defecto) no se persiste ni se
     * retoma nada — se comporta igual que antes.
     */
    private val loadSavedTabs: (profileId: String) -> List<SavedTabSession> = { emptyList() },
    private val saveTabs: (profileId: String, tabs: List<SavedTabSession>) -> Unit = { _, _ -> },
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

    /** sessionId+cwd por tab que YA arrancó sesión, para persistir y poder retomarla (`session/load`). */
    private val sessionRecords = mutableMapOf<String, SavedTabSession>()

    /** sessionId+cwd pendiente de retomar en el próximo [openTabInternal] de ese tabId. */
    private val resumeInfo = mutableMapOf<String, Pair<String, String>>()

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
                val tabsToOpen = if (pendingTabs.isNotEmpty()) {
                    // Reconnect DENTRO del mismo proceso: mismos tabIds → mismos
                    // runDirs → mismo proceso remoto si sigue vivo (arranque
                    // idempotente, Fase B). sessionRecords ya tiene su sessionId de
                    // la vez anterior si llegó a arrancar, para pasar por
                    // session/load abajo en vez de perder la conversación.
                    pendingTabs
                } else {
                    // Manager recién creado (primer connect, o el proceso de la app
                    // se reinició — Android puede matarlo en background): sin
                    // tabIds en memoria, mirar si el perfil tiene tabs persistidos
                    // en disco con sesión ACP real para retomarlos en vez de
                    // arrancar siempre un tab-1 nuevo.
                    val saved = config.profileId?.let(loadSavedTabs).orEmpty()
                    if (saved.isEmpty()) {
                        listOf(newTabId())
                    } else {
                        mutex.withLock {
                            saved.forEach { s -> tabIds.add(s.tabId); sessionRecords[s.tabId] = s }
                            val maxSeen = saved.mapNotNull {
                                it.tabId.removePrefix("tab-").toIntOrNull()
                            }.maxOrNull() ?: 0
                            nextTabNumber = maxOf(nextTabNumber, maxSeen + 1)
                        }
                        saved.map { it.tabId }
                    }
                }
                // Cualquier tab por (re)abrir con un sessionId conocido (de disco o
                // de una sesión ya arrancada antes en este mismo proceso) intenta
                // session/load en vez de session/new — unifica ambos caminos.
                mutex.withLock {
                    tabsToOpen.forEach { id -> sessionRecords[id]?.let { resumeInfo[id] = it.sessionId to it.cwd } }
                }
                tabsToOpen.forEach { openTabInternal(it) }
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
            // closeTabInternal ya sacaba el tab de sessionRecords/tabIds y persiste.
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
        val resume = mutex.withLock { resumeInfo.remove(tabId) }
        try {
            val session = AcpSession(t, config.copy(acpRunDir = runDir), scope)
            val result = session.start(resume)
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
            val cwd = session.resolvedCwd
            if (cwd != null) persistTabSession(config.profileId, tabId, result.newSession.sessionId, cwd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Un tab que no arranca (agente no instalado, handshake agotado) no
            // tumba la conexión ni los demás tabs: se queda con su error.
            store.onError(e.message ?: e.toString())
        }
    }

    /** Guarda (o actualiza) el sessionId+cwd real de un tab para poder retomarlo tras un reinicio del proceso. */
    private suspend fun persistTabSession(profileId: String?, tabId: String, sessionId: String, cwd: String) {
        if (profileId == null) return
        val snapshot = mutex.withLock {
            sessionRecords[tabId] = SavedTabSession(tabId, sessionId, cwd)
            sessionRecords.values.toList()
        }
        saveTabs(profileId, snapshot)
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
        // Cerrar un tab es una decisión explícita del usuario: no debe volver a
        // aparecer solo por reabrir la app (el proceso remoto sigue vivo igual,
        // esto solo afecta qué se auto-reabre al reconectar/reiniciar).
        val profileId = connectedConfig?.profileId
        val snapshot = mutex.withLock {
            resumeInfo.remove(tabId)
            sessionRecords.remove(tabId)
            sessionRecords.values.toList()
        }
        if (profileId != null) saveTabs(profileId, snapshot)
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
