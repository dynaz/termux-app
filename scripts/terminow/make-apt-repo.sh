#!/usr/bin/env bash
# Create the signed apt repository that is served at TERMINOW_REPO_URL from the packages built
# by build-bootstrap.sh.
#
# Needs apt-ftparchive (apt-utils) and gpg with the secret key for the repository public key
# imported, see generate-repo-key.sh.
#
# Usage: make-apt-repo.sh <directory with .deb files> <repository output directory>
set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/config.sh"

debs_dir="${1:?Usage: $0 <directory with .deb files> <repository output directory>}"
repo_dir="${2:?Usage: $0 <directory with .deb files> <repository output directory>}"

distribution="stable"
component="main"

key_fingerprint="$(gpg --show-keys --with-colons "$TERMINOW_REPO_PUBLIC_KEY_FILE" | awk -F: '$1 == "fpr" { print $10; exit }')"
[ -n "$key_fingerprint" ] || { echo "[!] Failed to read the key in '$TERMINOW_REPO_PUBLIC_KEY_FILE'" 1>&2; exit 1; }
gpg --list-secret-keys "$key_fingerprint" > /dev/null 2>&1 || { echo "[!] The secret key for '$key_fingerprint' is not imported in gpg" 1>&2; exit 1; }

rm -rf "$repo_dir"
mkdir -p "$repo_dir/pool/$component"
find "$debs_dir" -maxdepth 1 -name '*.deb' -exec cp -t "$repo_dir/pool/$component" {} +
[ -n "$(ls -A "$repo_dir/pool/$component")" ] || { echo "[!] No .deb files found in '$debs_dir'" 1>&2; exit 1; }

cd "$repo_dir"
for arch in ${TERMINOW_ARCHITECTURES//,/ }; do
	binary_dir="dists/$distribution/$component/binary-$arch"
	mkdir -p "$binary_dir"
	# Packages for all architectures are listed for every architecture.
	apt-ftparchive --arch "$arch" packages "pool/$component" > "$binary_dir/Packages"
	gzip -9 -k -f "$binary_dir/Packages"
	xz -9 -k -f "$binary_dir/Packages"
done

apt-ftparchive \
	-o "APT::FTPArchive::Release::Origin=$TERMINOW_APP_NAME" \
	-o "APT::FTPArchive::Release::Label=$TERMINOW_APP_NAME" \
	-o "APT::FTPArchive::Release::Suite=$distribution" \
	-o "APT::FTPArchive::Release::Codename=$distribution" \
	-o "APT::FTPArchive::Release::Architectures=${TERMINOW_ARCHITECTURES//,/ }" \
	-o "APT::FTPArchive::Release::Components=$component" \
	release "dists/$distribution" > "dists/$distribution/Release"

gpg --batch --yes --local-user "$key_fingerprint" --clearsign -o "dists/$distribution/InRelease" "dists/$distribution/Release"
gpg --batch --yes --local-user "$key_fingerprint" --armor --detach-sign -o "dists/$distribution/Release.gpg" "dists/$distribution/Release"

echo "[*] Created the repository for $(ls "pool/$component" | wc -l) packages in '$repo_dir'"
