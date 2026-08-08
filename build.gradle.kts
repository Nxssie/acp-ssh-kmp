plugins {
    // Evita cargar los plugins varias veces (una por subproyecto).
    kotlin("multiplatform") apply false
    kotlin("plugin.compose") apply false
    kotlin("plugin.serialization") apply false
    id("com.android.application") apply false
    id("com.android.kotlin.multiplatform.library") apply false
    id("org.jetbrains.compose") apply false
}
