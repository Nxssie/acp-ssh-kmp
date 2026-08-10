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
import java.io.IOException
import java.io.InputStream

/**
 * Implementación de [TerminalHost] para Android con SSHJ:
 * shell con PTY (`xterm-256color`) + verificación TOFU + streams crudos.
 */
class AndroidSshTerminalHost(context: Context) : TerminalHost {

    init {
        com.nxssie.acpssh.crypto.ensureBouncyCastleProvider()
    }

    private val store = SecureStore(context)
    private val verifier = TofuHostKeyVerifier(store) { pending ->
        _connection.value = ConnectionState(ConnectStatus.AWAITING_HOST_KEY, pendingHostKey = pending)
    }

    private val _connection = MutableStateFlow(ConnectionState(ConnectStatus.DISCONNECTED))
    override val connection: StateFlow<ConnectionState> = _connection

    private val emulator = TerminalEmulator()
    override val screen: StateFlow<TerminalState> get() = emulator.state
    override val terminal: TerminalEmulator get() = emulator

    /** Comandos hacia el remoto, serializados por un único consumidor. */
    private sealed interface Command {
        class Data(val bytes: ByteArray) : Command
        class Resize(val cols: Int, val rows: Int) : Command
    }

    private var sessionScope: CoroutineScope? = null
    private var client: SSHClient? = null
    private var shell: Session.Shell? = null
    @Volatile private var writeChannel: Channel<Command>? = null

    override fun connect(config: TerminalConfig) {
        disconnect()
        _connection.value = ConnectionState(ConnectStatus.CONNECTING)

        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sessionScope = sc
        sc.launch {
            try {
                val ssh = AndroidSsh.connect(config, verifier)
                val session = ssh.startSession()
                session.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap<PTYMode, Int>())
                val sh = session.startShell()
                client = ssh
                shell = sh
                val channel = Channel<Command>(Channel.UNLIMITED)
                writeChannel = channel
                launch(Dispatchers.IO) { writeLoop(channel, sh) }
                emulator.onResponse = { out -> channel.trySend(Command.Data(out)) }
                _connection.value = ConnectionState(ConnectStatus.CONNECTED)

                config.remoteCommand?.takeIf { it.isNotBlank() }?.let { cmd ->
                    channel.trySend(Command.Data((cmd + "\r").encodeToByteArray()))
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

    /** Único consumidor del canal: serializa writes y window-changes al remoto. */
    private suspend fun writeLoop(channel: Channel<Command>, sh: Session.Shell) {
        for (cmd in channel) {
            try {
                when (cmd) {
                    is Command.Data -> {
                        sh.outputStream.write(cmd.bytes)
                        sh.outputStream.flush()
                    }
                    is Command.Resize -> sh.changeWindowDimensions(cmd.cols, cmd.rows, 0, 0)
                }
            } catch (e: Exception) {
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
                try {
                    emulator.feed(buffer, n)
                } catch (e: Exception) {
                    // Bug del emulador o secuencia inesperada: desconectar limpio
                    // en vez de tumbar la coroutine (y la app) sin handler.
                    break
                }
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
        emulator.onResponse = null
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
        writeChannel?.trySend(Command.Data(bytes))
    }

    override fun send(text: String) = send(text.encodeToByteArray())

    override fun resize(cols: Int, rows: Int) {
        emulator.resize(cols, rows)
        writeChannel?.trySend(Command.Resize(cols, rows))
    }

    override fun disconnect() {
        sessionScope?.cancel()
        sessionScope = null
        emulator.onResponse = null
        writeChannel?.close()
        writeChannel = null
        runCatching { shell?.close() }
        runCatching { client?.disconnect() }
        shell = null
        client = null
        _connection.value = ConnectionState(ConnectStatus.DISCONNECTED)
    }
}
