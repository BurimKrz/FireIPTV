plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val appVersion = "0.2.0"

android {
    namespace = "com.fireiptv.player"

    defaultConfig {
        applicationId = "com.fireiptv.player"
        targetSdk = 36
        // Bump appVersion, then push the tag v<appVersion>: CI builds the release and the app finds it.
        versionName = appVersion
        versionCode = appVersion.split('.').map(String::toInt).let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
    }

    // The release key lives outside the repo; its path and passwords come from ~/.gradle/gradle.properties
    // here, and from ORG_GRADLE_PROJECT_* secrets on CI. Every release must use the same key, or it
    // won't install over the copy on the TV.
    val keystore = providers.gradleProperty("fireiptv.keystore").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.gradleProperty("fireiptv.keystorePassword").get()
                keyAlias = providers.gradleProperty("fireiptv.keyAlias").get()
                keyPassword = providers.gradleProperty("fireiptv.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Fire TV sticks are ARM; the x86 FFmpeg copies only serve the emulators in debug builds.
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:player"))
    implementation(project(":feature:browse"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    debugImplementation(libs.leakcanary)
}
