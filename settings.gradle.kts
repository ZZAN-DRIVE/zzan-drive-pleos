pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content {
                includeGroup("ai.pleos.playground")
                // TextToSpeech/SpeechToText 3.3.1 depend on universal-access in this group.
                includeGroup("ai.pleos.playground.security")
            }
        }
    }
}

rootProject.name = "zzan-drive"
include(":app")
include(":core")
include(":data:source-api")
include(":data:source-mock")
include(":data:source-pleos")
