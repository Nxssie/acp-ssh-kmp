package com.nxssie.acpssh.profile

import com.nxssie.acpssh.session.AcpMode
import com.nxssie.acpssh.session.TerminalConfig

/**
 * Almacén de perfiles de conexión, claves SSH y comandos guardados (Fase F).
 *
 * API síncrona sin Flows: el volumen es mínimo y la UI relee la lista tras
 * cada mutación. Las implementaciones son la de Android
 * (EncryptedSharedPreferences, JSON por colección, con migración desde la
 * config única anterior) y la de desktop (archivo JSON con permisos 600).
 */
interface ProfileStore {
    fun listProfiles(): List<ConnectionProfile>
    fun saveProfile(profile: ConnectionProfile)
    fun deleteProfile(id: String)

    fun listKeys(): List<SavedKey>
    fun saveKey(key: SavedKey)
    fun deleteKey(id: String)

    fun listCommands(): List<SavedCommand>
    fun saveCommand(command: SavedCommand)
    fun deleteCommand(id: String)

    /** Último perfil usado (para preseleccionarlo en la lista al entrar). */
    fun loadLastProfileId(): String?
    fun setLastProfileId(id: String?)

    /**
     * Último modo usado (Terminal/Chat). El botón "atrás" de Android termina
     * la Activity de verdad — sin bundle de `rememberSaveable` que restaurar —
     * así que sin esto el auto-reconnect siempre vuelve a Terminal aunque el
     * usuario estuviera chateando.
     */
    fun loadLastMode(): AcpMode?
    fun setLastMode(mode: AcpMode)

    /** Tabs de chat ACP con sesión viva del perfil, para retomarlos (`session/load`) tras reconectar. */
    fun loadSavedTabs(profileId: String): List<SavedTabSession>
    fun saveTabs(profileId: String, tabs: List<SavedTabSession>)

    fun resolve(profile: ConnectionProfile): TerminalConfig? =
        resolveProfile(profile, listKeys(), listCommands())
}
