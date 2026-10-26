#!/usr/bin/env bash
# Smoke tests for the x86_64 build, run inside the android-emulator-runner against a booted x86_64
# emulator. Verifies what can be checked without UI automation or a network:
#   1. the app installs and launches with no fatal crash (its x86_64 native libraries load),
#   2. the x86_64 bootstrap extracts (getZip from the per-ABI libtermux-bootstrap.so, then unzip),
#   3. an extracted bootstrap binary is x86_64 ELF.
# Running a shell/PTY, Ubuntu, SSH/SFTP, tmux and RDP are NOT automated here.
set -uo pipefail

PKG=com.terminow
ACT=com.termux.app.TermuxActivity
APK="${1:?apk path required}"

fail() {
    echo "::error::$1"
    adb logcat -d > logcat.txt 2>/dev/null || true
    tail -80 logcat.txt 2>/dev/null || true
    printf 'SMOKE_RESULT=FAIL: %s\n' "$1" > smoke-result.txt
    exit 1
}

adb wait-for-device
adb shell input keyevent 82 >/dev/null 2>&1 || true

echo "== Installing $APK =="
adb install -r -g "$APK" || fail "install failed"

adb logcat -c >/dev/null 2>&1 || true
echo "== Launching $PKG/$ACT =="
adb shell am start -W -n "$PKG/$ACT" || fail "activity launch failed"

echo "== Waiting for the bootstrap to extract =="
sleep 100

adb logcat -d > logcat.txt 2>/dev/null || true

# 1) No fatal crash, process alive.
if grep -qE "FATAL EXCEPTION" logcat.txt && grep -qE "$PKG" logcat.txt; then
    fail "fatal exception in logcat"
fi
PID="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
[ -n "$PID" ] || fail "app process not running after launch"
echo "PASS launch: running (pid $PID), no fatal exception"

# 2) Bootstrap extracted (debug build, so run-as can read the app's private files).
if ! adb shell run-as "$PKG" ls files/usr/bin/bash >/dev/null 2>&1; then
    echo "files/usr/bin listing:"; adb shell run-as "$PKG" ls files/usr/bin 2>&1 | head -40
    fail "bootstrap not extracted (files/usr/bin/bash missing)"
fi
echo "PASS bootstrap: files/usr/bin/bash present"

# 3) The extracted bash is an x86_64 ELF.
adb exec-out run-as "$PKG" cat files/usr/bin/bash > bash.bin 2>/dev/null
SIZE="$(stat -c %s bash.bin 2>/dev/null || echo 0)"
[ "$SIZE" -gt 1000 ] || fail "could not read bash from the app (size $SIZE)"
MACHINE="$(readelf -h bash.bin 2>/dev/null | sed -n 's/.*Machine:[[:space:]]*//p')"
echo "bash ELF Machine: $MACHINE"
echo "$MACHINE" | grep -qi "X86-64" || fail "extracted bash is not x86_64 ELF (got: $MACHINE)"
echo "PASS arch: extracted bash is x86_64 ELF"

printf 'SMOKE_RESULT=PASS: launch + x86_64 native libraries + bootstrap extraction + x86_64 ELF verified\n' > smoke-result.txt
cat smoke-result.txt
