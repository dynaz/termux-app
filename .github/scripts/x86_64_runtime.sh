#!/usr/bin/env bash
# Deeper x86_64 runtime validation, run inside the android-emulator-runner against a booted x86_64
# emulator, after the app is installed. It drives the app's own bootstrap through `run-as` (a debug
# build, so the app's private files are reachable) with the Termux environment, and records every
# command and its result. Covers what can be driven without the terminal UI:
#   1. shell command execution (coreutils; the shell is x86_64),
#   2. pkg/apt update and installing a package from the TermiNow apt repository,
#   3. several concurrent shell sessions (independent processes),
#   4. SSH: install openssh, run sshd, connect over the loopback and run a command,
#   5. AI Bridge: the bridge runtime (node) runs in the bootstrap (end-to-end pairing needs the UI),
#   6. Ubuntu through proot-distro on x86_64: install, first login, shell + networking, restart.
#
# Core checks (1-2) gate the job. The rest are reported PASS/FAIL/SKIP with their output so one run
# yields a full evidence table; read the printed summary and the uploaded log.
set -uo pipefail

PKG=com.terminow
ACT=com.termux.app.TermuxActivity
DATA="/data/data/$PKG"
PREFIX="$DATA/files/usr"
HOMEDIR="$DATA/files/home"
APK="${1:-}"
LOG=runtime.txt
: > "$LOG"
declare -A RESULT

say()  { echo "$@" | tee -a "$LOG"; }
hr()   { say "======================================================================"; }
mark() { RESULT["$1"]="$2"; }

# --- 0. install the APK (if given), launch, and wait for the bootstrap to extract ----------------
adb wait-for-device
adb shell input keyevent 82 >/dev/null 2>&1 || true
if [ -n "$APK" ]; then
    hr; say "[0] Install + launch + bootstrap extraction"
    say "Installing $APK"
    adb install -r -g "$APK" 2>&1 | tail -2 | tee -a "$LOG"
    adb shell am start -W -n "$PKG/$ACT" 2>&1 | tail -3 | tee -a "$LOG"
    for i in $(seq 1 24); do
        adb shell run-as "$PKG" ls files/usr/bin/bash >/dev/null 2>&1 && break
        sleep 5
    done
    if adb shell run-as "$PKG" ls files/usr/bin/bash >/dev/null 2>&1; then
        say "bootstrap extracted: files/usr/bin/bash present"
    else
        say "bootstrap did NOT extract in time; checks will likely fail"
    fi
fi

# The app's extracted native library directory, where the proot loader lives (proot can not execute
# it from the app data directory). Derived from the installed APK path.
APKDIR="$(adb shell pm path "$PKG" 2>/dev/null | sed -n 's|package:\(.*\)/base.apk|\1|p' | tr -d '\r' | head -1)"
NLD=""; [ -n "$APKDIR" ] && NLD="$APKDIR/lib/x86_64"
say "APK dir: ${APKDIR:-<unknown>}"
say "Native library dir (proot loader): ${NLD:-<unknown>}"

# Run a script in the bootstrap with the Termux environment, as the app uid. The whole device-side
# script (environment set-up plus the payload, which bash runs from a quoted heredoc so it is taken
# literally) is base64-encoded and piped into `run-as $PKG sh`, so it travels as a single token and
# never fights `adb shell` word-splitting or the nested quoting layers.
run_bash() {
    local payload="$1" script b64
    script="$(cat <<EOF
export PREFIX='$PREFIX' HOME='$HOMEDIR' TMPDIR='$PREFIX/tmp' LANG=en_US.UTF-8 TERM=xterm-256color
export PATH="\$PREFIX/bin:\$PATH" LD_LIBRARY_PATH="\$PREFIX/lib"
export PROOT_LOADER='$NLD/libproot-loader.so' PROOT_TMP_DIR="\$TMPDIR"
for p in "\$PREFIX/lib/libtermux-exec-ld-preload.so" "\$PREFIX/lib/libtermux-exec.so"; do [ -f "\$p" ] && export LD_PRELOAD="\$p" && break; done
mkdir -p "\$TMPDIR" "\$HOME" 2>/dev/null; cd "\$HOME" 2>/dev/null
"\$PREFIX/bin/bash" --login <<'TERMINOW_PAYLOAD_EOF'
$payload
TERMINOW_PAYLOAD_EOF
EOF
)"
    b64="$(printf '%s' "$script" | base64 | tr -d '\n')"
    adb shell "echo $b64 | base64 -d | run-as $PKG sh" 2>&1
}

# --- 1. shell command execution -----------------------------------------------------------------
hr; say "[1] Shell command execution"
OUT="$(run_bash 'echo MARK_SH $(uname -m) uid=$(id -u) bins=$(ls -1 "$PREFIX/bin" | wc -l); ls -1 / >/dev/null && echo "coreutils ls: $(ls --version 2>/dev/null | head -1)"; file "$PREFIX/bin/bash" 2>/dev/null | head -1')"
say "$OUT"
echo "$OUT" | grep -q "MARK_SH x86_64" && mark shell PASS || mark shell FAIL

# --- 2. pkg/apt update + install a package ------------------------------------------------------
# Installs nodejs from the TermiNow apt repository: it is a repository package (so not already in the
# pruned bootstrap) and is also the AI Bridge runtime, so this one install covers check 5 too.
hr; say "[2] pkg/apt update and package installation (nodejs from the TermiNow repo)"
OUT="$(run_bash 'echo "--- apt update ---"; apt-get update 2>&1 | tail -15; echo "--- install nodejs ---"; pkg install -y nodejs 2>&1 | tail -25; echo "--- run ---"; command -v node && node -v 2>&1 | head -1')"
say "$OUT"
APT_UPDATED=0; echo "$OUT" | grep -qiE "Reading package lists|Fetched|Hit |Get:" && APT_UPDATED=1
echo "$OUT" | grep -qiE "^v[0-9]+\.[0-9]+" && [ "$APT_UPDATED" = 1 ] && mark apt PASS || mark apt FAIL

