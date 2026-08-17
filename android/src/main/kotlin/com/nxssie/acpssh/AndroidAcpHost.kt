package com.nxssie.acpssh

import android.content.Context
import com.nxssie.acpssh.acp.AcpExecTransport
import com.nxssie.acpssh.acp.PermissionOutcome
import com.nxssie.acpssh.acp.PermissionRequest
import com.nxssie.acpssh.acp.RawByteChannel
import com.nxssie.acpssh.acp.SshjExecRawChannel
import com.nxssie.acpssh.notify.AcpNotifier
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient

/**
 * [AcpHost] para Android: una conexión SSH (TOFU + [SecureStore]) compartida
 * por N tabs de chat vía [AcpSessionManager]. El verifier TOFU se crea por
 * conexión dentro de la factoría: las host keys aceptadas persisten en el
 * store, así que no se pierde estado entre instancias.
 */
class AndroidAcpHost(context: Context, profileStore: ProfileStore) : AcpHost {

    init {
        com.nxssie.acpssh.crypto.ensureBouncyCastleProvider()
    }

    private val secureStore = SecureStore(context)
    private val notifier = AcpNotifier(context)

    /** Tabs con `pendingPermission` ya notificados: evita renotificar en cada emisión de [AcpSessionManager.tabs] mientras siga pendiente (streaming, etc.). */
    private val notifiedTabIds = mutableSetOf<String>()

    @Volatile private var verifier: TofuHostKeyVerifier? = null

    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val manager = AcpSessionManager(
        scope = hostScope,
        connectSsh = { config, onHostKey ->
            val v = TofuHostKeyVerifier(secureStore, onHostKey)
            verifier = v
            AndroidAcpTransport(withContext(Dispatchers.IO) { AndroidSsh.connect(config, v) })
        },
        loadSavedTabs = profileStore::loadSavedTabs,
        saveTabs = profileStore::saveTabs,
    )

    init {
        // Best-effort: mientras el proceso siga vivo en background (sin
        // foreground service, Android puede matarlo en cualquier momento —
        // ver AcpNotifier), avisa cuando un tab pasa a tener un
        // `session/request_permission` pendiente que antes no tenía.
        hostScope.launch {
            manager.tabs.collect { tabs ->
                val pendingNow = tabs.filter { it.session.pendingPermission != null }.map { it.tabId }.toSet()
                for (tabId in pendingNow - notifiedTabIds) {
                    val tab = tabs.first { it.tabId == tabId }
                    notifier.notifyPermissionPending(
                        tabId = tabId,
                        agentName = tab.session.agentName,
                        summary = tab.session.pendingPermission?.title ?: "Acción pendiente de confirmar",
                    )
                }
                for (tabId in notifiedTabIds - pendingNow) notifier.cancel(tabId)
                notifiedTabIds.clear()
                notifiedTabIds.addAll(pendingNow)
            }
        }
    }

    override val connection: StateFlow<ConnectionState> = manager.connection
    override val tabs: StateFlow<List<AcpTabState>> = manager.tabs
    override val activeTabId: StateFlow<String?> = manager.activeTabId
    override val maxTabs: Int get() = manager.maxTabs

    override fun connect(config: TerminalConfig) = manager.connect(config)
    override fun acceptHostKey() = verifier?.acceptHostKey() ?: Unit
    override fun rejectHostKey() = verifier?.rejectHostKey() ?: Unit
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
    override fun setConfigOption(configId: String, value: String) = manager.setConfigOption(configId, value)
    override fun setModel(modelId: String) = manager.setModel(modelId)
    override fun setMode(modeId: String) = manager.setMode(modeId)

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
