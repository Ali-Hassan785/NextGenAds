plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Firebase's google-services plugin requires a google-services.json. Apply it only when that file
// is present so the sample still builds without Firebase configured — drop your json into app/ to
// activate Analytics reporting.
if (project.file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.alihassn.nextgenSample"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.alihassn.nextgenSample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        // BuildConfig.DEBUG gates the UMP test-device hash so it can't ship in release builds.
        buildConfig = true
        // Jetpack Compose — powers ComposeAdsActivity, which demos every ad via :nextgenadscompose.
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
  implementation(project(":nextgenads"))
    // Compose wrapper over :nextgenads — used by ComposeAdsActivity.
    implementation(project(":nextgenadscompose"))

//    implementation("com.github.Ali-Hassan785.NextGenAds:nextgenads:1.4.0")
//    implementation("com.github.Ali-Hassan785.NextGenAds:nextgenads-compose:1.4.0")

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Jetpack Compose for the ComposeAdsActivity demo screen (versions via the BOM).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}