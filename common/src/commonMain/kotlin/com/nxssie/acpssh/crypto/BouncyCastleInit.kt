package com.nxssie.acpssh.crypto

/**
 * Registra BouncyCastle como proveedor JCE "BC". Necesario porque SSHJ resuelve
 * algoritmos de key exchange modernos (p.ej. `curve25519-sha256` / X25519) contra
 * el proveedor "BC"; en Android el proveedor "BC" del sistema es una versión
 * recortada sin esos algoritmos, así que hay que sustituirlo por el completo.
 */
expect fun ensureBouncyCastleProvider()
