#!/usr/bin/env bash
# Build the TermiNow bootstrap archive and all the packages for its package repository.
#
# This needs docker on an x86_64 Linux host with about 60GB of free disk space and takes a few
# hours, since every package is compiled from source for the TermiNow package name. It can not
# be run on an Android device.
#
# Usage: build-bootstrap.sh <work directory>
#
# Results:
#   <work directory>/termux-packages/output/bootstrap-<arch>.zip   The bootstrap archives for the app.
#   <work directory>/termux-packages/output/*.deb                  The packages for make-apt-repo.sh.
#   <work directory>/termux-packages/output/proot-loader-<arch>    The proot loader for the app.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$script_dir/config.sh"

work_dir="${1:?Usage: $0 <work directory>}"
mkdir -p "$work_dir"
cd "$work_dir"

if [ ! -d termux-packages/.git ]; then
	git init -q termux-packages
	git -C termux-packages remote add origin "$TERMINOW_PACKAGES_REPO"
fi
git -C termux-packages fetch -q --depth 1 origin "$TERMINOW_PACKAGES_REF"
# Discard the changes of a previous configure, they are applied again below.
git -C termux-packages checkout -q -f FETCH_HEAD
rm -f termux-packages/packages/termux-keyring/terminow.gpg

"$script_dir/configure-termux-packages.sh" termux-packages

cd termux-packages
# libbz2 is the parent package of the bzip2 bootstrap package, which can not be built by its own name.
./scripts/run-docker.sh ./scripts/build-bootstraps.sh \
	--architectures "$TERMINOW_ARCHITECTURES" --add "libbz2,$TERMINOW_EXTRA_PACKAGES"

# The packages that are only in the package repository. Their dependencies that were built for
# the bootstrap are not built again.
for arch in ${TERMINOW_ARCHITECTURES//,/ }; do
	for package in ${TERMINOW_REPO_PACKAGES//,/ }; do
		./scripts/run-docker.sh ./build-package.sh -a "$arch" "$package"
	done
done

# The proot loader is included in the app as a native library, see configure-termux-packages.sh.
if [[ ",$TERMINOW_REPO_PACKAGES," == *",proot,"* ]]; then
	for arch in ${TERMINOW_ARCHITECTURES//,/ }; do
		proot_debs=(output/proot_*_"$arch".deb)
		[ -f "${proot_debs[0]}" ] && [ "${#proot_debs[@]}" = "1" ] || { echo "[!] Expected one proot package for $arch but found: ${proot_debs[*]}" 1>&2; exit 1; }
		loader="output/proot-loader-$arch"
		ar p "${proot_debs[0]}" data.tar.xz | tar -xJ --wildcards -O '*/libexec/proot/loader.elf' > "$loader"
		[ "$(head -c 4 "$loader" | od -An -c | tr -d ' ')" = '177ELF' ] || { echo "[!] '$loader' taken from '${proot_debs[0]}' is not an ELF file" 1>&2; exit 1; }
	done
	ls -l output/proot-loader-*
fi

ls -l output/bootstrap-*.zip

for archive in output/bootstrap-*.zip; do
	python3 -I "$script_dir/check-bootstrap.py" "$archive" "$TERMINOW_PACKAGE_NAME"
done
