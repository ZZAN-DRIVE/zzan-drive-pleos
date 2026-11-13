import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from keystore.properties at the repository root (git-ignored) or from
// ZZAN_KEYSTORE* environment variables. Without them the release APK is unsigned and
// scripts/release.sh stops with instructions. Never commit the keystore or its passwords.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.zzandrive.app"
    compileSdk = 35
    useLibrary("android.car")
    defaultConfig {
        // Package name and toolchain: spec.md D-13.
        applicationId = "com.zzandrive.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // arm64-v8a: Apple Silicon emulator and head units. x86_64: Windows/Intel emulator image.
        // TTS/STT's universal-access ships libuacore.so for these ABIs.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        create("release") {
            signingValue("storeFile", "ZZAN_KEYSTORE")?.let { path ->
                storeFile = rootProject.file(path)
                storePassword = signingValue("storePassword", "ZZAN_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ZZAN_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ZZAN_KEY_PASSWORD")
            }
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        debug {
            // Diagnostics screen and debug commands are reachable from adb only in debug builds.
            manifestPlaceholders["diagExported"] = "true"
        }
        release {
            manifestPlaceholders["diagExported"] = "false"
            isMinifyEnabled = false // Vehicle SDK uses reflection; R8 stays off for now
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Character clips are already-compressed WebP: store them as is so they are read in place (memory-mapped).
    androidResources { noCompress += "webp" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data:source-mock"))
    implementation(project(":data:source-pleos"))

    // Pleos Connect SDK (public set per the SDK release note; LLM SDK is discontinued).
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
}
