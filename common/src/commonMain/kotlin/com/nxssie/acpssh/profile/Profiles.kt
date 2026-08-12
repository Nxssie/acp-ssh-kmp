package com.nxssie.acpssh.profile

import com.nxssie.acpssh.session.AcpMode
import com.nxssie.acpssh.session.TerminalConfig
import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * Clave privada SSH guardada. La UI solo muestra [label]; el PEM no se pinta
 * salvo tras el botón explícito "Mostrar clave" (fricción deliberada).
 *
 * [publicKeyLine] solo se conoce si la clave se generó en la app (una clave
 * importada por PEM no trae su línea pública y no se deriva en commonMain).
 */
@Serializable
data class SavedKey(
    val id: String,
    val label: String,
    val privateKeyPem: String,
    val publicKeyLine: String? = null,
)

/**
 * Comando remoto guardado. [mode] null = reutilizable en Terminal y Chat; si
 * se fija, el selector de comandos lo filtra a ese modo (con opción "ver todos").
 */
@Serializable
data class SavedCommand(
    val id: String,
    val label: String,
    val command: String,
    val mode: AcpMode? = null,
)

/**
 * Perfil de conexión guardado. Referencia la clave y el comando por id (la
 * clave NUNCA se duplica dentro del perfil). [commandId] null = sin comando
 * custom: en Terminal cae al shell (default); en Chat cae a [chatAgentKind]
 * (ver decisión cerrada #5 del plan). Un [commandId] explícito es la vía
 * avanzada para reemplazar tanto el shell como Claude/Pi Agent por un
 * comando propio.
 */
@Serializable
data class ConnectionProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val keyId: String,
    val commandId: String? = null,
    val chatAgentKind: ChatAgentKind? = null,
    val acpRunDir: String? = null,
    val acpCwd: String? = null,
)

/**
 * Implementaciones ACP de fábrica para Chat: siempre disponibles sin crear un
 * comando custom (elimina la fricción de "Gestionar comandos" para el caso
 * común). [command] es una ruta absoluta porque el `sh` no interactivo del
 * exec SSH no hereda el PATH de un shell interactivo — ver
 * RemoteAcpProcess/AcpSession. Si el servidor de destino tiene otro usuario o
 * instalación, el comando custom ([ConnectionProfile.commandId]) sigue siendo
 * la vía de escape.
 */
@Serializable
enum class ChatAgentKind(val label: String, val command: String) {
    CLAUDE("Claude Agent", "/home/agent/.bun/bin/claude-code-acp"),
    PI("Pi Agent", "/home/agent/.bun/bin/pi-acp"),
}

/**
 * Ids únicos sin dependencias de plataforma (kotlin.uuid sigue experimental):
 * 128 bits de aleatoriedad en hex, colisión despreciable para listas locales.
 */
fun newProfileId(): String =
    Random.nextLong().toULong().toString(16) + "-" + Random.nextLong().toULong().toString(16)

/**
 * Snapshot de un tab de chat ACP con sesión ya arrancada, persistido para poder
 * retomarlo (`session/load`) tras un reinicio del proceso (Android puede matar
 * la app en background). [cwd] es el mismo que se usó al crear la sesión — el
 * agente real indexa sus archivos de sesión por cwd, así que hay que reusarlo
 * tal cual en vez de recalcularlo al reconectar.
 */
@Serializable
data class SavedTabSession(val tabId: String, val sessionId: String, val cwd: String)

/**
 * Construye la config de conexión a partir del perfil y sus referencias ya
 * resueltas. [command] (custom, opcional) manda sobre el default del modo; en
 * Chat sin comando custom cae a [ConnectionProfile.chatAgentKind] (Claude por
 * defecto); en Terminal sin comando custom no hay `remoteCommand` (shell).
 */
fun ConnectionProfile.toTerminalConfig(key: SavedKey, command: SavedCommand?, mode: AcpMode): TerminalConfig =
    TerminalConfig(
        host = host,
        port = port,
        username = username,
        privateKeyPem = key.privateKeyPem,
        remoteCommand = command?.command?.takeIf { it.isNotBlank() }
            ?: (chatAgentKind ?: ChatAgentKind.CLAUDE).command.takeIf { mode == AcpMode.CHAT },
        publicKeyLine = key.publicKeyLine,
        acpRunDir = acpRunDir?.takeIf { it.isNotBlank() },
        acpCwd = acpCwd?.takeIf { it.isNotBlank() },
        profileId = id,
    )

/**
 * Resuelve las referencias del perfil. Devuelve null si la clave ya no existe
 * (p. ej. se borró de la lista de claves): la UI debe bloquear "Conectar" en
 * ese perfil. Un [ConnectionProfile.commandId] huérfano se tolera como "sin
 * comando custom" (cae al default explícito del modo) en vez de fallar.
 */
fun resolveProfile(
    profile: ConnectionProfile,
    keys: List<SavedKey>,
    commands: List<SavedCommand>,
    mode: AcpMode,
): TerminalConfig? {
    val key = keys.firstOrNull { it.id == profile.keyId } ?: return null
    val command = profile.commandId?.let { id -> commands.firstOrNull { it.id == id } }
    return profile.toTerminalConfig(key, command, mode)
}
