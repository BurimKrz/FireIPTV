plugins {
    alias(libs.plugins.android.library)
}

// FFmpeg audio decoding for formats the device lacks (MP2 on Fire TV). The Java sources are
// media3's decoder_ffmpeg at the same version as media3; the native library is prebuilt by
// tools/build-ffmpeg.sh into src/main/jniLibs, so normal builds need no NDK.
android {
    namespace = "com.fireiptv.core.ffmpeg"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(libs.media3.decoder)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.annotation)
    compileOnly(libs.checker.qual)
}
