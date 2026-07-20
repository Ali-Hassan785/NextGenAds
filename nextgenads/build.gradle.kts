plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.alihassan.nextgenads"
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

    testOptions {
        unitTests {
            // Let local JVM unit tests exercise classes that touch android.jar stubs (SystemClock,
            // Log) — the stubs return default values instead of throwing "not mocked". Used by
            // ShowRateTrackerTest, whose counter/percentage logic is otherwise pure Kotlin.
            isReturnDefaultValues = true
        }
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
            artifactId = "nextgenads"
            version = "1.7.0"

            afterEvaluate {
                from(components["release"])
            }
        }
    }

    repositories {
        // Private, authenticated Maven repo (GitHub Packages). Credentials are read from
        // ~/.gradle/gradle.properties (gpr.user / gpr.key) or environment variables — never
        // hard-coded here, so they are not committed.
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // ProcessLifecycleOwner — used by AppOpenAdManager to detect app foregrounding. `api` because
    // AppOpenAdManager implements DefaultLifecycleObserver, so the type is part of our public ABI.
    api(libs.androidx.lifecycle.process)

    // Google Mobile Ads SDK (Next Generation) — exposed transitively so apps can use ad types.
    api(libs.ads.mobile.sdk)
    // User Messaging Platform (UMP) consent.
    api(libs.user.messaging.platform)
    // Shimmer placeholders shown while ads load.
    implementation(libs.shimmer)
    // Google Play In-App Updates — powers InAppUpdateManager. `api` so AppUpdateInfo (exposed by the
    // onUpdateAvailable callback) is on the public ABI.
    api(libs.app.update)
    // Google Play In-App Review — powers InAppReviewManager. `implementation`: no Play review type
    // leaks into this library's public API, so it can stay an internal detail.
    implementation(libs.review)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
