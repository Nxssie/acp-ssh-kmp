plugins {
    id("com.android.application")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

android {
    compileSdk = 36
    namespace = "com.nxssie.acpssh"

    defaultConfig {
        applicationId = "com.nxssie.acpssh"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.sshj)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)
}
