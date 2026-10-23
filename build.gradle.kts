import com.android.build.api.dsl.CommonExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

// Shared SDK levels for every Android module (see the implementation plan).
subprojects {
    listOf("com.android.application", "com.android.library", "com.android.test").forEach { id ->
        plugins.withId(id) {
            extensions.configure<CommonExtension>("android") {
                compileSdk {
                    version = release(37) { minorApiLevel = 2 }
                }
                defaultConfig.minSdk = 25
                compileOptions.sourceCompatibility = JavaVersion.VERSION_17
                compileOptions.targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}
