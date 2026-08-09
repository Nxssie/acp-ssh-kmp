package com.nxssie.acpssh

import com.nxssie.acpssh.session.PendingHostKey
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verificación de host key por Trust On First Use: si la huella ya fue
 * aceptada se conecta; si no, bloquea la conexión, notifica a la UI y espera
 * la decisión del usuario ([acceptHostKey]/[rejectHostKey]).
 */
class TofuHostKeyVerifier(
    private val store: SecureStore,
    private val onPending: (PendingHostKey) -> Unit,
) : HostKeyVerifier {

    /** Estado inmutable de una verificación en curso: evita que una decisión
     *  tardía (reconexión, segunda clave del mismo host) acepte la huella
     *  equivocada. */
    private class Pending(val host: String, val fingerprint: String) {
        val latch = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
    }

    @Volatile private var pending: Pending? = null

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fp = fingerprint(key)
        if (fp in store.acceptedKeys(hostname)) return true

        val p = Pending(hostname, fp)
        pending = p
        onPending(PendingHostKey(key.algorithm, fp))
        return try {
            p.latch.await(DECISION_TIMEOUT_SECONDS, TimeUnit.SECONDS) && p.accepted.get()
        } catch (e: InterruptedException) {
            false
        } finally {
            // Solo limpia si nadie inició otra verificación mientras tanto.
            if (pending === p) pending = null
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    fun acceptHostKey() {
        val p = pending ?: return
        store.acceptKey(p.host, p.fingerprint)
        p.accepted.set(true)
        p.latch.countDown()
    }

    fun rejectHostKey() {
        val p = pending ?: return
        p.accepted.set(false)
        p.latch.countDown()
    }

    /**
     * SHA-256 sobre el blob de la clave en formato wire SSH (no `key.encoded`, que
     * es la codificación X.509 de Java y nunca coincide con `ssh-keygen -lf`).
     */
    private fun fingerprint(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }

    private companion object {
        const val DECISION_TIMEOUT_SECONDS = 120L
    }
}
