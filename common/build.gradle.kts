import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.kotlin.multiplatform.library")
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    android {
        namespace = "com.nxssie.acpssh.common"
        compileSdk = 36
        minSdk = 26

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        named("commonMain") {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.io.core)
            }
        }
        named("desktopMain") {
            dependencies {
                implementation(libs.sshj)
            }
        }
        named("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}


