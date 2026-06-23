plugins {
    alias(libs.plugins.android.library)
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
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Google Mobile Ads SDK (Next Generation) — exposed transitively so apps can use ad types.
    api(libs.ads.mobile.sdk)
    // User Messaging Platform (UMP) consent.
    api(libs.user.messaging.platform)
    // Shimmer placeholders shown while ads load.
    implementation(libs.shimmer)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
