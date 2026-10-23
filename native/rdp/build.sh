#!/bin/bash
# Builds the built-in RDP engine of TermiNow for Android arm64-v8a, API 24:
# OpenSSL and FreeRDP as static libraries, and libterminow-rdp.so, the JNI bridge of the
# viewer, linked against them. The sources are the pinned versions of versions.env.
#
# The NDK has no aarch64 host toolchain, so on an aarch64 host the Ubuntu clang 18 and lld
# compile for aarch64-linux-android24 with the sysroot, compiler-rt and libunwind of the NDK.
# On an x86_64 host the NDK's own clang is used the same way.
#
# Usage: native/rdp/build.sh [work dir] [output dir]
#   work dir:   sources and build trees (default ~/.terminow/rdp)
#   output dir: where libterminow-rdp.so goes (default app/src/main/rdpLibs/arm64-v8a)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=versions.env
. "$HERE/versions.env"
WORK="${1:-$HOME/.terminow/rdp}"
OUT_DIR="${2:-$REPO/app/src/main/rdpLibs/$ANDROID_ABI}"
JOBS="${JOBS:-$(nproc)}"

SDK="${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$REPO/local.properties")}"
NDK_VERSION="${JITPACK_NDK_VERSION:-$(sed -n 's/^ndkVersion=//p' "$REPO/gradle.properties")}"
NDK="$SDK/ndk/$NDK_VERSION"
PREBUILT="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$PREBUILT/sysroot" ] || { echo "No NDK sysroot at $PREBUILT" >&2; exit 1; }

PREFIX="$WORK/out"
TC="$WORK/tc"
mkdir -p "$WORK" "$PREFIX"

fetch() {
    local name="$1" url="$2" tag="$3" commit="$4"
    if [ ! -d "$WORK/$name/.git" ]; then
        git clone -q --depth 1 --branch "$tag" "$url" "$WORK/$name"
    fi
    local head
    head="$(git -C "$WORK/$name" rev-parse HEAD)"
    if [ "$head" != "$commit" ]; then
        echo "$name is at $head, expected $commit" >&2
        exit 1
    fi
}

toolchain() {
    rm -rf "$TC"
    mkdir -p "$TC/bin" "$TC/resource/lib/linux/aarch64"
    local clang_major
    if [ "$(uname -m)" = "x86_64" ]; then
        CLANG="$PREBUILT/bin/clang"
        LLD="$PREBUILT/bin/ld.lld"
        LLVM_BIN="$PREBUILT/bin"
        cp -r "$PREBUILT/lib/clang/"*/include "$TC/resource/"
    else
        CLANG=/usr/bin/clang-18
        LLD=/usr/lib/llvm-18/bin/ld.lld
        LLVM_BIN=/usr/lib/llvm-18/bin
        [ -x "$CLANG" ] && [ -x "$LLD" ] || { echo "Install clang-18 and lld-18" >&2; exit 1; }
        cp -r /usr/lib/llvm-18/lib/clang/18/include "$TC/resource/"
    fi
    # compiler-rt and libunwind of the NDK, for Android
    cp "$PREBUILT/lib/clang/"*/lib/linux/libclang_rt.builtins-aarch64-android.a "$TC/resource/lib/linux/"
    cp "$PREBUILT/lib/clang/"*/lib/linux/aarch64/libunwind.a "$TC/resource/lib/linux/aarch64/"
    ln -sfn "$PREBUILT/sysroot" "$TC/sysroot"
    for x in clang clang++; do
        local driver="$CLANG"
        [ "$x" = "clang++" ] && driver="${CLANG/clang/clang++}"
        cat > "$TC/bin/$ANDROID_TARGET$ANDROID_API-$x" <<EOF
#!/bin/sh
exec $driver --target=$ANDROID_TARGET$ANDROID_API --sysroot=$TC/sysroot --rtlib=compiler-rt --unwindlib=libunwind \\
    -resource-dir=$TC/resource --ld-path=$LLD -L$TC/resource/lib/linux/aarch64 "\$@"
EOF
        chmod +x "$TC/bin/$ANDROID_TARGET$ANDROID_API-$x"
    done
    for t in ar ranlib nm strip objcopy readelf; do
        if [ -x "$LLVM_BIN/llvm-$t" ]; then ln -sf "$LLVM_BIN/llvm-$t" "$TC/bin/llvm-$t"; else ln -sf "/usr/bin/llvm-$t-18" "$TC/bin/llvm-$t"; fi
    done
    CC="$TC/bin/$ANDROID_TARGET$ANDROID_API-clang"
    CXX="$TC/bin/$ANDROID_TARGET$ANDROID_API-clang++"
}

