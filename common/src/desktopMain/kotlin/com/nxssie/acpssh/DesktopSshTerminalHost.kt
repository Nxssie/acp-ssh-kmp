package com.nxssie.acpssh

import com.nxssie.acpssh.jvm.SshjConnect
import com.nxssie.acpssh.session.ConnectionState
import com.nxssie.acpssh.session.ConnectStatus
import com.nxssie.acpssh.session.TerminalConfig
import com.nxssie.acpssh.session.TerminalHost
import com.nxssie.acpssh.terminal.TerminalEmulator
import com.nxssie.acpssh.terminal.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Implementación de [TerminalHost] para desktop (JVM) reutilizando
 * [SshjConnect] + [PtyShell]. Sin TOFU: usa `~/.ssh/known_hosts` si existe y,
 * si no, un verifier promiscuo (herramienta de desarrollo).
 */
class DesktopSshTerminalHost : TerminalHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _connection = MutableStateFlow(ConnectionState(ConnectStatus.DISCONNECTED))
    override val connection: StateFlow<ConnectionState> = _connection

    private val emulator = TerminalEmulator()
    override val screen: StateFlow<TerminalState> get() = emulator.state
    override val terminal: TerminalEmulator get() = emulator

    private var job: Job? = null
    private var session: SshSession? = null
    private var shell: PtyShell? = null
    private var lastConfig: TerminalConfig? = null
    private var writeChannel: Channel<ByteArray>? = null

    override fun connect(config: TerminalConfig) {
        disconnect()
        lastConfig = config
        _connection.value = ConnectionState(ConnectStatus.CONNECTING)
        job = scope.launch {
            try {
                val knownHosts = File(System.getProperty("user.home"), ".ssh/known_hosts").takeIf { it.isFile }
                val ssh = SshjConnect.connect(config.toSshConfig(), knownHosts)
                val sh = ssh.openShell()
                session = ssh
                shell = sh
                val channel = Channel<ByteArray>(Channel.UNLIMITED)
                writeChannel = channel
                launch { writeLoop(channel, sh) }
                emulator.onResponse = { out -> channel.trySend(out) }
                _connection.value = ConnectionState(ConnectStatus.CONNECTED)

                config.remoteCommand?.takeIf { it.isNotBlank() }?.let { cmd ->
                    channel.trySend((cmd + "\r").encodeToByteArray())
                }

                launch { readLoop(sh) }
                launch { drainStderr(sh) }
            } catch (e: Exception) {
                runCatching { session?.close() }
                session = null
                shell = null
                writeChannel?.close()
                writeChannel = null
                _connection.value = ConnectionState(ConnectStatus.FAILED, error = e.message ?: e.toString())
            }
        }
    }

    /** Único consumidor del canal de escritura: serializa todos los writes al stdin remoto. */
    private suspend fun writeLoop(channel: Channel<ByteArray>, sh: PtyShell) {
        for (bytes in channel) {
            try {
                sh.stdin.write(bytes)
                sh.stdin.flush()
            } catch (e: Exception) {
                break
            }
        }
    }

    private suspend fun readLoop(sh: PtyShell) {
        val buffer = ByteArray(4096)
        while (true) {
            val n = try {
                sh.stdout.readAtMostTo(buffer)
            } catch (e: Exception) {
                break
            }
            if (n <= 0) break
            emulator.feed(buffer, n)
        }
        disconnectInternal()
    }

    private suspend fun drainStderr(sh: PtyShell) {
        val buffer = ByteArray(1024)
        while (true) {
            val n = try {
                sh.stderr.readAtMostTo(buffer)
            } catch (e: Exception) {
                break
            }
            if (n <= 0) break
        }
    }

    private suspend fun disconnectInternal() {
        if (_connection.value.status == ConnectStatus.CONNECTED) {
            _connection.value = ConnectionState(ConnectStatus.DISCONNECTED)
        }
        job?.cancel()
        job = null
        writeChannel?.close()
        writeChannel = null
        runCatching { shell?.close() }
        runCatching { session?.close() }
        shell = null
        session = null
    }

    override fun acceptHostKey() = Unit

    override fun rejectHostKey() = Unit

    override fun send(bytes: ByteArray) {
        writeChannel?.trySend(bytes)
    }

    override fun send(text: String) = send(text.encodeToByteArray())

    override fun resize(cols: Int, rows: Int) {
        emulator.resize(cols, rows)
        val sh = shell ?: return
        scope.launch {
            runCatching { sh.resize(cols, rows) }
        }
    }

    override fun disconnect() {
        job?.cancel()
        job = null
        writeChannel?.close()
        writeChannel = null
        runCatching { shell?.close() }
        runCatching { session?.close() }
        shell = null
        session = null
        _connection.value = ConnectionState(ConnectStatus.DISCONNECTED)
    }

    override fun loadLastConfig(): TerminalConfig? = lastConfig

    private fun TerminalConfig.toSshConfig() = SshConnectionConfig(
        host = host,
        port = port,
        username = username,
        auth = SshConnectionConfig.Auth.KeyData(privateKeyPem),
    )
}
