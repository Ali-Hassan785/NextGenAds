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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Private GitHub Packages repo that hosts com.github.Ali-Hassan785:nextgenads.
        // Credentials come from ~/.gradle/gradle.properties (gpr.user / gpr.key) or CI env vars —
        // never committed. gpr.key must be a GitHub PAT with the read:packages scope.
        maven {
            url = uri("https://maven.pkg.github.com/Ali-Hassan785/NextGenAds")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "NextGenSample"
include(":app")
include(":nextgenads")
