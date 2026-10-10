#!/usr/bin/env bash
# Configure a termux-packages checkout so that everything it builds is for the TermiNow app:
# the package name that is compiled into paths, the package repository that apt and pkg use,
# and the key that the repository is signed with.
#
# Usage: configure-termux-packages.sh <termux-packages directory>
set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/config.sh"

packages_dir="${1:?Usage: $0 <termux-packages directory>}"
cd "$packages_dir"

fail() { echo "[!] $*" 1>&2; exit 1; }

# Replace a line that must exist exactly once, so that upstream changes to it are not missed.
replace_line() {
	local file="$1" regex="$2" replacement="$3" count
	count="$(grep -cE "$regex" "$file" || true)"
	[ "$count" = "1" ] || fail "Expected exactly one line matching '$regex' in '$file' but found $count"
	sed -i -E "s|$regex|$replacement|" "$file"
}

[ -f scripts/properties.sh ] || fail "'$packages_dir' is not a termux-packages checkout"
[ -f "$TERMINOW_REPO_PUBLIC_KEY_FILE" ] || fail "The repository public key '$TERMINOW_REPO_PUBLIC_KEY_FILE' does not exist, run generate-repo-key.sh"

echo "[*] Setting the app package name to '$TERMINOW_PACKAGE_NAME'..."
replace_line scripts/properties.sh '^TERMUX_APP__PACKAGE_NAME="com\.termux"$' "TERMUX_APP__PACKAGE_NAME=\"$TERMINOW_PACKAGE_NAME\""
replace_line scripts/properties.sh '^TERMUX_APP__NAME="\$TERMUX__NAME"$' "TERMUX_APP__NAME=\"$TERMINOW_APP_NAME\""

# The values that the packages of the repositories in repo.json are compiled for.
replace_line scripts/properties.sh '^TERMUX_REPO_APP__PACKAGE_NAME="com\.termux"$' "TERMUX_REPO_APP__PACKAGE_NAME=\"$TERMINOW_PACKAGE_NAME\""
for variable in TERMUX_REPO_APP__DATA_DIR TERMUX_REPO__CORE_DIR TERMUX_REPO__APPS_DIR TERMUX_REPO__ROOTFS TERMUX_REPO__HOME TERMUX_REPO__PREFIX; do
	replace_line scripts/properties.sh "^$variable=\"/data/data/com\\.termux(.*)\"\$" "$variable=\"/data/data/$TERMINOW_PACKAGE_NAME\\1\""
done

echo "[*] Setting the package repository to '$TERMINOW_REPO_URL'..."
# Only the main repository is built, the others are pointed away from the official ones so
# that packages compiled for com.termux can never be downloaded as build dependencies.
jq --arg main "$TERMINOW_REPO_URL" --arg base "$TERMINOW_REPO_BASE_URL" '
	.packages.name = "terminow-main" | .packages.url = $main |
	."root-packages".name = "terminow-root" | ."root-packages".url = ($base + "/terminow-root") |
	."x11-packages".name = "terminow-x11" | ."x11-packages".url = ($base + "/terminow-x11")' repo.json > repo.json.new
mv repo.json.new repo.json

# The sources.list of the apt package.
grep -q 'packages-cf\.termux\.dev/apt/termux-main/' packages/apt/build.sh || fail "The repository url was not found in packages/apt/build.sh"
sed -i -E "s#https://packages(-cf)?\\.termux\\.dev/apt/termux-main/#$TERMINOW_REPO_URL/#g" packages/apt/build.sh
! grep -q 'termux\.dev/apt' packages/apt/build.sh || fail "An official repository url is left in packages/apt/build.sh"

# pkg replaces the sources.list url with one of the mirrors of termux-tools, so make every
# mirror the TermiNow repository.
! grep -q '^termux_step_post_get_source' packages/termux-tools/build.sh || fail "packages/termux-tools/build.sh already defines termux_step_post_get_source"
cat >> packages/termux-tools/build.sh <<MIRRORS

termux_step_post_get_source() {
	local mirror
	for mirror in mirrors/default mirrors/*/*; do
		[ -f "\$mirror" ] || continue
		{
			echo "# This file is sourced by pkg"
			echo "# $TERMINOW_APP_NAME | $TERMINOW_REPO_BASE_URL"
			echo "WEIGHT=1"
			echo "MAIN=\"$TERMINOW_REPO_URL\""
			echo "ROOT=\"$TERMINOW_REPO_BASE_URL/terminow-root\""
			echo "X11=\"$TERMINOW_REPO_BASE_URL/terminow-x11\""
		} > "\$mirror"
	done
}
MIRRORS

# Bootstrap packages that are subpackages, like bzip2 of libbz2, have no package directory and
# are built with their parent package. build-bootstraps.sh ignores the error for them, but only
# checks for an error message that build-package.sh no longer prints for them.
replace_line scripts/build-bootstraps.sh '\[\[ "\$build_output" == \*"No build\.sh script at package dir"\* \]\]; then$' \
	'{ [[ "$build_output" == *"No build.sh script at package dir"* ]] \|\| [[ "$build_output" == *"found in any of the enabled repositories"* ]]; }; then'

# command-not-found builds its database from the Contents index of the package repository,
# which does not exist before the first build and is not created by make-apt-repo.sh. bash only
# uses command-not-found if it is installed.
replace_line scripts/build-bootstraps.sh '^([[:space:]]*)PACKAGES\+=\("command-not-found"\)$' '\1: # command-not-found is not built'

echo "[*] Adding the repository key to termux-keyring..."
cp "$TERMINOW_REPO_PUBLIC_KEY_FILE" packages/termux-keyring/terminow.gpg
replace_line packages/termux-keyring/build.sh '^([[:space:]]*)install -Dm600 "\$TERMUX_PKG_BUILDER_DIR/termux-autobuilds\.gpg" "\$GPG_SHARE_DIR"$' \
	'\1install -Dm600 "$TERMUX_PKG_BUILDER_DIR/termux-autobuilds.gpg" "$GPG_SHARE_DIR"\n\1install -Dm600 "$TERMUX_PKG_BUILDER_DIR/terminow.gpg" "$GPG_SHARE_DIR"'
grep -q 'terminow\.gpg' packages/termux-keyring/build.sh || fail "The repository key was not added to packages/termux-keyring/build.sh"

echo "[*] Configured '$packages_dir' for '$TERMINOW_PACKAGE_NAME'"
