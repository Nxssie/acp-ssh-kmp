package com.nxssie.acpssh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nxssie.acpssh.session.TerminalConfig

/**
 * Preferencias cifradas (AndroidX Security): clave privada PEM, config de
 * conexión y host keys aceptadas (TOFU). No se escribe ningún secreto en claro.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "acp_ssh_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveConfig(config: TerminalConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putString(KEY_PORT, config.port.toString())
            .putString(KEY_USER, config.username)
            .putString(KEY_PEM, config.privateKeyPem)
            .putString(KEY_COMMAND, config.remoteCommand.orEmpty())
            .apply()
    }

    fun loadConfig(): TerminalConfig? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val pem = prefs.getString(KEY_PEM, null) ?: return null
        return TerminalConfig(
            host = host,
            port = prefs.getString(KEY_PORT, null)?.toIntOrNull() ?: 22,
            username = user,
            privateKeyPem = pem,
            remoteCommand = prefs.getString(KEY_COMMAND, null)?.takeIf { it.isNotBlank() },
        )
    }

    fun acceptedKeys(host: String): Set<String> =
        prefs.getStringSet(HOST_KEY_PREFIX + host, emptySet())?.toSet() ?: emptySet()

    fun acceptKey(host: String, fingerprint: String) {
        prefs.edit()
            .putStringSet(HOST_KEY_PREFIX + host, acceptedKeys(host) + fingerprint)
            .apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USER = "user"
        const val KEY_PEM = "pem"
        const val KEY_COMMAND = "command"
        const val HOST_KEY_PREFIX = "hostkey:"
    }
}
