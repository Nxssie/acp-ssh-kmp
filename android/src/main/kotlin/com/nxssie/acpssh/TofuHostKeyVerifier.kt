package com.nxssie.acpssh

import com.nxssie.acpssh.session.PendingHostKey
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

    @Volatile private var decision: CountDownLatch? = null
    @Volatile private var accepted: AtomicBoolean? = null
    @Volatile private var pendingHost: String = ""
    @Volatile private var pendingFingerprint: String = ""

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fp = fingerprint(key)
        if (fp in store.acceptedKeys(hostname)) return true

        pendingHost = hostname
        pendingFingerprint = fp
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        decision = latch
        accepted = result
        onPending(PendingHostKey(key.algorithm, fp))
        return try {
            latch.await(DECISION_TIMEOUT_SECONDS, TimeUnit.SECONDS) && result.get()
        } catch (e: InterruptedException) {
            false
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    fun acceptHostKey() {
        store.acceptKey(pendingHost, pendingFingerprint)
        accepted?.set(true)
        decision?.countDown()
    }

    fun rejectHostKey() {
        accepted?.set(false)
        decision?.countDown()
    }

    private fun fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }

    private companion object {
        const val DECISION_TIMEOUT_SECONDS = 120L
    }
}
