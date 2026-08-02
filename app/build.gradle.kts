plugins {
    // AGP 9 ships built-in Kotlin support; the standalone kotlin("android") plugin must not be
    // applied alongside it.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.openhelm.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.openhelm.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 for shrinking, not for secrecy — the project is open source. Compose and Hilt
            // ship their own consumer keep rules; anything app-specific goes in proguard-rules.pro.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        // The video pane's diagnostic stats overlay and the simulator-only TCP transport are
        // gated on BuildConfig.DEBUG — they are development instrumentation, not shipped UI.
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":protocol"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.coroutines.android)

    implementation(libs.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Local JVM tests only — the pure decision functions behind the layout and the safety copy.
    // Deliberately not Robolectric or an instrumentation suite: what is worth locking down here is
    // arithmetic and string handling that can be checked at window sizes and against inputs no
    // emulator conveniently produces.
    testImplementation(libs.junit4)
}
