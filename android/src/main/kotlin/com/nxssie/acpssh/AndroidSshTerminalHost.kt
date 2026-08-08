package com.nxssie.acpssh

import android.content.Context
import com.nxssie.acpssh.session.ConnectionState
import com.nxssie.acpssh.session.ConnectStatus
import com.nxssie.acpssh.session.TerminalConfig
import com.nxssie.acpssh.session.TerminalHost
import com.nxssie.acpssh.terminal.TerminalEmulator
import com.nxssie.acpssh.terminal.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Implementación de [TerminalHost] para Android con SSHJ:
 * shell con PTY (`xterm-256color`) + verificación TOFU + streams crudos.
 */
class AndroidSshTerminalHost(context: Context) : TerminalHost {

    private val store = SecureStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val verifier = TofuHostKeyVerifier(store) { pending ->
        _connection.value = ConnectionState(ConnectStatus.AWAITING_HOST_KEY, pendingHostKey = pending)
    }

    private val _connection = MutableStateFlow(ConnectionState(ConnectStatus.DISCONNECTED))
    override val connection: StateFlow<ConnectionState> = _connection

    private val emulator = TerminalEmulator()
    override val screen: StateFlow<TerminalState> get() = emulator.state
    override val terminal: TerminalEmulator get() = emulator

    private var sessionScope: CoroutineScope? = null
    private var client: SSHClient? = null
    private var shell: Session.Shell? = null
    @Volatile private var writeChannel: Channel<ByteArray>? = null

    override fun connect(config: TerminalConfig) {
        disconnect()
        store.saveConfig(config)
        _connection.value = ConnectionState(ConnectStatus.CONNECTING)

        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sessionScope = sc
        sc.launch {
            try {
                val ssh = SSHClient()
                ssh.addHostKeyVerifier(verifier)
                ssh.connectTimeout = 10_000
                ssh.connection.keepAlive.keepAliveInterval = 30
                ssh.connect(config.host, config.port)
                try {
                    ssh.authPublickey(config.username, keyProviderFromPem(config.privateKeyPem))
                } catch (e: Exception) {
                    runCatching { ssh.disconnect() }
                    throw e
                }
                val session = ssh.startSession()
                session.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap<PTYMode, Int>())
                val sh = session.startShell()
                client = ssh
                shell = sh
                val channel = Channel<ByteArray>(Channel.UNLIMITED)
                writeChannel = channel
                launch(Dispatchers.IO) { writeLoop(channel, sh.outputStream) }
                emulator.onResponse = { out -> channel.trySend(out) }
                _connection.value = ConnectionState(ConnectStatus.CONNECTED)

                config.remoteCommand?.takeIf { it.isNotBlank() }?.let { cmd ->
                    channel.trySend((cmd + "\r").encodeToByteArray())
                }

                launch { readLoop(sh.inputStream) }
                launch { drainStderr(sh.errorStream) }
            } catch (e: Exception) {
                runCatching { client?.disconnect() }
                client = null
                shell = null
                writeChannel?.close()
                writeChannel = null
                _connection.value = ConnectionState(ConnectStatus.FAILED, error = e.message ?: e.toString())
            }
        }
    }

    /** Único consumidor del canal de escritura: serializa todos los writes al stdin remoto. */
    private suspend fun writeLoop(channel: Channel<ByteArray>, out: OutputStream) {
        for (bytes in channel) {
            try {
                out.write(bytes)
                out.flush()
            } catch (e: IOException) {
                break
            }
        }
    }

    private suspend fun readLoop(input: InputStream) {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            while (currentCoroutineContext().isActive) {
                val n = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    break
                }
                if (n <= 0) break
                emulator.feed(buffer, n)
            }
        }
        disconnectInternal()
    }

    private suspend fun drainStderr(input: InputStream) {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            while (currentCoroutineContext().isActive) {
                val n = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    break
                }
                if (n <= 0) break
            }
        }
    }

    private suspend fun disconnectInternal() {
        if (_connection.value.status == ConnectStatus.CONNECTED) {
            _connection.value = ConnectionState(ConnectStatus.DISCONNECTED)
        }
        sessionScope?.cancel()
        sessionScope = null
        writeChannel?.close()
        writeChannel = null
        runCatching { shell?.close() }
        runCatching { client?.disconnect() }
        shell = null
        client = null
    }

    override fun acceptHostKey() = verifier.acceptHostKey()

    override fun rejectHostKey() = verifier.rejectHostKey()

    override fun send(bytes: ByteArray) {
        writeChannel?.trySend(bytes)
    }

    override fun send(text: String) = send(text.encodeToByteArray())

    override fun resize(cols: Int, rows: Int) {
        emulator.resize(cols, rows)
        val sh = shell ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { sh.changeWindowDimensions(cols, rows, 0, 0) }
        }
    }

    override fun disconnect() {
        sessionScope?.cancel()
        sessionScope = null
        writeChannel?.close()
        writeChannel = null
        runCatching { shell?.close() }
        runCatching { client?.disconnect() }
        shell = null
        client = null
        _connection.value = ConnectionState(ConnectStatus.DISCONNECTED)
    }

    override fun loadLastConfig(): TerminalConfig? = store.loadConfig()

    private fun keyProviderFromPem(pem: String): KeyProvider {
        val header = pem.substringBefore('\n')
        return if (header.contains("OPENSSH PRIVATE KEY")) {
            com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile()
                .apply { init(pem, null, null) }
        } else {
            OpenSSHKeyFile().apply { init(pem, null, null) }
        }
    }
}
