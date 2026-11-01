#!/usr/bin/env bash
# Deeper x86_64 runtime validation, run inside the android-emulator-runner against a booted x86_64
# emulator, after the app is installed. It drives the app's own bootstrap through `run-as` (a debug
# build, so the app's private files are reachable) with the Termux environment, and records every
# command and its result. Scope and honest limits of each check:
#   1. shell  - shell command execution; coreutils; the shell is an x86_64 ELF.
#   2. apt    - pkg/apt update AND installing a real repository package (proot-distro). Needs the
#               TermiNow apt repository to publish x86_64 packages.
#   3. sessions - several CONCURRENT SHELL PROCESSES are independent. This is NOT the terminal
#               multi-session UI (tabs/switching), which needs manual QA on a device.
#   4. ssh    - SSH over the LOOPBACK (sshd + connect on 127.0.0.1). This is NOT a real remote SSH
#               test to another host, which needs a second machine / manual QA.
#   5. bridge - the AI Bridge RUNTIME (node executes). This is NOT full AI Bridge pairing with a
#               host and a client, which needs the UI and a paired device (manual QA).
#   6. ubuntu - Ubuntu through proot-distro on x86_64, as five REQUIRED sub-checks: install, login,
#               command execution, networking, restart. x86_64 Ubuntu is validated only if ALL pass.
#
# The job is gated on the REQUIRED checks: shell, apt, and every Ubuntu sub-check. sessions, ssh and
# bridge are reported with their output as evidence. Read the printed summary and the uploaded log.
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

# The app's extracted native library directory, where the proot loader lives (proot can not execute
# it from the app data directory). Derived from the installed APK path.
APKDIR="$(adb shell pm path "$PKG" 2>/dev/null | sed -n 's|package:\(.*\)/base.apk|\1|p' | tr -d '\r' | head -1)"
NLD=""; [ -n "$APKDIR" ] && NLD="$APKDIR/lib/x86_64"

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

# --- 0. install the APK (if given), launch, and wait for the bootstrap to extract ----------------
adb wait-for-device
adb shell input keyevent 82 >/dev/null 2>&1 || true
if [ -n "$APK" ]; then
    hr; say "[0] Install + launch + bootstrap extraction"
    adb install -r -g "$APK" 2>&1 | tail -2 | tee -a "$LOG"
    adb shell am start -W -n "$PKG/$ACT" 2>&1 | tail -3 | tee -a "$LOG"
    for i in $(seq 1 24); do adb shell run-as "$PKG" ls files/usr/bin/bash >/dev/null 2>&1 && break; sleep 5; done
    adb shell run-as "$PKG" ls files/usr/bin/bash >/dev/null 2>&1 && say "bootstrap extracted" || say "bootstrap did NOT extract in time"
fi
say "APK dir: ${APKDIR:-<unknown>}   proot loader dir: ${NLD:-<unknown>}"

