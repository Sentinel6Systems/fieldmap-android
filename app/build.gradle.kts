import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from a file outside the repository (see README, "Release builds").
// Without it, release builds are left unsigned and debug builds work as usual.
val signingFile = (findProperty("fieldmap.signing") as String?)?.let { file(it) }
val signing = Properties().apply { if (signingFile?.isFile == true) signingFile.inputStream().use { load(it) } }

android {
    namespace = "io.github.sentinel6systems.fieldmap"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sentinel6systems.fieldmap"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (signing.isNotEmpty()) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // Android's own org.json is a stub in unit tests: this is the same API, for the JVM.
    testImplementation("org.json:json:20231013")
}
