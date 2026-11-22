import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.squareup.wire")
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
        versionCode = 3
        versionName = "0.1.2"
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

// The messages of Meshtastic radios (mesh/): Meshtastic's protobuf definitions, at the release
// pinned here and checked against its SHA-256, made into Kotlin classes by Wire. The repository
// holds none of Meshtastic's files. See README, "Build".
val meshtasticVersion = "2.8.3"
val meshtasticSha256 = "8d43d2927df93f7000e2473e40ecdaf3fc48992577582fb107979611dc44ee50"
val meshtasticFiles = listOf("admin", "atak", "channel", "config", "connection_status", "device_ui", "field_metadata", "mesh",
    "module_config", "portnums", "telemetry", "xmodem")
val meshtasticArchive = layout.buildDirectory.file("meshtastic/protobufs-$meshtasticVersion.tar.gz")
val meshtasticGet = tasks.register("meshtasticGet") {
    val archive = meshtasticArchive
    outputs.file(archive)
    doLast {
        val file = archive.get().asFile
        fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (file.isFile && sha256(file) == meshtasticSha256) return@doLast
        file.parentFile.mkdirs()
        uri("https://github.com/meshtastic/protobufs/archive/refs/tags/v$meshtasticVersion.tar.gz").toURL().openStream().use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val got = sha256(file)
        if (got != meshtasticSha256) {
            file.delete()
            throw GradleException("Meshtastic protobufs v$meshtasticVersion: SHA-256 is $got, expected $meshtasticSha256")
        }
    }
}
val meshtasticProtos = layout.buildDirectory.dir("meshtastic/proto")
val meshtasticUnpack = tasks.register<Sync>("meshtasticUnpack") {
    dependsOn(meshtasticGet)
    from(tarTree(resources.gzip(meshtasticArchive))) {
        include(meshtasticFiles.map { "*/meshtastic/$it.proto" })
        eachFile { path = path.substringAfter('/') }
    }
    includeEmptyDirs = false
    into(meshtasticProtos)
}

wire {
    sourcePath { srcDir(meshtasticProtos) }
    // Fieldmap's own messages over the mesh (app/src/main/proto/fieldmap/mesh.proto).
    sourcePath { srcDir("src/main/proto") }
    // Only what the app talks to its radio with, and what that reaches.
    root("meshtastic.ToRadio", "meshtastic.FromRadio", "meshtastic.AdminMessage", "meshtastic.Data", "meshtastic.Routing", "fieldmap.MeshMessage")
    kotlin {}
}
tasks.matching { it.name.startsWith("generate") && it.name.endsWith("Protos") }.configureEach { dependsOn(meshtasticUnpack) }

// Routes that follow roads and trails (geo/Router.kt): the BRouter engine (MIT), its Java sources
// at the release pinned here and checked against its SHA-256, compiled with the app; and its car
// and hiking profiles, as assets. The repository holds none of BRouter's files. See README, "Build".
val brouterVersion = "1.7.10"
val brouterSha256 = "cb83f220332de8223476e029a9b075157fb74401c69a991ceaa013486c29760f"
val brouterModules = listOf("brouter-core", "brouter-util", "brouter-expressions", "brouter-codec", "brouter-mapaccess")
val brouterProfiles = listOf("lookups.dat", "car-vario.brf", "hiking-mountain.brf")
val brouterArchive = layout.buildDirectory.file("brouter/brouter-$brouterVersion.tar.gz")
val brouterGet = tasks.register("brouterGet") {
    val archive = brouterArchive
    outputs.file(archive)
    doLast {
        val file = archive.get().asFile
        fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (file.isFile && sha256(file) == brouterSha256) return@doLast
        file.parentFile.mkdirs()
        uri("https://github.com/abrensch/brouter/archive/refs/tags/v$brouterVersion.tar.gz").toURL().openStream().use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val got = sha256(file)
        if (got != brouterSha256) {
            file.delete()
            throw GradleException("BRouter v$brouterVersion: SHA-256 is $got, expected $brouterSha256")
        }
    }
}
val brouterJava = layout.buildDirectory.dir("brouter/java")
val brouterUnpack = tasks.register<Sync>("brouterUnpack") {
    dependsOn(brouterGet)
    from(tarTree(resources.gzip(brouterArchive))) {
        include(brouterModules.map { "*/$it/src/main/java/**" })
        eachFile { path = path.substringAfter("/src/main/java/") }
    }
    // The licence goes with the code, and into the APK with the profiles.
    from(tarTree(resources.gzip(brouterArchive))) {
        include("*/LICENSE")
        eachFile { path = "btools/LICENSE" }
    }
    includeEmptyDirs = false
    into(brouterJava)
}
val brouterAssets = layout.buildDirectory.dir("brouter/assets")
val brouterAssetsUnpack = tasks.register<Sync>("brouterAssets") {
    dependsOn(brouterGet)
    from(tarTree(resources.gzip(brouterArchive))) {
        include(brouterProfiles.map { "*/misc/profiles2/$it" } + "*/LICENSE")
        eachFile { path = "brouter/" + if (name == "LICENSE") "LICENSE.txt" else name }
    }
    includeEmptyDirs = false
    into(brouterAssets)
}
android.sourceSets.getByName("main") {
    java.srcDir(brouterJava)
    assets.srcDir(brouterAssets)
}
tasks.named("preBuild") { dependsOn(brouterUnpack, brouterAssetsUnpack) }

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
