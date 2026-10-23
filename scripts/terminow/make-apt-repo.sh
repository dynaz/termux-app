#!/usr/bin/env bash
# Create the signed apt repository that is served at TERMINOW_REPO_URL from the packages built
# by build-bootstrap.sh.
#
# Needs apt-ftparchive (apt-utils) and gpg with the secret key for the repository public key
# imported, see generate-repo-key.sh.
#
# Usage: make-apt-repo.sh <directory with .deb files> <repository output directory>
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$script_dir/config.sh"

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

# The packages that must be installable from the repository, the publication fails otherwise
required_packages=(apt bash termux-tools termux-exec termux-keyring ${TERMINOW_EXTRA_PACKAGES//,/ } ${TERMINOW_REPO_PACKAGES//,/ })

for arch in ${TERMINOW_ARCHITECTURES//,/ }; do
	binary_dir="dists/$distribution/$component/binary-$arch"
	mkdir -p "$binary_dir"
	# The packages of the architecture and of the all architecture, like apt expects them
	# without a separate binary-all index, see No-Support-for-Architecture-all below.
	apt-ftparchive --arch "$arch" packages "pool/$component" > "$binary_dir/Packages.unchecked"
	# Leave out the packages that apt can never install, since a mandatory dependency of them is
	# not built, like dpkg-perl that depends on clang, and the ones that depend on them. apt may
	# fail a whole installation if it tries to install one of them, like a recommended package.
	python3 -I "$script_dir/check-repo-deps.py" "$binary_dir/Packages.unchecked" "$binary_dir/Packages" \
		--require "${required_packages[@]}"
	rm -f "$binary_dir/Packages.unchecked"
	gzip -9 -k -f "$binary_dir/Packages"
	xz -9 -k -f "$binary_dir/Packages"
done

# Remove the packages that were left out of every index from the pool
find "pool/$component" -name '*.deb' | while read -r deb; do
	if ! grep -q -x -F "Filename: $deb" dists/"$distribution"/"$component"/binary-*/Packages; then
		echo "[*] Removing $deb from the pool, it is in no index"
		rm -f -- "$deb"
	fi
done

# Written to another directory first, so that the Release file does not list itself
release_file="$(mktemp)"
apt-ftparchive \
	-o "APT::FTPArchive::Release::Origin=$TERMINOW_APP_NAME" \
	-o "APT::FTPArchive::Release::Label=$TERMINOW_APP_NAME" \
	-o "APT::FTPArchive::Release::Suite=$distribution" \
	-o "APT::FTPArchive::Release::Codename=$distribution" \
	-o "APT::FTPArchive::Release::Architectures=${TERMINOW_ARCHITECTURES//,/ } all" \
	-o "APT::FTPArchive::Release::Components=$component" \
	release "dists/$distribution" > "$release_file"
# The packages of the all architecture are in the index of each architecture instead of in a
# binary-all index, which apt-ftparchive has no option for
sed -i '/^Architectures:/a No-Support-for-Architecture-all: Packages' "$release_file"
grep -q '^No-Support-for-Architecture-all: Packages$' "$release_file"
mv "$release_file" "dists/$distribution/Release"
chmod 644 "dists/$distribution/Release"

gpg --batch --yes --local-user "$key_fingerprint" --clearsign -o "dists/$distribution/InRelease" "dists/$distribution/Release"
gpg --batch --yes --local-user "$key_fingerprint" --armor --detach-sign -o "dists/$distribution/Release.gpg" "dists/$distribution/Release"

echo "[*] Created the repository for $(ls "pool/$component" | wc -l) packages in '$repo_dir'"
