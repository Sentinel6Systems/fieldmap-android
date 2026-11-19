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
    // One APK for every device has the libraries of three processor types, and the NetBird
    // library is large (about 38 MB each). Compressed in the APK, the file to download is less
    // than half the size, and Android unpacks only the libraries of the device at install time.
    packaging { jniLibs { useLegacyPackaging = true } }
}

// The NetBird client of the app is a Go library (nblink/). It is built here from source, with
// NetBird and Go at the versions pinned in nblink/go.mod: the repository holds no built library.
// The task runs only when the library is missing or its sources changed. See README, "Build".
val nblink = tasks.register<Exec>("nblink") {
    val library = layout.projectDirectory.file("libs/nblink.aar")
    inputs.files(fileTree(rootProject.file("nblink")) { include("*.go", "_patch/*", "go.mod", "go.sum", "build.sh") })
    outputs.file(library)
    commandLine(rootProject.file("nblink/build.sh").path, library.asFile.path)
}
tasks.named("preBuild") { dependsOn(nblink) }

dependencies {
    implementation(files("libs/nblink.aar").builtBy(nblink))
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    // The HTTP client MapLibre uses, at MapLibre's version: the app gives the map its own
    // client, so that the map's requests can go through the remote link too.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // Android's own org.json is a stub in unit tests: this is the same API, for the JVM.
    testImplementation("org.json:json:20231013")
}
