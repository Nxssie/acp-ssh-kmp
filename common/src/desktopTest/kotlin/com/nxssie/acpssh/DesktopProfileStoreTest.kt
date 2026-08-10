package com.nxssie.acpssh

import com.nxssie.acpssh.profile.ConnectionProfile
import com.nxssie.acpssh.profile.SavedCommand
import com.nxssie.acpssh.profile.SavedKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * El store de desktop es código nuevo en desktopMain (hoy no había ninguna
 * persistencia): se prueba aquí el round-trip a disco, el upsert por id, el
 * borrado y los permisos 600 del archivo.
 */
class DesktopProfileStoreTest {

    private fun tempStore(): Pair<DesktopProfileStore, File> {
        val dir = Files.createTempDirectory("acp-profile-store-test").toFile()
        val file = File(dir, "profiles.json")
        return DesktopProfileStore(file) to file
    }

    @Test
    fun emptyWhenFileDoesNotExist() {
        val (store, _) = tempStore()
        assertTrue(store.listProfiles().isEmpty())
        assertTrue(store.listKeys().isEmpty())
        assertTrue(store.listCommands().isEmpty())
        assertNull(store.loadLastProfileId())
    }

    @Test
    fun persistsAcrossInstances() {
        val (store, file) = tempStore()
        val key = SavedKey("k1", "clave", "PEM")
        val profile = ConnectionProfile("p1", "srv", "h", 22, "u", "k1")
        store.saveKey(key)
        store.saveProfile(profile)
        store.saveCommand(SavedCommand("c1", "cmd", "tmux"))
        store.setLastProfileId("p1")

        val reloaded = DesktopProfileStore(file)
        assertEquals(listOf(profile), reloaded.listProfiles())
        assertEquals(listOf(key), reloaded.listKeys())
        assertEquals("cmd", reloaded.listCommands().single().label)
        assertEquals("p1", reloaded.loadLastProfileId())
    }

    @Test
    fun saveUpsertsById() {
        val (store, _) = tempStore()
        store.saveKey(SavedKey("k1", "vieja", "PEM1"))
        store.saveKey(SavedKey("k1", "nueva", "PEM2"))
        assertEquals(listOf(SavedKey("k1", "nueva", "PEM2")), store.listKeys())
    }

    @Test
    fun deleteRemovesAndClearsLastProfile() {
        val (store, _) = tempStore()
        store.saveProfile(ConnectionProfile("p1", "a", "h", 22, "u", "k1"))
        store.saveProfile(ConnectionProfile("p2", "b", "h", 22, "u", "k1"))
        store.setLastProfileId("p1")
        store.deleteProfile("p1")
        assertEquals(listOf("p2"), store.listProfiles().map { it.id })
        assertNull(store.loadLastProfileId())
        store.deleteKey("k1")
        store.deleteCommand("c1")
    }

    @Test
    fun fileHasOwnerOnlyPermissions() {
        val (store, file) = tempStore()
        store.saveKey(SavedKey("k1", "clave", "PEM"))
        val perms = Files.getPosixFilePermissions(file.toPath())
        assertEquals(PosixFilePermissions.fromString("rw-------"), perms)
    }

    @Test
    fun corruptFileLoadsAsEmpty() {
        val (store, file) = tempStore()
        file.writeText("{ no json valido")
        assertTrue(store.listProfiles().isEmpty())
    }
}
