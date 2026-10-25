#!/usr/bin/env bash
# Settings shared by the TermiNow bootstrap and package repository scripts.
# The app side of these values is in TermuxConstants.java and app/build.gradle.

# Must equal the applicationId in app/build.gradle and TermuxConstants.TERMUX_PACKAGE_NAME.
: "${TERMINOW_PACKAGE_NAME:=com.terminow}"
: "${TERMINOW_APP_NAME:=TermiNow}"

# The termux-packages commit that the bootstrap and repository are built from.
: "${TERMINOW_PACKAGES_REPO:=https://github.com/termux/termux-packages.git}"
: "${TERMINOW_PACKAGES_REF:=e446fad09f58f4355c8f45243ea831cf3545af47}"

# Where the package repository built by make-apt-repo.sh is served from. `pkg` and `apt` on the
# device only ever use this url, the packages of the official Termux repositories are compiled
# for com.termux and do not work under another package name.
: "${TERMINOW_REPO_BASE_URL:=https://dynaz.github.io/terminow-package/apt}"
: "${TERMINOW_REPO_URL:=$TERMINOW_REPO_BASE_URL/terminow-main}"

# Packages built and put in the bootstrap in addition to the default bootstrap packages. All of
# them and their dependencies also end up in the package repository.
: "${TERMINOW_EXTRA_PACKAGES:=openssh,git,tmux}"

# Packages built for the package repository only, to be installed with `pkg install`. proot-distro
# runs Linux distributions like Ubuntu; it depends on python, which is too large for the bootstrap.
: "${TERMINOW_REPO_PACKAGES:=proot,proot-distro,nodejs,npm}"

: "${TERMINOW_ARCHITECTURES:=aarch64}"

# The public key of the key that signs the package repository, see generate-repo-key.sh.
# Can be set to another key for tests.
: "${TERMINOW_REPO_PUBLIC_KEY_FILE:=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/terminow-repo.gpg}"
