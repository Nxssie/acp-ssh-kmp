package com.nxssie.acpssh

import com.nxssie.acpssh.acp.AcpClient
import com.nxssie.acpssh.acp.AcpExecTransport
import com.nxssie.acpssh.acp.PermissionOutcome
import com.nxssie.acpssh.acp.PermissionRequest
import com.nxssie.acpssh.acp.RawByteChannel
import com.nxssie.acpssh.acp.asRawByteChannel
import com.nxssie.acpssh.jvm.SshjConnect
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Implementación de [AcpHost] para desktop (JVM) sobre [SshjConnect] y los
 * canales `exec` kotlinx.io ya existentes. Sin TOFU: usa `~/.ssh/known_hosts`
 * si existe y, si no, verifier promiscuo (herramienta de desarrollo).
 */
class DesktopAcpHost : AcpHost {

    init {
        com.nxssie.acpssh.crypto.ensureBouncyCastleProvider()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _connection = MutableStateFlow(ConnectionState(ConnectStatus.DISCONNECTED))
    override val connection: StateFlow<ConnectionState> = _connection

    private val sessionStore = AcpSessionStore()
    override val session: StateFlow<AcpSessionState> get() = sessionStore.state

    private var job: Job? = null
    private var transport: AcpExecTransport? = null
    private var acpSession: AcpSession? = null
    private var acpClient: AcpClient? = null
    private var lastConfig: TerminalConfig? = null

    override fun connect(config: TerminalConfig) {
        disconnect()
        lastConfig = config
        _connection.value = ConnectionState(ConnectStatus.CONNECTING)
        job = scope.launch {
            try {
                val knownHosts = File(System.getProperty("user.home"), ".ssh/known_hosts").takeIf { it.isFile }
                val ssh = SshjConnect.connect(config.toSshConfig(), knownHosts)
                val transport = DesktopAcpTransport(ssh)
                this@DesktopAcpHost.transport = transport

                val acpSession = AcpSession(transport, config, scope)
                this@DesktopAcpHost.acpSession = acpSession
                val result = acpSession.start()
                val acpClient = result.client
                this@DesktopAcpHost.acpClient = acpClient

                acpClient.onEof = { onChannelEof() }
                launch { for (u in acpClient.updates) sessionStore.onUpdate(u) }
                launch { for (p in acpClient.permissionRequests) onPermissionRequest(p) }

                sessionStore.onSessionStarted(
                    agentName = result.initialize.agentTitle ?: result.initialize.agentName,
                    sessionId = result.newSession.sessionId,
                )
                _connection.value = ConnectionState(ConnectStatus.CONNECTED)
            } catch (e: Exception) {
                runCatching { transport?.close() }
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

    private fun onChannelEof() {
        if (_connection.value.status == ConnectStatus.CONNECTED || _connection.value.status == ConnectStatus.CONNECTING) {
            _connection.value = ConnectionState(ConnectStatus.DISCONNECTED, error = "Conexión perdida")
        }
        job?.cancel()
        job = null
        runCatching { acpSession?.close() }
        runCatching { transport?.close() }
        acpSession = null
        transport = null
        acpClient = null
        sessionStore.reset()
    }

    override fun acceptHostKey() = Unit

    override fun rejectHostKey() = Unit

    override fun sendPrompt(text: String) {
        val client = acpClient ?: return
        val sessionId = sessionStore.state.value.sessionId ?: return
        if (sessionStore.state.value.busy || text.isBlank()) return
        sessionStore.onUserPrompt(text)
        scope.launch {
            val result = client.prompt(sessionId, text)
            sessionStore.onTurnEnd(error = result.error?.message)
        }
    }

    override fun respondPermission(request: PermissionRequest, outcome: PermissionOutcome) {
        scope.launch {
            runCatching { acpClient?.respondPermission(request, outcome) }
            sessionStore.onPermission(null)
        }
    }

    override fun toggleToolCall(id: String) = sessionStore.toggleToolCall(id)

    override fun cancelTurn() {
        val client = acpClient ?: return
        val sessionId = sessionStore.state.value.sessionId ?: return
        val pending = sessionStore.state.value.pendingPermission
        scope.launch {
            runCatching { client.cancel(sessionId) }
            if (pending != null) {
                runCatching { client.respondPermission(pending.request, PermissionOutcome.Cancelled) }
            }
            sessionStore.onPermission(null)
            sessionStore.onTurnEnd()
        }
    }

    override fun disconnect() {
        job?.cancel()
        job = null
        runCatching { acpSession?.close() }
        runCatching { transport?.close() }
        acpSession = null
        transport = null
        acpClient = null
        sessionStore.reset()
        _connection.value = ConnectionState(ConnectStatus.DISCONNECTED)
    }

    override fun loadLastConfig(): TerminalConfig? = lastConfig

    private class DesktopAcpTransport(private val ssh: SshSession) : AcpExecTransport {
        override suspend fun exec(command: String): RawByteChannel = ssh.exec(command).asRawByteChannel()

        override fun close() = ssh.close()
    }

    private fun TerminalConfig.toSshConfig() = SshConnectionConfig(
        host = host,
        port = port,
        username = username,
        auth = SshConnectionConfig.Auth.KeyData(privateKeyPem),
    )
}
