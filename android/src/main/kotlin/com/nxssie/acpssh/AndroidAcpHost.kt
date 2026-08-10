package com.nxssie.acpssh

import android.content.Context
import com.nxssie.acpssh.acp.AcpClient
import com.nxssie.acpssh.acp.AcpExecTransport
import com.nxssie.acpssh.acp.PermissionOutcome
import com.nxssie.acpssh.acp.PermissionRequest
import com.nxssie.acpssh.acp.RawByteChannel
import com.nxssie.acpssh.acp.SshjExecRawChannel
import com.nxssie.acpssh.session.AcpHost
import com.nxssie.acpssh.session.AcpSession
import com.nxssie.acpssh.session.AcpSessionState
import com.nxssie.acpssh.session.AcpSessionStore
import com.nxssie.acpssh.session.ConnectionState
import com.nxssie.acpssh.session.ConnectStatus
import com.nxssie.acpssh.session.PermissionUi
import com.nxssie.acpssh.session.TerminalConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient

/**
 * Implementación de [AcpHost] para Android: conecta por SSH (mismo TOFU y
 * SecureStore que el terminal), arranca el agente remoto persistente y habla
 * ACP por canales `exec` crudos.
 */
class AndroidAcpHost(context: Context) : AcpHost {

    init {
        com.nxssie.acpssh.crypto.ensureBouncyCastleProvider()
    }

    private val store = SecureStore(context)
    private val verifier = TofuHostKeyVerifier(store) { pending ->
        _connection.value = ConnectionState(ConnectStatus.AWAITING_HOST_KEY, pendingHostKey = pending)
    }

    private val sessionStore = AcpSessionStore()
    override val session: StateFlow<AcpSessionState> get() = sessionStore.state

    private val _connection = MutableStateFlow(ConnectionState(ConnectStatus.DISCONNECTED))
    override val connection: StateFlow<ConnectionState> = _connection

    private var sessionScope: CoroutineScope? = null
    private var client: SSHClient? = null
    private var transport: AcpExecTransport? = null
    private var acpSession: AcpSession? = null
    private var acpClient: AcpClient? = null

    override fun connect(config: TerminalConfig) {
        disconnect()
        store.saveConfig(config)
        _connection.value = ConnectionState(ConnectStatus.CONNECTING)

        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sessionScope = sc
        sc.launch {
            try {
                val ssh = AndroidSsh.connect(config, verifier)
                client = ssh
                val transport = AndroidAcpTransport(ssh)
                this@AndroidAcpHost.transport = transport

                val acpSession = AcpSession(transport, config, sc)
                this@AndroidAcpHost.acpSession = acpSession
                val result = acpSession.start()
                val acpClient = result.client
                this@AndroidAcpHost.acpClient = acpClient

                acpClient.onEof = { onChannelEof() }
                launch { for (u in acpClient.updates) sessionStore.onUpdate(u) }
                launch { for (p in acpClient.permissionRequests) onPermissionRequest(p) }

                sessionStore.onSessionStarted(
                    agentName = result.initialize.agentTitle ?: result.initialize.agentName,
                    sessionId = result.newSession.sessionId,
                )
                _connection.value = ConnectionState(ConnectStatus.CONNECTED)
            } catch (e: Exception) {
                runCatching { client?.disconnect() }
                client = null
                transport = null
                acpSession = null
                acpClient = null
                _connection.value = ConnectionState(ConnectStatus.FAILED, error = e.message ?: e.toString())
            }
        }
    }

    private suspend fun onPermissionRequest(request: PermissionRequest) {
        sessionStore.onPermission(
            PermissionUi(
                request = request,
                title = request.toolCall.title ?: "Solicitud de permiso",
                kind = request.toolCall.kind,
            ),
        )
    }

    /** El canal remoto cerró: desconectar limpio (el proceso remoto sobrevive). */
    private fun onChannelEof() {
        if (_connection.value.status == ConnectStatus.CONNECTED || _connection.value.status == ConnectStatus.CONNECTING) {
            _connection.value = ConnectionState(ConnectStatus.DISCONNECTED, error = "Conexión perdida")
        }
        sessionScope?.cancel()
        sessionScope = null
        runCatching { acpSession?.close() }
        runCatching { transport?.close() }
        acpSession = null
        transport = null
        acpClient = null
        sessionStore.reset()
    }

    override fun acceptHostKey() = verifier.acceptHostKey()

    override fun rejectHostKey() = verifier.rejectHostKey()

    override fun sendPrompt(text: String) {
        val client = acpClient ?: return
        val sessionId = sessionStore.state.value.sessionId ?: return
        if (sessionStore.state.value.busy || text.isBlank()) return
        sessionStore.onUserPrompt(text)
        sessionScope?.launch {
            val result = client.prompt(sessionId, text)
            sessionStore.onTurnEnd(error = result.error?.message)
        }
    }

    override fun respondPermission(request: PermissionRequest, outcome: PermissionOutcome) {
        sessionScope?.launch {
            runCatching { acpClient?.respondPermission(request, outcome) }
            sessionStore.onPermission(null)
        }
    }

    override fun toggleToolCall(id: String) = sessionStore.toggleToolCall(id)

    override fun cancelTurn() {
        val client = acpClient ?: return
        val sessionId = sessionStore.state.value.sessionId ?: return
        val pending = sessionStore.state.value.pendingPermission
        sessionScope?.launch {
            runCatching { client.cancel(sessionId) }
            // El spec exige responder Cancelled a los permisos pendientes al cancelar.
            if (pending != null) {
                runCatching { client.respondPermission(pending.request, PermissionOutcome.Cancelled) }
            }
            sessionStore.onPermission(null)
            sessionStore.onTurnEnd()
        }
    }

    override fun disconnect() {
        sessionScope?.cancel()
        sessionScope = null
        runCatching { acpSession?.close() }
        runCatching { transport?.close() }
        acpSession = null
        transport = null
        acpClient = null
        sessionStore.reset()
        _connection.value = ConnectionState(ConnectStatus.DISCONNECTED)
    }

    override fun loadLastConfig(): TerminalConfig? = store.loadConfig()

    private class AndroidAcpTransport(private val ssh: SSHClient) : AcpExecTransport {
        override suspend fun exec(command: String): RawByteChannel = withContext(Dispatchers.IO) {
            val session = ssh.startSession()
            try {
                SshjExecRawChannel(session.exec(command), session)
            } catch (e: Exception) {
                session.close()
                throw e
            }
        }

        override fun close() {
            runCatching { ssh.disconnect() }
        }
    }
}
