import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Raw readings and outside records only (no logic). Pure JVM so :core stays testable without Android.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
