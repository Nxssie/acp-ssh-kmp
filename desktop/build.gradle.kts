import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(project(":common"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.nxssie.acpssh.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "acp-ssh"
            packageVersion = "1.0.0"
        }
    }
}
