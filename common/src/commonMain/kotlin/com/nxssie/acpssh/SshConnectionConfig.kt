package com.nxssie.acpssh

/** Configuración de conexión SSH (dominio, sin dependencias de implementación). */
data class SshConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: Auth,
) {
    sealed interface Auth {
        data class KeyFile(val path: String) : Auth
        /** Clave PEM en memoria (pegada en la UI), sin fichero intermedio. */
        data class KeyData(val pem: String) : Auth
        data class Password(val secret: String) : Auth
    }
}
