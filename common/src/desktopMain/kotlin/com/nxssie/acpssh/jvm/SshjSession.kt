package com.nxssie.acpssh.jvm

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import com.nxssie.acpssh.ExecChannel
import com.nxssie.acpssh.PtyShell
import com.nxssie.acpssh.SshConnectionConfig
import com.nxssie.acpssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import java.io.File
import java.io.IOException

/**
 * Fábrica de sesiones SSH basada en SSHJ (JVM/desktop).
 *
 * El verificado de host key es obligatorio vía archivo known_hosts: sin él la
 * conexión falla en lugar de aceptar claves desconocidas (secure default).
 * Pasando `knownHosts = null` se usa un verifier promiscuo (solo dev/desktop).
 */
object SshjConnect {

    suspend fun connect(config: SshConnectionConfig, knownHosts: File? = null): SshSession =
        withContext(Dispatchers.IO) {
            val client = SSHClient()
            if (knownHosts != null) {
                if (!knownHosts.isFile) {
                    throw IOException("known_hosts no existe: ${knownHosts.path}")
                }
                client.loadKnownHosts(knownHosts)
            } else {
                client.addHostKeyVerifier(PromiscuousVerifier())
            }
            client.connectTimeout = 10_000
            client.connect(config.host, config.port)
            // Las sesiones ACP/terminal son largas y pueden quedar idle: keepalive
            // para que NAT/firewalls intermedios no corten la conexión.
            client.connection.keepAlive.keepAliveInterval = 30
            try {
                when (val auth = config.auth) {
                    is SshConnectionConfig.Auth.KeyFile -> {
                        client.authPublickey(config.username, keyProvider(auth.path))
                    }
                    is SshConnectionConfig.Auth.KeyData -> {
                        client.authPublickey(config.username, keyProviderFromString(auth.pem))
                    }
                    is SshConnectionConfig.Auth.Password -> {
                        client.authPassword(config.username, auth.secret)
                    }
                }
            } catch (e: Exception) {
                client.disconnect()
                throw e
            }
            SshjSession(client)
        }

    /**
     * Elige el lector de clave según el header del archivo: OpenSSH v1 (ed25519,
     * formato moderno de `ssh-keygen`) o PEM clásico (RSA/EC).
     */
    private fun keyProvider(path: String): KeyProvider {
        val file = File(path)
        val header = file.useLines { it.firstOrNull() }.orEmpty()
        return if (header.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) {
            OpenSSHKeyV1KeyFile().apply { init(file, null) }
        } else {
            OpenSSHKeyFile().apply { init(file, null) }
        }
    }

    /** Igual que [keyProvider] pero desde el PEM en memoria (sin fichero). */
    private fun keyProviderFromString(pem: String): KeyProvider {
        val header = pem.substringBefore('\n')
        return if (header.contains("OPENSSH PRIVATE KEY")) {
            OpenSSHKeyV1KeyFile().apply { init(pem, null, null) }
        } else {
            OpenSSHKeyFile().apply { init(pem, null, null) }
        }
    }
}

private class SshjSession(private val client: SSHClient) : SshSession {

    override suspend fun exec(command: String): ExecChannel = withContext(Dispatchers.IO) {
        val session: Session = client.startSession()
        val exec: Session.Command = session.exec(command)
        SshjExecChannel(exec, session)
    }

    override suspend fun openShell(term: String, cols: Int, rows: Int): PtyShell =
        withContext(Dispatchers.IO) {
            val session: Session = client.startSession()
            session.allocatePTY(term, cols, rows, 0, 0, emptyMap<PTYMode, Int>())
            val shell: Session.Shell = session.startShell()
            SshjPtyShell(shell, session)
        }

    override fun close() {
        client.disconnect()
    }
}

private class SshjExecChannel(
    private val exec: Session.Command,
    private val session: Session,
) : ExecChannel {

    override val stdout: Source = exec.inputStream.asSource().buffered()
    override val stderr: Source = exec.errorStream.asSource().buffered()
    override val stdin: Sink = exec.outputStream.asSink().buffered()

    override suspend fun exitStatus(): Int = withContext(Dispatchers.IO) {
        // Espera al cierre del canal SIN tocar stdout: el reader del protocolo
        // (NDJSON en Fase 2) es el único consumidor de ese stream.
        exec.join()
        exec.exitStatus ?: -1
    }

    override fun close() {
        exec.close()
        session.close()
    }
}

private class SshjPtyShell(
    private val shell: Session.Shell,
    private val session: Session,
) : PtyShell {

    override val stdout: Source = shell.inputStream.asSource().buffered()
    override val stderr: Source = shell.errorStream.asSource().buffered()
    override val stdin: Sink = shell.outputStream.asSink().buffered()

    override suspend fun resize(cols: Int, rows: Int) = withContext(Dispatchers.IO) {
        shell.changeWindowDimensions(cols, rows, 0, 0)
    }

    override fun close() {
        runCatching { shell.close() }
        runCatching { session.close() }
    }
}
