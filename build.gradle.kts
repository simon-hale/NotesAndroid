buildscript {
    repositories {
        mavenCentral()
    }

    dependencies {
        // AGP 9 uses built-in Kotlin. Haze 2.0.0 is built with Kotlin 2.4.20,
        // so explicitly make the newer KGP available to AGP.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}