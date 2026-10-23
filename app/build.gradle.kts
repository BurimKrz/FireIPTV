plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.fireiptv.player"

    defaultConfig {
        applicationId = "com.fireiptv.player"
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
