#!/bin/bash
# Rebuilds core/ffmpeg/src/main/jniLibs/<abi>/libffmpegJNI.so: FFmpeg with only the MPEG audio
# decoder (MP1/MP2/MP3), linked with media3's JNI wrapper. Run it after a media3 version bump.
#
#   tools/build-ffmpeg.sh [work dir, default ~/build-ffmpeg]
#
# Needs git, make and the NDK below. Sources are fetched into the work dir, never into the repo.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:-$HOME/build-ffmpeg}"
MEDIA3_VERSION="$(sed -nE 's/^media3 = "(.*)"/\1/p' "$REPO/gradle/libs.versions.toml")"
FFMPEG_BRANCH="release/6.0" # what media3's decoder_ffmpeg README recommends
NDK="${NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
MIN_SDK=25
DECODERS=(mp3) # FFmpeg's mp3 decoder handles all three MPEG audio layers
OUT="$REPO/core/ffmpeg/src/main/jniLibs"

mkdir -p "$WORK"
cd "$WORK"
[ -d media3 ] || git clone --depth 1 --branch "$MEDIA3_VERSION" https://github.com/androidx/media.git media3
[ -d ffmpeg ] || git clone --depth 1 --branch "$FFMPEG_BRANCH" https://github.com/FFmpeg/FFmpeg.git ffmpeg

MODULE="$WORK/media3/libraries/decoder_ffmpeg/src/main"
ln -sfn "$WORK/ffmpeg" "$MODULE/jni/ffmpeg"
(cd "$MODULE/jni" && ./build_ffmpeg.sh "$MODULE" "$NDK" linux-x86_64 "$MIN_SDK" "${DECODERS[@]}")

BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
LIBS="$WORK/ffmpeg/android-libs"
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    case $abi in
        armeabi-v7a) cxx="$BIN/armv7a-linux-androideabi$MIN_SDK-clang++"; extra=() ;;
        arm64-v8a) cxx="$BIN/aarch64-linux-android$MIN_SDK-clang++"; extra=(-Wl,-Bsymbolic) ;;
        x86) cxx="$BIN/i686-linux-android$MIN_SDK-clang++"; extra=() ;;
        x86_64) cxx="$BIN/x86_64-linux-android$MIN_SDK-clang++"; extra=() ;;
    esac
    mkdir -p "$OUT/$abi"
    # Same flags as media3's CMakeLists.txt; 16 KB pages for newer Android.
    "$cxx" -std=c++11 -O2 -fPIC -shared -fvisibility=hidden -fvisibility-inlines-hidden \
        -I"$WORK/ffmpeg" "$MODULE/jni/ffmpeg_jni.cc" \
        "$LIBS/$abi/libswresample.a" "$LIBS/$abi/libavcodec.a" "$LIBS/$abi/libavutil.a" \
        -landroid -llog -lm -static-libstdc++ -Wl,-z,max-page-size=16384 "${extra[@]}" \
        -o "$OUT/$abi/libffmpegJNI.so"
    "$BIN/llvm-strip" --strip-unneeded "$OUT/$abi/libffmpegJNI.so"
done
ls -l "$OUT"/*/libffmpegJNI.so
