package com.nxssie.acpssh.profile

import com.nxssie.acpssh.session.AcpMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Modelo de datos de la Fase F: serialización (con defaults omitidos para
 * tolerar archivos escritos por versiones futuras/pasadas) y reglas de
 * resolución de referencias keyId/commandId.
 */
class ProfilesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val key = SavedKey(id = "k1", label = "Mi clave", privateKeyPem = "PEM", publicKeyLine = "ssh-ed25519 AAAA c")
    private val command = SavedCommand(id = "c1", label = "tmux", command = "tmux new -As x", mode = AcpMode.TERMINAL)
    private val profile = ConnectionProfile(
        id = "p1",
        label = "servidor",
        host = "example.com",
        port = 2222,
        username = "carlos",
        keyId = "k1",
        commandId = "c1",
        acpRunDir = ".acp",
        acpCwd = "/srv",
    )

    @Test
    fun profileRoundTripsThroughJson() {
        val encoded = json.encodeToString(ConnectionProfile.serializer(), profile)
        assertEquals(profile, json.decodeFromString(ConnectionProfile.serializer(), encoded))
    }

    @Test
    fun defaultsSurviveMissingFields() {
        // Un archivo escrito antes de que existieran puerto/comando/runDir sigue cargando.
        val minimal = """{"id":"p","label":"l","host":"h","username":"u","keyId":"k"}"""
        val decoded = json.decodeFromString(ConnectionProfile.serializer(), minimal)
        assertEquals(22, decoded.port)
        assertEquals(AcpMode.TERMINAL, decoded.mode)
        assertNull(decoded.commandId)
        assertNull(decoded.acpRunDir)
        assertNull(decoded.acpCwd)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val future = """{"id":"p","label":"l","host":"h","username":"u","keyId":"k","newField":42}"""
        assertEquals("p", json.decodeFromString(ConnectionProfile.serializer(), future).id)
    }

    @Test
    fun toTerminalConfigMapsAllFields() {
        val config = profile.toTerminalConfig(key, command)
        assertEquals("example.com", config.host)
        assertEquals(2222, config.port)
        assertEquals("carlos", config.username)
        assertEquals("PEM", config.privateKeyPem)
        assertEquals("tmux new -As x", config.remoteCommand)
        assertEquals("ssh-ed25519 AAAA c", config.publicKeyLine)
        assertEquals(".acp", config.acpRunDir)
        assertEquals("/srv", config.acpCwd)
    }

    @Test
    fun toTerminalConfigWithoutCommandLeavesNullRemoteCommandInTerminal() {
        val config = profile.copy(commandId = null).toTerminalConfig(key, null)
        assertNull(config.remoteCommand)
    }

    @Test
    fun blankCommandFallsBackToDefaultOfTheMode() {
        val terminalConfig = profile.toTerminalConfig(key, command.copy(command = "  "))
        assertNull(terminalConfig.remoteCommand)

        val chatConfig = profile.copy(mode = AcpMode.CHAT).toTerminalConfig(key, command.copy(command = "  "))
        assertEquals(ChatAgentKind.CLAUDE.command, chatConfig.remoteCommand)
    }

    @Test
    fun chatModeWithoutCustomCommandFallsBackToClaudeAgentByDefault() {
        val config = profile.copy(mode = AcpMode.CHAT, commandId = null).toTerminalConfig(key, null)
        assertEquals(ChatAgentKind.CLAUDE.command, config.remoteCommand)
    }

    @Test
    fun chatModeHonorsExplicitChatAgentKind() {
        val config = profile.copy(mode = AcpMode.CHAT, commandId = null, chatAgentKind = ChatAgentKind.PI)
            .toTerminalConfig(key, null)
        assertEquals(ChatAgentKind.PI.command, config.remoteCommand)
    }

    @Test
    fun customCommandOverridesChatAgentKindEvenInChatMode() {
        val config = profile.copy(mode = AcpMode.CHAT, chatAgentKind = ChatAgentKind.PI).toTerminalConfig(key, command)
        assertEquals("tmux new -As x", config.remoteCommand)
    }

    @Test
    fun resolveFailsWhenKeyIsMissing() {
        assertNull(resolveProfile(profile, keys = emptyList(), commands = listOf(command)))
    }

    @Test
    fun resolveToleratesOrphanCommandIdAsNoCommand() {
        val config = resolveProfile(profile.copy(commandId = "borrado"), listOf(key), emptyList())
        assertEquals("example.com", config?.host)
        assertNull(config?.remoteCommand)
    }

    @Test
    fun resolveToleratesOrphanCommandIdAsNoCommandFallsBackToChatDefault() {
        val config = resolveProfile(profile.copy(mode = AcpMode.CHAT, commandId = "borrado"), listOf(key), emptyList())
        assertEquals(ChatAgentKind.CLAUDE.command, config?.remoteCommand)
    }

    @Test
    fun resolveFindsBothReferences() {
        val config = resolveProfile(profile, listOf(key), listOf(command))
        assertEquals("PEM", config?.privateKeyPem)
        assertEquals("tmux new -As x", config?.remoteCommand)
    }

    @Test
    fun newProfileIdsAreUnique() {
        assertNotEquals(newProfileId(), newProfileId())
        assertTrue(newProfileId().isNotBlank())
    }
}
