package com.nxssie.acpssh

import android.content.Context
import com.nxssie.acpssh.profile.ConnectionProfile
import com.nxssie.acpssh.profile.ProfileStore
import com.nxssie.acpssh.profile.SavedCommand
import com.nxssie.acpssh.profile.SavedKey
import com.nxssie.acpssh.profile.SavedTabSession
import com.nxssie.acpssh.profile.newProfileId
import com.nxssie.acpssh.session.AcpMode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [ProfileStore] sobre las mismas EncryptedSharedPreferences que [SecureStore]:
 * cada colección se serializa como JSON bajo una sola key (`profiles`, `keys`,
 * `commands`), así añadir campos al modelo no exige migrar el schema de prefs.
 *
 * Migración desde la config única anterior (Fase F): si existen las keys
 * legadas (`host`/`user`/`pem`…) y todavía no hay ningún perfil, se crean un
 * perfil + clave + comando a partir de ellas y se borran las keys viejas —
 * nadie pierde su conexión guardada al actualizar. Las host keys TOFU
 * (`hostkey:*`) no se tocan: las sigue leyendo [SecureStore] del mismo archivo.
 */
class SecureStoreProfileStore(context: Context) : ProfileStore {

    private val prefs = secureSharedPreferences(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        migrateLegacyConfig()
    }

    override fun listProfiles(): List<ConnectionProfile> = readList(KEY_PROFILES, ConnectionProfile.serializer())

    override fun saveProfile(profile: ConnectionProfile) {
        writeList(KEY_PROFILES, ConnectionProfile.serializer(), listProfiles().upsert(profile))
    }

    override fun deleteProfile(id: String) {
        writeList(KEY_PROFILES, ConnectionProfile.serializer(), listProfiles().filterNot { it.id == id })
        if (loadLastProfileId() == id) setLastProfileId(null)
    }

    override fun listKeys(): List<SavedKey> = readList(KEY_KEYS, SavedKey.serializer())

    override fun saveKey(key: SavedKey) {
        writeList(KEY_KEYS, SavedKey.serializer(), listKeys().upsert(key))
    }

    override fun deleteKey(id: String) {
        writeList(KEY_KEYS, SavedKey.serializer(), listKeys().filterNot { it.id == id })
    }

    override fun listCommands(): List<SavedCommand> = readList(KEY_COMMANDS, SavedCommand.serializer())

    override fun saveCommand(command: SavedCommand) {
        writeList(KEY_COMMANDS, SavedCommand.serializer(), listCommands().upsert(command))
    }

    override fun deleteCommand(id: String) {
        writeList(KEY_COMMANDS, SavedCommand.serializer(), listCommands().filterNot { it.id == id })
    }

    override fun loadLastProfileId(): String? = prefs.getString(KEY_LAST_PROFILE, null)

    override fun setLastProfileId(id: String?) {
        prefs.edit().putString(KEY_LAST_PROFILE, id).apply()
    }

    override fun loadLastMode(): AcpMode? =
        prefs.getString(KEY_LAST_MODE, null)?.let { runCatching { AcpMode.valueOf(it) }.getOrNull() }

    override fun setLastMode(mode: AcpMode) {
        prefs.edit().putString(KEY_LAST_MODE, mode.name).apply()
    }

    override fun loadSavedTabs(profileId: String): List<SavedTabSession> =
        readList(tabsKey(profileId), SavedTabSession.serializer())

    override fun saveTabs(profileId: String, tabs: List<SavedTabSession>) {
        if (tabs.isEmpty()) {
            prefs.edit().remove(tabsKey(profileId)).apply()
        } else {
            writeList(tabsKey(profileId), SavedTabSession.serializer(), tabs)
        }
    }

    private fun tabsKey(profileId: String) = "tabs:$profileId"

    private fun migrateLegacyConfig() {
        if (prefs.contains(KEY_PROFILES)) return
        val host = prefs.getString(LEGACY_HOST, null) ?: return
        val user = prefs.getString(LEGACY_USER, null) ?: return
        val pem = prefs.getString(LEGACY_PEM, null) ?: return
        val key = SavedKey(
            id = newProfileId(),
            label = "Clave importada",
            privateKeyPem = pem,
            publicKeyLine = prefs.getString(LEGACY_PUBLIC, null)?.takeIf { it.isNotBlank() },
        )
        val command = prefs.getString(LEGACY_COMMAND, null)?.takeIf { it.isNotBlank() }
            ?.let { SavedCommand(id = newProfileId(), label = it.take(40), command = it) }
        val profile = ConnectionProfile(
            id = newProfileId(),
            label = "$user@$host",
            host = host,
            port = prefs.getString(LEGACY_PORT, null)?.toIntOrNull() ?: 22,
            username = user,
            keyId = key.id,
            commandId = command?.id,
        )
        saveKey(key)
        command?.let { saveCommand(it) }
        saveProfile(profile)
        setLastProfileId(profile.id)
        prefs.edit()
            .remove(LEGACY_HOST)
            .remove(LEGACY_PORT)
            .remove(LEGACY_USER)
            .remove(LEGACY_PEM)
            .remove(LEGACY_COMMAND)
            .remove(LEGACY_PUBLIC)
            .apply()
    }

    private fun <T> readList(key: String, serializer: KSerializer<T>): List<T> =
        prefs.getString(key, null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(serializer), it) }.getOrNull() }
            ?: emptyList()

    private fun <T> writeList(key: String, serializer: KSerializer<T>, list: List<T>) {
        prefs.edit()
            .putString(key, json.encodeToString(ListSerializer(serializer), list))
            .apply()
    }

    private fun List<ConnectionProfile>.upsert(item: ConnectionProfile): List<ConnectionProfile> =
        if (any { it.id == item.id }) map { if (it.id == item.id) item else it } else this + item

    private fun List<SavedKey>.upsert(item: SavedKey): List<SavedKey> =
        if (any { it.id == item.id }) map { if (it.id == item.id) item else it } else this + item

    private fun List<SavedCommand>.upsert(item: SavedCommand): List<SavedCommand> =
        if (any { it.id == item.id }) map { if (it.id == item.id) item else it } else this + item

    private companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_KEYS = "keys"
        const val KEY_COMMANDS = "commands"
        const val KEY_LAST_PROFILE = "last_profile"
        const val KEY_LAST_MODE = "last_mode"

        const val LEGACY_HOST = "host"
        const val LEGACY_PORT = "port"
        const val LEGACY_USER = "user"
        const val LEGACY_PEM = "pem"
        const val LEGACY_COMMAND = "command"
        const val LEGACY_PUBLIC = "public_key"
    }
}
