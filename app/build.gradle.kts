plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val appVersion = "1.0.3"

/** FireIPTV Beta's version: push the tag v<betaVersion> and CI publishes it as a GitHub pre-release. */
val betaVersion = "1.1.0-beta.7"

/** 1.0.2 → 10002. A beta adds two digits for its number: 1.1.0-beta.3 → 1010003. */
fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = version.substringBefore('-').split('.').map(String::toInt)
    val code = major * 10_000 + minor * 100 + patch
    val beta = version.substringAfter("-beta.", "").toIntOrNull() ?: return code
    return code * 100 + beta
}

android {
    namespace = "com.fireiptv.player"

    defaultConfig {
        applicationId = "com.fireiptv.player"
        targetSdk = 36
    }

    // Two apps from one codebase, installed side by side: FireIPTV, and FireIPTV Beta for trying
    // new things (its own data, settings and updates). Bump the version, then push the tag
    // v<version>: CI builds that app's release and the app finds it.
    flavorDimensions += "edition"
    productFlavors {
        create("stable") {
            dimension = "edition"
            versionName = appVersion
            versionCode = versionCodeOf(appVersion)
        }
        create("beta") {
            dimension = "edition"
            applicationIdSuffix = ".beta"
            versionName = betaVersion
            versionCode = versionCodeOf(betaVersion)
        }
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
    implementation(project(":core:log"))
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
