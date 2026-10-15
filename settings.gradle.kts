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
    }
}

rootProject.name = "Rodet"

// Fase 1: nucleo en Kotlin/JVM puro. Los modulos Android llegan en la fase 2.
include(":core:model")
include(":core:extract")
include(":core:tags")
include(":core:inventory")

// Por ahora solo el lector de tags de depuracion (adelanto de la fase 3).
include(":app")
include(":actualizador")
