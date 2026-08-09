package com.nxssie.acpssh.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

data class GeneratedSshKey(val privateKeyPem: String, val publicKeyLine: String)

/** Genera un par de claves Ed25519 (bytes RAW seed/pub); implementado por plataforma. */
internal expect fun generateEd25519RawKeyPair(): Pair<ByteArray, ByteArray>

/**
 * Genera un keypair Ed25519 y lo codifica en el formato "openssh-key-v1" (el mismo
 * que produce `ssh-keygen -t ed25519`), sin passphrase, listo para `authorized_keys`
 * en el destino y para guardarse como `privateKeyPem` en la config de conexión.
 */
fun generateEd25519SshKey(comment: String): GeneratedSshKey {
    val (seed, pub) = generateEd25519RawKeyPair()
    return OpenSshKeyEncoder.encode(seed, pub, comment)
}

@OptIn(ExperimentalEncodingApi::class)
private object OpenSshKeyEncoder {
    private const val ALGO = "ssh-ed25519"
    private val MAGIC: ByteArray = "openssh-key-v1".encodeToByteArray() + byteArrayOf(0)

    fun encode(seed: ByteArray, pub: ByteArray, comment: String): GeneratedSshKey {
        val pubBlob = SshWriter().apply {
            writeString(ALGO)
            writeString(pub)
        }.toByteArray()

        val checkInt = Random.nextInt()
        val privSection = SshWriter().apply {
            writeInt(checkInt)
            writeInt(checkInt)
            writeString(ALGO)
            writeString(pub)
            writeString(seed + pub) // OpenSSH: sk (32) || pk (32)
            writeString(comment)
            var pad = 1
            while (size() % 8 != 0) {
                writeByte(pad.toByte())
                pad++
            }
        }.toByteArray()

        val body = SshWriter().apply {
            writeRaw(MAGIC)
            writeString("none") // cipher
            writeString("none") // kdf
            writeString(ByteArray(0)) // kdfoptions
            writeInt(1) // number of keys
            writeString(pubBlob)
            writeString(privSection)
        }.toByteArray()

        val base64 = Base64.encode(body)
        val wrapped = base64.chunked(70).joinToString("\n")
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$wrapped\n-----END OPENSSH PRIVATE KEY-----\n"
        val publicLine = "$ALGO ${Base64.encode(pubBlob)} $comment"
        return GeneratedSshKey(privateKeyPem = pem, publicKeyLine = publicLine)
    }
}

/** Escritor mínimo del formato SSH wire (uint32 big-endian + string con length-prefix). */
private class SshWriter {
    private val bytes = ArrayList<Byte>()

    fun size(): Int = bytes.size

    fun writeByte(b: Byte) {
        bytes.add(b)
    }

    fun writeRaw(data: ByteArray) {
        bytes.addAll(data.toList())
    }

    fun writeInt(value: Int) {
        bytes.add((value ushr 24).toByte())
        bytes.add((value ushr 16).toByte())
        bytes.add((value ushr 8).toByte())
        bytes.add(value.toByte())
    }

    fun writeString(data: ByteArray) {
        writeInt(data.size)
        writeRaw(data)
    }

    fun writeString(s: String) {
        writeString(s.encodeToByteArray())
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}
