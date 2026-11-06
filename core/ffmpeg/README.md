# :core:ffmpeg

Plays audio the device has no decoder for. Fire TV has none for MP2 (MPEG-1 Layer II), which most Albanian channels use, so those played silent. The player turns on extension renderers in "on" mode, so FFmpeg only takes a track when the platform decoders can't. AAC and AC-3 still go to the hardware.

- `src/main/java`: copied unchanged from [androidx/media](https://github.com/androidx/media) `libraries/decoder_ffmpeg`, at the same tag as `media3` in `gradle/libs.versions.toml` (Apache 2.0, headers kept). The video renderer is left out.
- `src/main/jniLibs`: `libffmpegJNI.so` for each ABI, built by `tools/build-ffmpeg.sh` from FFmpeg `release/6.0` with only the `mp3` decoder (covers layers I to III) and media3's `ffmpeg_jni.cc`.

When `media3` is bumped, copy the Java files from the new tag and rerun the script. The JNI wrapper and the Java classes must come from the same version.

FFmpeg is LGPL 2.1+. This build is configured without GPL parts. The script and these notes are how to rebuild the library from source.
