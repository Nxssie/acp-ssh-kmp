package com.nxssie.acpssh.acp

/**
 * De qué lado viene un error, para no atribuir al cliente lo que es de red,
 * del servidor remoto o del agente ACP mismo — el pedido concreto que motivó
 * esto: "si el servidor no responde no quiero interpretar que hay un
 * problema en el cliente app".
 */
enum class ErrorOrigin(val label: String) {
    /** SSH: host inalcanzable, rechazado, timeout de conexión, auth rechazada. */
    CONNECTION("Conexión"),

    /** El agente remoto no respondió a tiempo, o el canal se cerró en medio de una sesión viva. */
    SERVER("Servidor"),

    /** Respuesta de error JSON-RPC del propio agente (p. ej. `session/prompt` con código de error). */
    PROTOCOL("Agente"),

    /** No reconocido por ninguna de las categorías anteriores: puede ser un bug del cliente. */
    APP("App"),
}

/**
 * Lanzada por [com.nxssie.acpssh.session.AcpSession.start] cuando el agente
 * remoto no responde al handshake dentro del timeout — deliberadamente un
 * tipo propio en vez de reusar `IllegalStateException`: [classify] necesita
 * distinguirla por tipo, no por parseo de texto traducido.
 */
class AgentUnresponsiveException(message: String) : Exception(message)

/**
 * Clasifica una excepción por [ErrorOrigin] usando el tipo declarado cuando lo
 * tenemos ([AgentUnresponsiveException], [AcpException]) y, para el resto
 * (excepciones de SSHJ/`java.net`/`java.io` que este módulo `commonMain` no
 * puede importar por nombre — viven en los módulos JVM que sí las lanzan),
 * el nombre simple de la clase — portable entre plataformas sin depender de
 * los tipos concretos de cada una.
 */
fun classify(e: Throwable): ErrorOrigin = when {
    e is AgentUnresponsiveException -> ErrorOrigin.SERVER
    e is AcpException -> ErrorOrigin.PROTOCOL
    looksLikeConnectionError(e) -> ErrorOrigin.CONNECTION
    else -> ErrorOrigin.APP
}

private fun looksLikeConnectionError(e: Throwable): Boolean {
    val name = e::class.simpleName.orEmpty()
    val connectionNames = listOf(
        "Connect", "Timeout", "UnknownHost", "Transport", "SSH", "Socket",
        "IOException", "EOFException", "UserAuth", "NoRouteToHost", "Unreachable",
    )
    return connectionNames.any { it in name }
}

/**
 * Mensaje para mostrar al usuario, con el origen como prefijo visible — para
 * que "Servidor: el agente no respondió en 20s" y "Conexión: host inalcanzable"
 * no se confundan con un fallo de la app. El detalle completo (stack trace)
 * se queda fuera de este mensaje corto; ver [com.nxssie.acpssh.log.AppLog].
 */
fun classifiedMessage(e: Throwable): String {
    val origin = classify(e)
    val body = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "Error desconocido"
    return "${origin.label}: $body"
}
