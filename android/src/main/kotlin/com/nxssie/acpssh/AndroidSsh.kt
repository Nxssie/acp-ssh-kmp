package com.nxssie.acpssh

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import com.nxssie.acpssh.session.TerminalConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile

/**
 * Conexión SSH común a los hosts de Android (terminal y ACP): crea el cliente,
 * aplica el verifier TOFU, conecta y autentica con la clave PEM. Extraído para
 * no duplicar la plomería entre `AndroidSshTerminalHost` y `AndroidAcpHost`.
 */
object AndroidSsh {

    fun connect(config: TerminalConfig, verifier: HostKeyVerifier): SSHClient {
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
        return ssh
    }

    /** Elige el lector de clave según el header del PEM (OpenSSH v1 vs clásico). */
    fun keyProviderFromPem(pem: String): KeyProvider {
        val header = pem.substringBefore('\n')
        return if (header.contains("OPENSSH PRIVATE KEY")) {
            OpenSSHKeyV1KeyFile().apply { init(pem, null, null) }
        } else {
            OpenSSHKeyFile().apply { init(pem, null, null) }
        }
    }
}