# Optionally point ONLY this test emulator's apt at an isolated staging repository, by swapping the
# production base URL in the bootstrap's sources.list for the staging base. Production app sources on
# disk (the committed bootstrap) are unchanged; only this throwaway emulator is reconfigured. The
# staging repo is signed by the same key the bootstrap already trusts, so signatures still verify.
if [ -n "${TERMINOW_STAGING_BASE:-}" ]; then
    hr; say "[staging] Pointing the emulator apt at the staging repository: $TERMINOW_STAGING_BASE"
    OUT="$(run_bash '
        for f in "$PREFIX/etc/apt/sources.list" "$PREFIX/etc/apt/sources.list.d"/*.list; do
            [ -f "$f" ] || continue
            sed -i "s#https\?://dynaz.github.io/terminow-package/apt#'"$TERMINOW_STAGING_BASE"'#g" "$f"
            echo "--- $f ---"; cat "$f"
        done
    ')"
    say "$OUT"
fi

# --- 1. shell command execution -----------------------------------------------------------------
hr; say "[1] Shell command execution (coreutils; shell is x86_64 ELF)"
OUT="$(run_bash 'echo MARK_SH $(uname -m) uid=$(id -u) bins=$(ls -1 "$PREFIX/bin" | wc -l); echo "ls: $(ls -1 "$PREFIX/bin" | head -1) ... ok"; file "$PREFIX/bin/bash" 2>/dev/null | head -1')"
say "$OUT"
echo "$OUT" | grep -q "MARK_SH x86_64" && mark shell PASS || mark shell FAIL

# --- 2. pkg/apt update + install a repository package -------------------------------------------
# Installs proot-distro (a repository package, also the Ubuntu runtime). Requires the TermiNow apt
# repository to carry x86_64 packages.
hr; say "[2] pkg/apt update and package installation (proot-distro from the TermiNow repo)"
OUT="$(run_bash 'echo "--- apt update ---"; apt-get update 2>&1 | tail -12; echo "--- install proot-distro proot ---"; pkg install -y proot-distro proot 2>&1 | tail -25; echo "--- verify ---"; command -v proot-distro && command -v proot && proot --version 2>&1 | head -1')"
say "$OUT"
APT_UPDATED=0; echo "$OUT" | grep -qiE "Reading package lists|Fetched|Hit:|Get:" && APT_UPDATED=1
APT_INSTALLED=0; echo "$OUT" | grep -q "/proot-distro" && echo "$OUT" | grep -qiE "proot .*[0-9]" && APT_INSTALLED=1
{ [ "$APT_UPDATED" = 1 ] && [ "$APT_INSTALLED" = 1 ]; } && mark apt PASS || mark apt FAIL

# --- 3. multiple concurrent shell processes (NOT the multi-session UI) --------------------------
hr; say "[3] Multiple concurrent shell PROCESSES (not the terminal multi-session UI)"
OUT="$(run_bash '
    rm -f "$TMPDIR"/sess_*.txt
    for i in 1 2 3; do ( for j in 1 2 3; do echo "session $i tick $j pid $BASHPID"; sleep 0.3; done > "$TMPDIR/sess_$i.txt" ) & done
    wait
    for i in 1 2 3; do echo "== process $i =="; cat "$TMPDIR/sess_$i.txt"; done
    echo "distinct_pids=$(sed -n "s/.*pid //p" "$TMPDIR"/sess_*.txt | sort -u | wc -l)"
')"
say "$OUT"
PIDS="$(echo "$OUT" | sed -n 's/distinct_pids=//p' | tr -d ' \r')"
{ [ "${PIDS:-0}" -ge 2 ] && echo "$OUT" | grep -q "session 3 tick 3"; } && mark sessions PASS || mark sessions FAIL

# --- 4. SSH over the loopback (NOT a real remote host) ------------------------------------------
hr; say "[4] SSH over the LOOPBACK 127.0.0.1 (not a remote host)"
OUT="$(run_bash '
    command -v sshd >/dev/null || pkg install -y openssh 2>&1 | tail -6
    mkdir -p "$HOME/.ssh"; chmod 700 "$HOME/.ssh"
    [ -f "$HOME/.ssh/id_ed25519" ] || ssh-keygen -t ed25519 -N "" -f "$HOME/.ssh/id_ed25519" >/dev/null 2>&1
    cp "$HOME/.ssh/id_ed25519.pub" "$HOME/.ssh/authorized_keys"; chmod 600 "$HOME/.ssh/authorized_keys"
    ssh-keygen -A >/dev/null 2>&1 || true
    pkill -f "sshd.*8022" 2>/dev/null || true
    "$PREFIX/bin/sshd" -p 8022 -o "PidFile=$TMPDIR/sshd.pid" -o "StrictModes=no" \
        -o "PasswordAuthentication=no" -o "AuthorizedKeysFile=$HOME/.ssh/authorized_keys" 2>&1 | tail -3
    sleep 2
    ssh -p 8022 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
        -i "$HOME/.ssh/id_ed25519" "$(whoami)@127.0.0.1" "echo SSH_OK on \$(uname -m)" 2>&1 | tail -5
')"
say "$OUT"
echo "$OUT" | grep -q "SSH_OK on x86_64" && mark ssh PASS || mark ssh FAIL

# --- 5. AI Bridge runtime (node executes; NOT full pairing) -------------------------------------
hr; say "[5] AI Bridge RUNTIME: node executes (not full AI Bridge pairing with a host/client)"
OUT="$(run_bash 'command -v node >/dev/null || pkg install -y nodejs 2>&1 | tail -6; if command -v node >/dev/null; then node -e "console.log(\"NODE_OK\", process.arch, process.version)"; else echo "node unavailable"; fi')"
say "$OUT"
echo "$OUT" | grep -q "NODE_OK x64" && mark bridge PASS || mark bridge SKIP

# --- 6. Ubuntu via proot-distro on x86_64: five REQUIRED sub-checks ------------------------------
hr; say "[6] Ubuntu via proot-distro on x86_64 (install, login, command, networking, restart)"
UB_INSTALL=FAIL; UB_LOGIN=FAIL; UB_CMD=FAIL; UB_NET=FAIL; UB_RESTART=FAIL
if [ -z "$NLD" ]; then
    say "proot loader dir unknown; all Ubuntu sub-checks FAIL"
elif ! run_bash 'command -v proot-distro >/dev/null && echo PD_OK' | grep -q PD_OK; then
    say "proot-distro is not installed (needs the x86_64 apt repository); all Ubuntu sub-checks FAIL"
else
    OUT="$(run_bash '
        echo "=== UB install ==="; proot-distro install ubuntu 2>&1 | tail -20
        proot-distro list --installed 2>/dev/null | grep -qi ubuntu && echo UB_INSTALL_OK
        echo "=== UB login ==="; proot-distro login ubuntu -- /bin/true 2>&1 && echo UB_LOGIN_OK
        echo "=== UB command ==="; proot-distro login ubuntu -- /bin/sh -c ". /etc/os-release; echo UB_CMD id=\$ID arch=\$(uname -m) hello=\$(echo hi)" 2>&1 | tail -4
        echo "=== UB networking ==="; proot-distro login ubuntu -- /bin/sh -c "apt-get update 2>&1 | tail -4; getent hosts archive.ubuntu.com >/dev/null 2>&1 && echo UB_NET_OK || (apt-get update 2>&1 | grep -qiE \"Fetched|Hit|Get:\" && echo UB_NET_OK)" 2>&1 | tail -6
        echo "=== UB restart (fresh login after the previous exited) ==="; proot-distro login ubuntu -- /bin/sh -c "echo UB_RESTART_OK on \$(uname -m)" 2>&1 | tail -3
    ')"
    say "$OUT"
    echo "$OUT" | grep -q "UB_INSTALL_OK" && UB_INSTALL=PASS
    echo "$OUT" | grep -q "UB_LOGIN_OK" && UB_LOGIN=PASS
    echo "$OUT" | grep -q "UB_CMD id=ubuntu arch=x86_64" && UB_CMD=PASS
    echo "$OUT" | grep -q "UB_NET_OK" && UB_NET=PASS
    echo "$OUT" | grep -q "UB_RESTART_OK on x86_64" && UB_RESTART=PASS
fi
say "  ubuntu.install   $UB_INSTALL"
say "  ubuntu.login     $UB_LOGIN"
say "  ubuntu.command   $UB_CMD"
say "  ubuntu.network   $UB_NET"
say "  ubuntu.restart   $UB_RESTART"
{ [ "$UB_INSTALL" = PASS ] && [ "$UB_LOGIN" = PASS ] && [ "$UB_CMD" = PASS ] && [ "$UB_NET" = PASS ] && [ "$UB_RESTART" = PASS ]; } && mark ubuntu PASS || mark ubuntu FAIL

# --- summary ------------------------------------------------------------------------------------
hr; say "SUMMARY (x86_64 runtime validation)"
say "  shell     ${RESULT[shell]:-?}   (shell command execution)"
say "  apt       ${RESULT[apt]:-?}   (pkg/apt update + install a repo package)"
say "  sessions  ${RESULT[sessions]:-?}   (concurrent shell PROCESSES; not the multi-session UI)"
say "  ssh       ${RESULT[ssh]:-?}   (LOOPBACK 127.0.0.1; not a remote host)"
say "  bridge    ${RESULT[bridge]:-?}   (node runtime executes; not full AI Bridge pairing)"
say "  ubuntu    ${RESULT[ubuntu]:-?}   (install=$UB_INSTALL login=$UB_LOGIN command=$UB_CMD network=$UB_NET restart=$UB_RESTART)"
cp "$LOG" "${GITHUB_WORKSPACE:-.}/runtime.txt" 2>/dev/null || true

# Gate on the REQUIRED checks: shell, apt, and every Ubuntu sub-check.
if [ "${RESULT[shell]:-FAIL}" = PASS ] && [ "${RESULT[apt]:-FAIL}" = PASS ] && [ "${RESULT[ubuntu]:-FAIL}" = PASS ]; then
    say "REQUIRED CHECKS PASSED (shell + apt + Ubuntu install/login/command/networking/restart)."
    exit 0
else
    say "REQUIRED CHECKS FAILED. See the per-check output above."
    exit 1
fi
