package com.nxssie.acpssh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Preferencias cifradas (AndroidX Security) compartidas por [SecureStore]
 * (host keys TOFU) y [SecureStoreProfileStore] (perfiles/claves/comandos):
 * mismo archivo `acp_ssh_secure`, claves distintas. No se escribe ningún
 * secreto en claro.
 */
internal fun secureSharedPreferences(context: Context): SharedPreferences {
    val appContext = context.applicationContext
    val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        appContext,
        "acp_ssh_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

/** Host keys aceptadas por Trust On First Use. */
class SecureStore(context: Context) {

    private val prefs = secureSharedPreferences(context)

    fun acceptedKeys(host: String): Set<String> =
        prefs.getStringSet(HOST_KEY_PREFIX + host, emptySet())?.toSet() ?: emptySet()

    fun acceptKey(host: String, fingerprint: String) {
        prefs.edit()
            .putStringSet(HOST_KEY_PREFIX + host, acceptedKeys(host) + fingerprint)
            .apply()
    }

    private companion object {
        const val HOST_KEY_PREFIX = "hostkey:"
    }
}
