#!/bin/bash
# Links libterminow-rdp.so, the JNI bridge of the viewer, against the static OpenSSL and FreeRDP
# that build.sh made. Only JNI_OnLoad and the Java_ functions are exported, the rest is hidden,
# and the library is stripped. Usage: build-jni.sh <work dir> <output dir>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/versions.env"
WORK="$1"
OUT_DIR="$2"
PREFIX="$WORK/out"
TC="$WORK/tc"
CC="$TC/bin/$ANDROID_TARGET$ANDROID_API-clang"
BUILD="$WORK/jni-build"
mkdir -p "$BUILD" "$OUT_DIR"

cat > "$BUILD/exports.map" <<'MAP'
{ global: JNI_OnLoad; Java_com_termux_app_terminow_rdp_TerminowRdpNative_*; local: *; };
MAP

"$CC" -shared -fPIC -O2 -fvisibility=hidden -DANDROID \
    -I"$PREFIX/include/freerdp3" -I"$PREFIX/include/winpr3" -I"$PREFIX/include" \
    -Wall -Wno-unused-parameter \
    -o "$BUILD/libterminow-rdp.so" "$HERE/jni/terminow_rdp.c" \
    -L"$PREFIX/lib" -Wl,--start-group -lfreerdp-client -lfreerdp -lwinpr -lssl -lcrypto -Wl,--end-group \
    -lz -llog -ljnigraphics -landroid -lm -ldl \
    -Wl,--version-script="$BUILD/exports.map" -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,noexecstack \
    -Wl,--no-undefined
"$TC/bin/llvm-strip" --strip-unneeded "$BUILD/libterminow-rdp.so"

# It must be a library for Android aarch64 that only needs libraries of Android itself
"$TC/bin/llvm-readelf" -h "$BUILD/libterminow-rdp.so" | grep -q "AArch64"
NEEDED="$("$TC/bin/llvm-readelf" -d "$BUILD/libterminow-rdp.so" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | sort | tr '\n' ' ')"
for lib in $NEEDED; do
    case "$lib" in
        libc.so|libm.so|libdl.so|liblog.so|libjnigraphics.so|libandroid.so|libz.so) ;;
        *) echo "Unexpected dependency $lib" >&2; exit 1 ;;
    esac
done
cp "$BUILD/libterminow-rdp.so" "$OUT_DIR/libterminow-rdp.so"
echo "Built $OUT_DIR/libterminow-rdp.so ($(stat -c %s "$OUT_DIR/libterminow-rdp.so") bytes), needs: $NEEDED"
