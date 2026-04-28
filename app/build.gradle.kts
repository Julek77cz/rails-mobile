plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

android {
    namespace = "cz.julek.rails"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.julek.rails"
        minSdk = 26          // Android 8.0 Oreo — UsageStatsManager + Foreground Service
        targetSdk = 35       // Android 15
        versionCode = 2
        versionName = "2.0.0"

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

    // ── Navigation Compose ──
    implementation(libs.androidx.navigation.compose)

    // ── Kotlin Serialization (for type-safe navigation routes) ──
    implementation(libs.kotlinx.serialization.json)

    // ── Coroutines ──
    implementation(libs.kotlinx.coroutines.android)

    // ── Firebase (BOM-managed) ──
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database)
}
