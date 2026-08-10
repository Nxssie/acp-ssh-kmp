package com.nxssie.acpssh

import com.nxssie.acpssh.profile.ConnectionProfile
import com.nxssie.acpssh.profile.ProfileStore
import com.nxssie.acpssh.profile.SavedCommand
import com.nxssie.acpssh.profile.SavedKey
import com.nxssie.acpssh.profile.SavedTabSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * [ProfileStore] de desktop: un único archivo JSON en
 * `~/.config/acp-ssh-kmp/profiles.json` con permisos 600.
 *
 * Asimetría deliberada con Android (documentada en el plan): desktop no tiene
 * AndroidX Security, así que las claves privadas van en plano protegidas por
 * permisos de usuario — el mismo nivel de protección que `~/.ssh/id_ed25519`.
 */
class DesktopProfileStore(
    private val file: File = File(System.getProperty("user.home"), ".config/acp-ssh-kmp/profiles.json"),
) : ProfileStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private var cache: StoreData? = null

    override fun listProfiles(): List<ConnectionProfile> = load().profiles

    override fun saveProfile(profile: ConnectionProfile) {
        mutate { it.copy(profiles = it.profiles.upsert(profile) { p -> p.id }) }
    }

    override fun deleteProfile(id: String) {
        mutate {
            it.copy(
                profiles = it.profiles.filterNot { p -> p.id == id },
                lastProfileId = if (it.lastProfileId == id) null else it.lastProfileId,
            )
        }
    }

    override fun listKeys(): List<SavedKey> = load().keys

    override fun saveKey(key: SavedKey) {
        mutate { it.copy(keys = it.keys.upsert(key) { k -> k.id }) }
    }

    override fun deleteKey(id: String) {
        mutate { it.copy(keys = it.keys.filterNot { k -> k.id == id }) }
    }

    override fun listCommands(): List<SavedCommand> = load().commands

    override fun saveCommand(command: SavedCommand) {
        mutate { it.copy(commands = it.commands.upsert(command) { c -> c.id }) }
    }

    override fun deleteCommand(id: String) {
        mutate { it.copy(commands = it.commands.filterNot { c -> c.id == id }) }
    }

    override fun loadLastProfileId(): String? = load().lastProfileId

    override fun setLastProfileId(id: String?) {
        mutate { it.copy(lastProfileId = id) }
    }

    override fun loadSavedTabs(profileId: String): List<SavedTabSession> = load().tabsByProfile[profileId].orEmpty()

    override fun saveTabs(profileId: String, tabs: List<SavedTabSession>) {
        mutate {
            it.copy(
                tabsByProfile = if (tabs.isEmpty()) {
                    it.tabsByProfile - profileId
                } else {
                    it.tabsByProfile + (profileId to tabs)
                },
            )
        }
    }

    @Synchronized
    private fun load(): StoreData =
        cache ?: readFromDisk().also { cache = it }

    @Synchronized
    private fun mutate(block: (StoreData) -> StoreData) {
        val next = block(load())
        writeToDisk(next)
        cache = next
    }

    private fun readFromDisk(): StoreData {
        if (!file.isFile) return StoreData()
        return runCatching { json.decodeFromString(StoreData.serializer(), file.readText()) }
            .getOrElse { StoreData() }
    }

    private fun writeToDisk(data: StoreData) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(StoreData.serializer(), data))
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (e: UnsupportedOperationException) {
            // FS no POSIX (Windows): se quedan los permisos por defecto.
        }
    }

    private fun <T> List<T>.upsert(item: T, id: (T) -> String): List<T> =
        if (any { id(it) == id(item) }) map { if (id(it) == id(item)) item else it } else this + item

    @Serializable
    private data class StoreData(
        val profiles: List<ConnectionProfile> = emptyList(),
        val keys: List<SavedKey> = emptyList(),
        val commands: List<SavedCommand> = emptyList(),
        val lastProfileId: String? = null,
        val tabsByProfile: Map<String, List<SavedTabSession>> = emptyMap(),
    )
}
