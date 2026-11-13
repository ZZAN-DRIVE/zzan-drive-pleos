import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Labelled mock data (시연 데이터): plan-example stations and scenario timelines.
// The same timelines drive JVM tests and the in-app demo mode.
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

dependencies {
    api(project(":data:source-api"))

    testImplementation(project(":core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
