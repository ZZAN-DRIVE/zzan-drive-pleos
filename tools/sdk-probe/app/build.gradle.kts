plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zzandrive.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zzandrive.probe"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-probe"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // Vehicle SDK POM lists vehicle-data as runtime-only, so types like CapabilityConfig
    // are invisible at compile time unless declared explicitly.
    implementation("ai.pleos.playground:vehicle-data:1.1.11")
    // Portal shows these as "Coming Soon" (cannot apply), but the artifacts are published.
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // Serves bundled assets to WebView over https so three.js can load a .glb without file:// limits.
    implementation("androidx.webkit:webkit:1.9.0")
    // Vector animation (Lottie JSON) for the format comparison.
    implementation("com.airbnb.android:lottie:6.4.0")
    // LottieAnimationView extends AppCompatImageView, so appcompat must be on the compile classpath.
    implementation("androidx.appcompat:appcompat:1.6.1")
    // Vehicle SDK uses Kotlin reflection internally (the official sample declares it too).
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.0")
}
