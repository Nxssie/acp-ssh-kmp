plugins {
    id("com.android.application")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

/** Nº de commits como versionCode: crece con cada build, sin llevarlo a mano. */
fun gitCommitCount(): Int {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    return output.toIntOrNull() ?: 1
}

android {
    compileSdk = 36
    namespace = "com.nxssie.acpssh"

    defaultConfig {
        applicationId = "com.nxssie.acpssh"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount()
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        getByName("debug") {
            // Firma de debug fija y committeada: sin esto, cada runner de CI genera
            // un keystore nuevo con clave distinta y el APK deja de poder
            // instalarse "encima" del anterior (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Keystore de release autofirmado (NUNCA committeado): la ruta y las
        // contraseñas vienen de env vars (~/.config/fish/conf.d/secrets.fish en
        // local, GitHub Actions secrets en CI — ver ACP_SSH_KMP_RELEASE_* en
        // shell/secrets.fish.example de harnxss). Firmar con debug (autofirmado
        // pero sin identidad estable entre máquinas) es lo que hacía saltar el
        // aviso de Play Protect al distribuir fuera de Play Store; sin estas env
        // vars, `assembleRelease` produce un APK sin firmar (fallo explícito al
        // instalar, no una firma incorrecta silenciosa).
        val releaseKeystorePath = System.getenv("ACP_SSH_KMP_RELEASE_KEYSTORE_PATH")
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ACP_SSH_KMP_RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ACP_SSH_KMP_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("ACP_SSH_KMP_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.sshj)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    // Notificación de "permiso pendiente" (AcpNotifier): NotificationCompat +
    // detección de foreground vía ProcessLifecycleOwner.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
}
