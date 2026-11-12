#!/usr/bin/env bash
# Audio-only ffmpeg for the fork's transcription/attachment pipeline. No network protocols.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cache="$root/build/runtime-cache"
mkdir -p "$cache" "$2/licenses/ffmpeg" "$2/licenses/lame"
ndk=${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/28.2.13676358}
tools="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$tools/llvm-ar" || { echo 'Install Android NDK 28.2.13676358 or set ANDROID_NDK_HOME' >&2; exit 1; }
fetch() {
    if ! echo "$3  $cache/$1" | sha256sum --check --status; then
        curl -fLsS --retry 3 "$2" -o "$cache/$1"
    fi
    echo "$3  $cache/$1" | sha256sum --check
}
fetch ffmpeg-n8.1.3.tar.gz https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n8.1.3 09f990289327d3ebfedf4fc15cae3f884a65dab7a5aac7bbb5b01ff83900efd9
fetch lame-3.100.tar.gz https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e
work=$(mktemp -d "$cache/audio.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT
tar xf "$cache/ffmpeg-n8.1.3.tar.gz" -C "$work"
tar xf "$cache/lame-3.100.tar.gz" -C "$work"
ff="$work/FFmpeg-n8.1.3"
lame="$work/lame-3.100"
cp "$ff/COPYING.LGPLv2.1" "$2/licenses/ffmpeg/"
cp "$lame/COPYING" "$2/licenses/lame/"
for arch in aarch64 x86_64; do
    abi=$arch
    if [[ $arch == aarch64 ]]; then abi=arm64-v8a; fi
    export CC="$tools/$arch-linux-android30-clang" AR="$tools/llvm-ar" RANLIB="$tools/llvm-ranlib" NM="$tools/llvm-nm"
    prefix="$work/$arch/prefix"
    mkdir -p "$work/$arch/lame" "$work/$arch/ffmpeg" "$1/$abi"
    (cd "$work/$arch/lame" && "$lame/configure" --host="$arch-linux-gnu" --prefix="$prefix" --disable-shared --enable-static \
        --disable-frontend --disable-decoder --disable-gtktest --disable-nasm --with-pic && make -j8 && make install)
    (cd "$work/$arch/ffmpeg" && "$ff/configure" --arch="$arch" --target-os=android --enable-cross-compile \
        --cc="$CC" --ar="$AR" --ranlib="$RANLIB" --nm="$NM" --strip="$tools/llvm-strip" \
        --disable-everything --disable-autodetect --disable-network --disable-doc --disable-debug --disable-asm \
        --disable-ffprobe --disable-ffplay --disable-avdevice --enable-ffmpeg --enable-libmp3lame \
        --enable-protocol=pipe,file --enable-demuxer=matroska,ogg,wav,mp3,mov,flac,aac \
        --enable-decoder=opus,vorbis,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,mp3,flac,aac \
        --enable-parser=opus,vorbis,mpegaudio,aac,flac --enable-encoder=libmp3lame --enable-muxer=mp3 \
        --enable-filter=aresample,aformat,anull --extra-cflags="-I$prefix/include" \
        --extra-ldflags="-L$prefix/lib -Wl,-z,max-page-size=16384" && make -j8 ffmpeg)
    install -m755 "$work/$arch/ffmpeg/ffmpeg" "$1/$abi/liboc_ffmpeg.so"
done
