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
     * Último modo elegido al crear un perfil nuevo (Terminal/Chat): solo sirve
     * de valor por defecto para preseleccionar el tipo en el formulario de
     * "Nueva conexión" — el tipo real de conexión vive en
     * [ConnectionProfile.mode], nunca lo sobreescribe un perfil ya guardado.
     */
    fun loadLastMode(): AcpMode?
    fun setLastMode(mode: AcpMode)

    /** Tabs de chat ACP con sesión viva del perfil, para retomarlos (`session/load`) tras reconectar. */
    fun loadSavedTabs(profileId: String): List<SavedTabSession>
    fun saveTabs(profileId: String, tabs: List<SavedTabSession>)

    /**
     * Si el autoupdate de Android (ver `update/UpdateChecker`) debe ofrecer también
     * builds marcados como pre-release. Default `true`: hoy `android-build.yml`
     * publica *todos* los releases como pre-release (no hay canal estable), así que
     * `false` significaría "nunca actualizar" hasta que exista un release estable.
     */
    fun loadReceivePrereleaseUpdates(): Boolean
    fun setReceivePrereleaseUpdates(value: Boolean)

    /** Override manual de tema claro/oscuro. Default [AppTheme.SYSTEM]: sigue el sistema. */
    fun loadAppTheme(): AppTheme
    fun setAppTheme(theme: AppTheme)

    fun resolve(profile: ConnectionProfile): TerminalConfig? =
        resolveProfile(profile, listKeys(), listCommands())
}
