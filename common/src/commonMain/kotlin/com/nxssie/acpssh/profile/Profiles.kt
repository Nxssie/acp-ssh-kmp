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
 * guardado: el default explícito del modo (shell en Terminal, claude-code-acp
 * en Chat, ver decisión cerrada #5 del plan).
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
    val acpRunDir: String? = null,
    val acpCwd: String? = null,
)

/**
 * Ids únicos sin dependencias de plataforma (kotlin.uuid sigue experimental):
 * 128 bits de aleatoriedad en hex, colisión despreciable para listas locales.
 */
fun newProfileId(): String =
    Random.nextLong().toULong().toString(16) + "-" + Random.nextLong().toULong().toString(16)

/**
 * Comandos precargados de fábrica (agentes ACP verificados a mano contra el
 * servidor real). Solo texto — ninguna credencial, host ni clave va acá, así
 * que es seguro hornearlo en un APK público. Ids fijos: las implementaciones
 * de [ProfileStore] los siembran una sola vez en el primer arranque (no
 * reaparecen si el usuario los borra después).
 *
 * Las rutas son absolutas porque el `sh` no interactivo que usa el lanzador
 * remoto (SSH `exec`) no hereda el PATH de un shell interactivo — ver
 * RemoteAcpProcess/AcpSession. Si el servidor de destino tiene otro usuario o
 * instalación, hay que editar el comando manualmente.
 */
val DEFAULT_COMMAND_SEEDS: List<SavedCommand> = listOf(
    SavedCommand(
        id = "seed-claude-code-acp",
        label = "claude-code-acp",
        command = "/home/agent/.bun/bin/claude-code-acp",
        mode = AcpMode.CHAT,
    ),
    SavedCommand(
        id = "seed-pi-acp",
        label = "pi-acp",
        command = "/home/agent/.bun/bin/pi-acp",
        mode = AcpMode.CHAT,
    ),
)

/**
 * Snapshot de un tab de chat ACP con sesión ya arrancada, persistido para poder
 * retomarlo (`session/load`) tras un reinicio del proceso (Android puede matar
 * la app en background). [cwd] es el mismo que se usó al crear la sesión — el
 * agente real indexa sus archivos de sesión por cwd, así que hay que reusarlo
 * tal cual en vez de recalcularlo al reconectar.
 */
@Serializable
data class SavedTabSession(val tabId: String, val sessionId: String, val cwd: String)

/** Construye la config de conexión a partir del perfil y sus referencias ya resueltas. */
fun ConnectionProfile.toTerminalConfig(key: SavedKey, command: SavedCommand?): TerminalConfig =
    TerminalConfig(
        host = host,
        port = port,
        username = username,
        privateKeyPem = key.privateKeyPem,
        remoteCommand = command?.command?.takeIf { it.isNotBlank() },
        publicKeyLine = key.publicKeyLine,
        acpRunDir = acpRunDir?.takeIf { it.isNotBlank() },
        acpCwd = acpCwd?.takeIf { it.isNotBlank() },
        profileId = id,
    )

/**
 * Resuelve las referencias del perfil. Devuelve null si la clave ya no existe
 * (p. ej. se borró de la lista de claves): la UI debe bloquear "Conectar" en
 * ese perfil. Un [ConnectionProfile.commandId] huérfano se tolera como "sin
 * comando" (cae al default explícito del modo) en vez de fallar.
 */
fun resolveProfile(
    profile: ConnectionProfile,
    keys: List<SavedKey>,
    commands: List<SavedCommand>,
): TerminalConfig? {
    val key = keys.firstOrNull { it.id == profile.keyId } ?: return null
    val command = profile.commandId?.let { id -> commands.firstOrNull { it.id == id } }
    return profile.toTerminalConfig(key, command)
}
