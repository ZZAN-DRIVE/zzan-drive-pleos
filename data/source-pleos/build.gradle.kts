// Real vehicle data through the Pleos SDK (Vehicle, FusedLocation, NaviHelper). Android library.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zzandrive.data.pleos"
    compileSdk = 35
    useLibrary("android.car")
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":data:source-api"))
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // Vehicle's POM lists vehicle-data as runtime-only; declare it so its types are visible at compile time.
    implementation("ai.pleos.playground:vehicle-data:1.1.11")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
}
