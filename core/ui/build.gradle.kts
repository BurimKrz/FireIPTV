plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.fireiptv.core.ui"
    buildFeatures { compose = true }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.foundation)
    api(libs.tv.material)
    // tv-material has no text fields; Material 3's are the recommended stand-in on TV.
    api(libs.compose.material3)
}