# --- 3. multiple concurrent shell sessions ------------------------------------------------------
hr; say "[3] Multiple concurrent shell sessions"
OUT="$(run_bash '
    rm -f "$TMPDIR"/sess_*.txt
    for i in 1 2 3; do ( for j in 1 2 3; do echo "session $i tick $j pid $BASHPID"; sleep 0.3; done > "$TMPDIR/sess_$i.txt" ) & done
    wait
    for i in 1 2 3; do echo "== session $i =="; cat "$TMPDIR/sess_$i.txt"; done
    echo "distinct_pids=$(sed -n "s/.*pid //p" "$TMPDIR"/sess_*.txt | sort -u | wc -l)"
')"
say "$OUT"
PIDS="$(echo "$OUT" | sed -n 's/distinct_pids=//p' | tr -d ' \r')"
{ [ "${PIDS:-0}" -ge 2 ] && echo "$OUT" | grep -q "session 3 tick 3"; } && mark sessions PASS || mark sessions FAIL

# --- 4. SSH over the loopback -------------------------------------------------------------------
hr; say "[4] SSH (openssh, sshd, loopback connect)"
OUT="$(run_bash '
    pkg install -y openssh 2>&1 | tail -8
    mkdir -p "$HOME/.ssh"; chmod 700 "$HOME/.ssh"
    [ -f "$HOME/.ssh/id_ed25519" ] || ssh-keygen -t ed25519 -N "" -f "$HOME/.ssh/id_ed25519" >/dev/null 2>&1
    cp "$HOME/.ssh/id_ed25519.pub" "$HOME/.ssh/authorized_keys"; chmod 600 "$HOME/.ssh/authorized_keys"
    ssh-keygen -A >/dev/null 2>&1 || true
    pkill -f "sshd.*8022" 2>/dev/null || true
    "$PREFIX/bin/sshd" -p 8022 \
        -o "PidFile=$TMPDIR/sshd.pid" -o "StrictModes=no" -o "UsePAM=no" \
        -o "PasswordAuthentication=no" -o "AuthorizedKeysFile=$HOME/.ssh/authorized_keys" 2>&1 | tail -3
    sleep 2
    ssh -p 8022 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
        -i "$HOME/.ssh/id_ed25519" "$(whoami)@127.0.0.1" "echo SSH_OK on \$(uname -m)" 2>&1 | tail -5
')"
say "$OUT"
echo "$OUT" | grep -q "SSH_OK on x86_64" && mark ssh PASS || mark ssh FAIL

# --- 5. AI Bridge runtime -----------------------------------------------------------------------
hr; say "[5] AI Bridge runtime (node in the bootstrap; end-to-end pairing needs the UI, see note)"
OUT="$(run_bash 'if command -v node >/dev/null; then node -e "console.log(\"NODE_OK\", process.arch, process.version)"; else echo "node not installed (pkg install nodejs)"; fi')"
say "$OUT"
echo "$OUT" | grep -q "NODE_OK" && mark bridge PASS || mark bridge SKIP

# --- 6. Ubuntu via proot-distro on x86_64 -------------------------------------------------------
hr; say "[6] Ubuntu via proot-distro on x86_64 (install, login, shell, networking, restart)"
if [ -z "$NLD" ]; then
    say "Native library dir for the proot loader is unknown; skipping."
    mark ubuntu SKIP
else
    OUT="$(run_bash '
        command -v proot-distro >/dev/null || { echo "proot-distro missing"; exit 0; }
        echo "=== proot self-test ==="; proot --version 2>&1 | head -2
        echo "=== install ubuntu ==="; proot-distro install ubuntu 2>&1 | tail -20
        echo "=== first login: os-release + arch ==="
        proot-distro login ubuntu -- /bin/sh -c "head -2 /etc/os-release; echo ARCH=\$(uname -m); echo WHOAMI=\$(id -un)" 2>&1 | tail -10
        echo "=== networking (apt-get update) ==="
        proot-distro login ubuntu -- /bin/sh -c "apt-get update 2>&1 | tail -6" 2>&1 | tail -8
        echo "=== restart (second login) ==="
        proot-distro login ubuntu -- /bin/sh -c "echo RELOGIN_OK on \$(uname -m)" 2>&1 | tail -3
    ')"
    say "$OUT"
    { echo "$OUT" | grep -q "ARCH=x86_64" && echo "$OUT" | grep -q "RELOGIN_OK on x86_64"; } && mark ubuntu PASS || mark ubuntu FAIL
fi

# --- summary ------------------------------------------------------------------------------------
hr; say "SUMMARY (x86_64 runtime validation)"
for k in shell apt sessions ssh bridge ubuntu; do say "  $(printf '%-9s' "$k") ${RESULT[$k]:-?}"; done
cp "$LOG" "${GITHUB_WORKSPACE:-.}/runtime.txt" 2>/dev/null || true

if [ "${RESULT[shell]:-FAIL}" = PASS ] && [ "${RESULT[apt]:-FAIL}" = PASS ]; then
    say "CORE OK (shell + apt)."
    exit 0
else
    say "CORE FAILED (shell and/or apt)."
    exit 1
fi
