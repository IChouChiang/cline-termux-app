plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin); the two Kotlin compiler
    // plugins below must match the Kotlin version AGP bundles.
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.2.10" apply false
}
