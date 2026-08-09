package com.nxssie.acpssh.crypto

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

actual fun ensureBouncyCastleProvider() {
    if (Security.getProvider("BC") == null) {
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
