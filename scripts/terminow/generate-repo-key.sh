#!/usr/bin/env bash
# Generate the key that signs the TermiNow package repository. This is only done once.
#
# The public key is written to terminow-repo.gpg next to this script and must be committed, it
# is installed on devices by the termux-keyring package of the bootstrap. The secret key is
# written to the given file and must be kept private, the bootstrap workflow reads it from the
# TERMINOW_REPO_GPG_PRIVATE_KEY repository secret:
#
#   gh secret set TERMINOW_REPO_GPG_PRIVATE_KEY < <secret key file>
#
# Usage: generate-repo-key.sh <secret key output file>
set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/config.sh"

secret_key_file="${1:?Usage: $0 <secret key output file>}"
[ ! -e "$TERMINOW_REPO_PUBLIC_KEY_FILE" ] || { echo "[!] '$TERMINOW_REPO_PUBLIC_KEY_FILE' already exists, replacing the key breaks pkg on installed devices" 1>&2; exit 1; }
[ ! -e "$secret_key_file" ] || { echo "[!] '$secret_key_file' already exists" 1>&2; exit 1; }

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
trap 'rm -rf "$GNUPGHOME"' EXIT

gpg --batch --quiet --passphrase '' --quick-generate-key "$TERMINOW_APP_NAME package repository" ed25519 sign never

(umask 077 && gpg --batch --armor --export-secret-keys > "$secret_key_file")
gpg --batch --export > "$TERMINOW_REPO_PUBLIC_KEY_FILE"

echo "[*] Public key: $TERMINOW_REPO_PUBLIC_KEY_FILE"
echo "[*] Secret key: $secret_key_file"
gpg --show-keys "$TERMINOW_REPO_PUBLIC_KEY_FILE"
