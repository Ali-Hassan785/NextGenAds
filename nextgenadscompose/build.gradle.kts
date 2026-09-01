plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    `maven-publish`
}

android {
    namespace = "com.alihassan.nextgenadscompose"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.keep")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        // Jetpack Compose. AGP wires the bundled Kotlin Compose compiler when this is enabled.
        compose = true
    }

    // Expose a single publishable "release" variant (with sources) for maven-publish / JitPack.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.Ali-Hassan785"
            artifactId = "nextgenads-compose"
            version = "1.8.0"

            afterEvaluate {
                from(components["release"])
            }
        }
    }

    repositories {
        // Same private GitHub Packages repo as :nextgenads. Credentials come from
        // ~/.gradle/gradle.properties (gpr.user / gpr.key) or CI env vars — never committed here.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Ali-Hassan785/NextGenAds")
            credentials {
                username = (findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
                password = (findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

dependencies {
    // The View/XML ads library this module wraps for Compose. `api` so Compose callers get every
    // ad type (AdType, BannerSize, NativeTemplate, RewardItem, the managers …) transitively.
    api(project(":nextgenads"))

    // Jetpack Compose (versions managed by the BOM). `api`, not `implementation`: the compose
    // artifacts below are exposed on our api surface without versions of their own, so the BOM has to
    // reach consumers' compile classpath too. With `implementation` the BOM lands only in the runtime
    // variant, and a consumer resolving this module gets "androidx.compose.foundation:foundation:"
    // with an empty version.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
