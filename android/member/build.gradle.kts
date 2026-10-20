import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.backupcircle.member"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.backupcircle.member"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        // A shared debug key in the repo, so APKs built on any laptop can update each other.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // Shrinking drops unused library code (mostly icons): ~60 MB -> a few MB,
            // so the APK downloads quickly over a phone hotspot. Names are not obfuscated.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // Per-app language (English / Hindi) that applies immediately and is remembered.
    implementation(libs.androidx.appcompat)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
