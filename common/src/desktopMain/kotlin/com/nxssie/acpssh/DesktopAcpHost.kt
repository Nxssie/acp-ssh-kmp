package com.nxssie.acpssh

import com.nxssie.acpssh.acp.AcpExecTransport
import com.nxssie.acpssh.acp.PermissionOutcome
import com.nxssie.acpssh.acp.PermissionRequest
import com.nxssie.acpssh.acp.RawByteChannel
import com.nxssie.acpssh.acp.asRawByteChannel
import com.nxssie.acpssh.jvm.SshjConnect
import com.nxssie.acpssh.profile.ProfileStore
import com.nxssie.acpssh.session.AcpHost
import com.nxssie.acpssh.session.AcpSessionManager
import com.nxssie.acpssh.session.AcpTabState
import com.nxssie.acpssh.session.ConnectionState
import com.nxssie.acpssh.session.RemoteSessionsUi
import com.nxssie.acpssh.session.TerminalConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * [AcpHost] para desktop (JVM): una conexión SSH sobre [SshjConnect] y los
 * canales `exec` kotlinx.io, compartida por N tabs vía [AcpSessionManager].
 * Sin TOFU: usa `~/.ssh/known_hosts` si existe y, si no, verifier promiscuo
 * (herramienta de desarrollo).
 */
class DesktopAcpHost(profileStore: ProfileStore) : AcpHost {

    init {
        com.nxssie.acpssh.crypto.ensureBouncyCastleProvider()
    }

    private val manager = AcpSessionManager(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        connectSsh = { config, _ ->
            val knownHosts = File(System.getProperty("user.home"), ".ssh/known_hosts").takeIf { it.isFile }
            DesktopAcpTransport(SshjConnect.connect(config.toSshConfig(), knownHosts))
        },
        loadSavedTabs = profileStore::loadSavedTabs,
        saveTabs = profileStore::saveTabs,
    )

    override val connection: StateFlow<ConnectionState> = manager.connection
    override val tabs: StateFlow<List<AcpTabState>> = manager.tabs
    override val activeTabId: StateFlow<String?> = manager.activeTabId
    override val maxTabs: Int get() = manager.maxTabs

    override fun connect(config: TerminalConfig) = manager.connect(config)
    override fun acceptHostKey() = Unit
    override fun rejectHostKey() = Unit
    override fun openTab() = manager.openTab()
    override fun closeTab(tabId: String) = manager.closeTab(tabId)
    override fun killTabAgent(tabId: String) = manager.killTabAgent(tabId)
    override fun selectTab(tabId: String) = manager.selectTab(tabId)
    override fun sendPrompt(text: String) = manager.sendPrompt(text)
    override fun respondPermission(request: PermissionRequest, outcome: PermissionOutcome) =
        manager.respondPermission(request, outcome)

    override fun toggleToolCall(id: String) = manager.toggleToolCall(id)
    override fun cancelTurn() = manager.cancelTurn()
    override fun disconnect() = manager.disconnect()

    override val remoteSessions: StateFlow<RemoteSessionsUi> = manager.remoteSessions
    override fun refreshRemoteSessions() = manager.refreshRemoteSessions()
    override fun attachRemoteSession(dirName: String) = manager.attachRemoteSession(dirName)
    override fun killRemoteSession(dirName: String) = manager.killRemoteSession(dirName)

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
