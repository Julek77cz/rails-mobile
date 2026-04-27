plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cz.julek.rails"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.julek.rails"
        minSdk = 26          // Android 8.0 Oreo — UsageStatsManager + Foreground Service
        targetSdk = 35       // Android 15
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // ── AndroidX Core ──
    implementation(libs.androidx.core.ktx)

    // ── Lifecycle ──
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    // ── Jetpack Compose (BOM-managed) ──
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // ── Activity Compose ──
    implementation(libs.androidx.activity.compose)

    // ── Networking — OkHttp (WebSocket + HTTP) ──
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // ── Coroutines ──
    implementation(libs.kotlinx.coroutines.android)
}
