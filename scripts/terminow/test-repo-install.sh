#!/usr/bin/env bash
# Check with apt itself that packages can be installed from a TermiNow package repository, like
# `pkg install` does on a device: the signature of the repository is verified with the
# repository key, recommended packages are installed too, and nothing is installed yet. The
# installation is only simulated, nothing is changed on this machine.
#
# Usage: test-repo-install.sh <repository directory or url> <architecture> <package>...
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$script_dir/config.sh"

repo="${1:?Usage: $0 <repository directory or url> <architecture> <package>...}"
arch="${2:?Usage: $0 <repository directory or url> <architecture> <package>...}"
shift 2
[ $# -gt 0 ] || { echo "[!] No packages to install were given" 1>&2; exit 1; }
[ -d "$repo" ] && repo="file:$(cd "$repo" && pwd)"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
mkdir -p "$work_dir"/{lists/partial,archives/partial,etc/preferences.d,etc/apt.conf.d,etc/trusted.gpg.d,log}
touch "$work_dir/status"
cp "$TERMINOW_REPO_PUBLIC_KEY_FILE" "$work_dir/terminow.gpg"
echo "deb [signed-by=$work_dir/terminow.gpg] $repo stable main" > "$work_dir/etc/sources.list"

apt_options=(
	-o "Dir::State=$work_dir" -o "Dir::State::status=$work_dir/status" -o "Dir::State::Lists=$work_dir/lists"
	-o "Dir::Cache=$work_dir" -o "Dir::Cache::Archives=$work_dir/archives"
	-o "Dir::Etc=$work_dir/etc" -o "Dir::Etc::SourceList=$work_dir/etc/sources.list" -o "Dir::Etc::SourceParts=-"
	-o "Dir::Etc::Parts=$work_dir/etc/apt.conf.d" -o "Dir::Etc::PreferencesParts=$work_dir/etc/preferences.d"
	-o "Dir::Etc::TrustedParts=$work_dir/etc/trusted.gpg.d" -o "Dir::Log=$work_dir/log"
	-o "APT::Architecture=$arch" -o "APT::Architectures::=$arch"
	-o "APT::Install-Recommends=true" -o "Debug::NoLocking=true"
)

echo "[*] Updating the package lists of $repo"
update_output="$(apt-get "${apt_options[@]}" update 2>&1)"
echo "$update_output"
# apt-get update only warns about some problems, like a skipped index
if grep -qE '^(W|E|Err):' <<< "$update_output"; then
	echo "[!] apt-get update reported problems" 1>&2
	exit 1
fi

echo "[*] Simulating the installation of $*"
apt-get "${apt_options[@]}" install -s -y "$@"
