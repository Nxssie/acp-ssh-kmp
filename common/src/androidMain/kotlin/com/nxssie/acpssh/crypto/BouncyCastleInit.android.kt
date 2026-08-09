package com.nxssie.acpssh.crypto

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

actual fun ensureBouncyCastleProvider() {
    // Android trae un "BC" recortado (sin X25519/Ed25519 completos): hay que
    // quitarlo antes de insertar el BouncyCastle real, porque no puede haber
    // dos proveedores con el mismo nombre.
    Security.removeProvider("BC")
    Security.insertProviderAt(BouncyCastleProvider(), 1)
}
