#!/usr/bin/env bash
# Run inside TermiNow Local Terminal after the new signed repository is deployed.
set -euo pipefail

prefix="${PREFIX:-/data/data/com.terminow/files/usr}"
[ "$prefix" = /data/data/com.terminow/files/usr ] || {
    echo "Run this in TermiNow Local Terminal (not Ubuntu or SSH)." >&2
    exit 1
}
old=https://dynaz.github.io/termux-app/apt
new=https://dynaz.github.io/terminow-package/apt
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/lists/partial"
printf 'deb %s/terminow-main stable main\n' "$new" > "$work/sources.list"

# Use apt's existing trusted keyring; never disable signature verification.
# Isolate the probe so a missing/untrusted endpoint cannot alter current sources or lists.
apt -o "Dir::Etc::sourcelist=$work/sources.list" \
    -o "Dir::Etc::sourceparts=-" \
    -o "Dir::State::lists=$work/lists" \
    -o "APT::Update::Error-Mode=any" update

backup="$prefix/var/backups/terminow-apt-$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$backup"
changed=0
while IFS= read -r -d '' file; do
    grep -qF "$old" "$file" || continue
    relative="${file#"$prefix"/}"
    mkdir -p "$backup/$(dirname "$relative")"
    cp -p "$file" "$backup/$relative"
    sed "s#https://dynaz\\.github\\.io/termux-app/apt#$new#g" "$file" > "$work/replacement"
    cat "$work/replacement" > "$file"
    changed=$((changed + 1))
done < <(find "$prefix/etc/apt" "$prefix/etc/termux/mirrors" -type f \
    \( -name '*.list' -o -name '*.sources' -o -path "$prefix/etc/termux/mirrors/*" \) -print0)
echo "Updated $changed files. Backups: $backup"
echo "Next: pkg update"
