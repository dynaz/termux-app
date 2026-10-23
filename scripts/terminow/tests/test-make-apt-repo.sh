#!/usr/bin/env bash
# Regression test for make-apt-repo.sh: create a repository from fake packages that have the
# dependency defect that was published, dpkg-perl depending on clang which is not built, and
# check that the packages that can not be installed are left out, that the proot-distro chain
# is kept and that apt can install proot-distro from the repository, with its recommendations.
#
# Needs dpkg-deb, apt-ftparchive, apt-get, gpg and python3. Signs with a throwaway key.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

export GNUPGHOME="$work_dir/gnupg"
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --passphrase '' --quick-generate-key "TermiNow test repository" ed25519 sign never
gpg --batch --export > "$work_dir/test-key.gpg"
export TERMINOW_REPO_PUBLIC_KEY_FILE="$work_dir/test-key.gpg"
export TERMINOW_ARCHITECTURES=aarch64 TERMINOW_EXTRA_PACKAGES=openssh TERMINOW_REPO_PACKAGES=proot,proot-distro

# name;version;architecture;depends;recommends;provides
packages=(
	"apt;2.8;aarch64;;;"
	"bash;5.3;aarch64;;;"
	"termux-tools;1.45;all;;;"
	"termux-exec;2.5;aarch64;;;"
	"termux-keyring;3.14;all;;;"
	"openssh;10.0;aarch64;;;"
	"libtalloc;2.4;aarch64;;;"
	"libandroid-shmem;0.7;aarch64;;;"
	"proot;5.1.107.96;aarch64;libandroid-shmem, libtalloc;;"
	"python;3.14.6-1;aarch64;;python-pip;"
	"python-pip;26.2.1;all;python (>= 3.11.1-1);clang, make, pkg-config;"
	"make;4.4;aarch64;;;"
	"proot-distro;5.9.0;all;proot (>= 5.1.107-71), python, python-pip;;"
	"perl;5.42.2;aarch64;;;"
	"dpkg;1.22.6-5;aarch64;;;"
	"dpkg-perl;1.22.6-5;all;perl, clang, make, dpkg (= 1.22.6-5);;"
	"dpkg-scanpackages;1.22.6-5;all;dpkg-perl, dpkg (= 1.22.6-5);;"
	"mawk;1.3;aarch64;;;awk"
	"uses-awk;1;all;awk;;"
	"uses-alternative;1;all;dpkg-perl | perl;;"
	"recommends-left-out;1;all;;dpkg-scanpackages;"
)
mkdir -p "$work_dir/debs"
for spec in "${packages[@]}"; do
	IFS=';' read -r name version arch depends recommends provides <<< "$spec"
	rm -rf "$work_dir/pkg" && mkdir -p "$work_dir/pkg/DEBIAN"
	{
		echo "Package: $name"; echo "Version: $version"; echo "Architecture: $arch"
		echo "Maintainer: TermiNow"; echo "Description: test package"
		[ -z "$depends" ] || echo "Depends: $depends"
		[ -z "$recommends" ] || echo "Recommends: $recommends"
		[ -z "$provides" ] || echo "Provides: $provides"
	} > "$work_dir/pkg/DEBIAN/control"
	dpkg-deb --root-owner-group -b "$work_dir/pkg" "$work_dir/debs/${name}_${version}_${arch}.deb" > /dev/null
done

"$script_dir/make-apt-repo.sh" "$work_dir/debs" "$work_dir/repo"

index="$work_dir/repo/dists/stable/main/binary-aarch64/Packages"
kept="$(awk '/^Package:/ { print $2 }' "$index" | sort | tr '\n' ' ')"
expected="apt bash dpkg libandroid-shmem libtalloc make mawk openssh perl proot proot-distro python python-pip recommends-left-out termux-exec termux-keyring termux-tools uses-alternative uses-awk "
if [ "$kept" != "$expected" ]; then
	echo "[!] Wrong packages in the index: $kept" 1>&2
	echo "[!] Expected: $expected" 1>&2
	exit 1
fi
for deb in dpkg-perl dpkg-scanpackages; do
	if ls "$work_dir/repo/pool/main/${deb}_"* > /dev/null 2>&1; then
		echo "[!] $deb was not removed from the pool" 1>&2
		exit 1
	fi
done
grep -q '^Architectures: aarch64 all$' "$work_dir/repo/dists/stable/Release"
grep -q '^No-Support-for-Architecture-all: Packages$' "$work_dir/repo/dists/stable/Release"
! grep -q ' Release$' "$work_dir/repo/dists/stable/Release"
gpgv --keyring "$work_dir/test-key.gpg" "$work_dir/repo/dists/stable/InRelease" 2> /dev/null

"$script_dir/test-repo-install.sh" "$work_dir/repo" aarch64 proot-distro recommends-left-out uses-awk uses-alternative > "$work_dir/install.log"
for name in proot python python-pip proot-distro make; do
	grep -q "^Inst $name " "$work_dir/install.log" || { cat "$work_dir/install.log"; echo "[!] $name would not be installed" 1>&2; exit 1; }
done

# A repository without a required package fails
mv "$work_dir/debs/proot_"* "$work_dir/"
if "$script_dir/make-apt-repo.sh" "$work_dir/debs" "$work_dir/repo2" > /dev/null 2>&1; then
	echo "[!] A repository without proot was created" 1>&2
	exit 1
fi

echo "[*] make-apt-repo.sh tests passed"