build_openssl() {
    [ -f "$PREFIX/lib/libcrypto.a" ] && [ -f "$PREFIX/lib/libssl.a" ] && return
    local src="$WORK/openssl"
    git -C "$src" clean -q -fdx
    (cd "$src" && ./Configure linux-aarch64 no-shared no-tests no-apps no-docs no-engine no-dso no-async no-ui-console \
        -fPIC -D__ANDROID_API__="$ANDROID_API" CC="$CC" AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" \
        --prefix="$PREFIX" --libdir=lib >"$WORK/openssl-config.log" 2>&1)
    make -C "$src" -j"$JOBS" >"$WORK/openssl-build.log" 2>&1
    make -C "$src" install_sw >"$WORK/openssl-install.log" 2>&1
}

build_freerdp() {
    [ -f "$PREFIX/lib/libfreerdp.a" ] && [ -f "$PREFIX/lib/libfreerdp-client.a" ] && return
    local build="$WORK/freerdp-build"
    rm -rf "$build"
    cmake -S "$WORK/FreeRDP" -B "$build" -G Ninja \
        -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 -DANDROID=ON \
        -DCMAKE_C_COMPILER="$CC" -DCMAKE_CXX_COMPILER="$CXX" \
        -DCMAKE_AR="$TC/bin/llvm-ar" -DCMAKE_RANLIB="$TC/bin/llvm-ranlib" \
        -DCMAKE_FIND_ROOT_PATH="$PREFIX;$TC/sysroot" -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER \
        -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DCMAKE_C_FLAGS="-DANDROID -D__ANDROID_API__=$ANDROID_API" -DCMAKE_CXX_FLAGS="-DANDROID -D__ANDROID_API__=$ANDROID_API" \
        -DOPENSSL_ROOT_DIR="$PREFIX" -DOPENSSL_USE_STATIC_LIBS=ON \
        -DBUILD_SHARED_LIBS=OFF -DWITH_LIBRARY_VERSIONING=OFF -DBUILD_TESTING=OFF \
        -DWITH_CLIENT=ON -DWITH_CLIENT_COMMON=ON -DWITH_CLIENT_CHANNELS=ON -DBUILTIN_CHANNELS=ON \
        -DWITH_CLIENT_SDL=OFF -DWITH_CLIENT_SDL2=OFF -DWITH_CLIENT_SDL3=OFF -DWITH_X11=OFF -DWITH_WAYLAND=OFF \
        -DWITH_SERVER=OFF -DWITH_SERVER_CHANNELS=OFF -DWITH_SAMPLE=OFF -DWITH_PROXY=OFF -DWITH_SHADOW=OFF \
        -DWITH_PLATFORM_SERVER=OFF -DWITH_WINPR_TOOLS=OFF -DWITH_MANPAGES=OFF \
        -DWITH_OPENSSL=ON -DWITH_MBEDTLS=OFF -DWITH_INTERNAL_RC4=ON -DWITH_INTERNAL_MD4=ON -DWITH_INTERNAL_MD5=ON \
        -DWITH_FFMPEG=OFF -DWITH_SWSCALE=OFF -DWITH_DSP_FFMPEG=OFF -DWITH_FAAD2=OFF -DWITH_LAME=OFF -DWITH_SOXR=OFF \
        -DWITH_OPUS=OFF -DWITH_GSM=OFF -DWITH_CUPS=OFF -DWITH_PULSE=OFF -DWITH_ALSA=OFF -DWITH_OSS=OFF \
        -DWITH_OPENSLES=OFF -DWITH_PCSC=OFF -DWITH_SMARTCARD_EMULATE=OFF -DWITH_KRB5=OFF -DWITH_FUSE=OFF \
        -DWITH_JSON_DISABLED=ON -DWITH_AAD=OFF -DWITH_URIPARSER=OFF -DWITH_WEBVIEW=OFF -DWITH_UNICODE_BUILTIN=ON \
        -DWITH_TIMEZONE_ICU=OFF -DWITH_SIMD=ON -DWITH_CAIRO=OFF -DWITH_VIDEO_FFMPEG=OFF -DWITH_OPENH264=OFF \
        -DCHANNEL_URBDRC=OFF -DCHANNEL_RDPEAR=OFF -DCHANNEL_PRINTER=OFF -DCHANNEL_SMARTCARD=OFF -DCHANNEL_PARALLEL=OFF \
        -DCHANNEL_SERIAL=OFF -DCHANNEL_TSMF=OFF -DCHANNEL_VIDEO=OFF -DCHANNEL_RDPECAM=OFF -DCHANNEL_AUDIN=OFF \
        -DCHANNEL_RDPSND=OFF -DCHANNEL_DRIVE=OFF -DCHANNEL_LOCATION=OFF -DCHANNEL_REMDESK=OFF \
        -DUSE_UNWIND=OFF -DWITH_DEBUG_ALL=OFF \
        >"$WORK/freerdp-config.log" 2>&1
    cmake --build "$build" -j"$JOBS" >"$WORK/freerdp-build.log" 2>&1
    cmake --install "$build" >"$WORK/freerdp-install.log" 2>&1
}

fetch openssl https://github.com/openssl/openssl.git "openssl-$OPENSSL_VERSION" "$OPENSSL_COMMIT"
fetch FreeRDP https://github.com/FreeRDP/FreeRDP.git "$FREERDP_VERSION" "$FREERDP_COMMIT"
toolchain
build_openssl
build_freerdp
if [ "${STOP_AFTER_FREERDP:-0}" = 1 ]; then echo "FreeRDP built in $PREFIX"; exit 0; fi
"$HERE/build-jni.sh" "$WORK" "$OUT_DIR"
# The licenses of what the library contains go into the app with it
mkdir -p "$REPO/app/src/main/assets/licenses"
cp "$WORK/FreeRDP/LICENSE" "$REPO/app/src/main/assets/licenses/FreeRDP-LICENSE.txt"
cp "$WORK/openssl/LICENSE.txt" "$REPO/app/src/main/assets/licenses/OpenSSL-LICENSE.txt"
