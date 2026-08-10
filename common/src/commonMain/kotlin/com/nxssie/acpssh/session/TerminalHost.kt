package com.nxssie.acpssh.session

import com.nxssie.acpssh.terminal.TerminalEmulator
import com.nxssie.acpssh.terminal.TerminalState
import kotlinx.coroutines.flow.StateFlow

enum class ConnectStatus { DISCONNECTED, CONNECTING, AWAITING_HOST_KEY, CONNECTED, FAILED }

data class PendingHostKey(val algorithm: String, val fingerprint: String)

data class ConnectionState(
    val status: ConnectStatus,
    val pendingHostKey: PendingHostKey? = null,
    val error: String? = null,
)

data class TerminalConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val privateKeyPem: String,
    val remoteCommand: String? = null,
    /** Línea `ssh-ed25519 <b64> <comment>` de la pública, si la privada se generó en la app. */
    val publicKeyLine: String? = null,
    /** Directorio remoto del agente ACP persistente (relativo al home; default `.acp-ssh-kmp`). */
    val acpRunDir: String? = null,
    /** CWD de `session/new` (default: el `pwd` remoto del exec SSH). */
    val acpCwd: String? = null,
    /** Id del [com.nxssie.acpssh.profile.ConnectionProfile] de origen, para persistir/resumir tabs ACP. */
    val profileId: String? = null,
)

/**
 * Contrato compartido entre la UI (commonMain) y la implementación SSH
 * (SSHJ en el módulo `:android` / `desktopMain`). La UI nunca depende de SSHJ.
 */
interface TerminalHost : HasConnection {
    override val connection: StateFlow<ConnectionState>
    val screen: StateFlow<TerminalState>
    val terminal: TerminalEmulator

    fun connect(config: TerminalConfig)
    fun acceptHostKey()
    fun rejectHostKey()
    fun send(bytes: ByteArray)
    fun send(text: String)
    fun resize(cols: Int, rows: Int)
    fun disconnect()
}
