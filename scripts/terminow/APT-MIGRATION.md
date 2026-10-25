# TermiNow APT endpoint migration

Canonical repository: https://dynaz.github.io/terminow-package/apt/terminow-main

The build and distribution of TermiNow packages live in the public repository
`dynaz/terminow-package`. The app source stays private in `dynaz/terminow`. The previous
endpoint `https://dynaz.github.io/termux-app/apt` remains available for older app versions.

## Deployment order

1. `dynaz/terminow-package` is public, so GitHub Actions and Pages are free. Enable Pages to
   serve the `gh-pages` branch at the repository root.
2. Configure `TERMINOW_REPO_GPG_PRIVATE_KEY` in this repository using the existing signing
   key. Do not generate a replacement key; the public key `terminow-repo.gpg` is already
   trusted by installed devices.
3. Run the TermiNow bootstrap workflow on `main`. It builds the packages for `com.terminow`
   (including nodejs and npm), publishes `gh-pages`, re-checks the public signed repository,
   and signature-installs the packages.
4. Confirm the published repository check passes. Copy the release's
   `terminow-bootstrap.properties` asset into `app/terminow-bootstrap.properties` in the app
   repository, then build the next APK from that bootstrap.
5. For existing installations, run `migrate-apt-repo.sh` from TermiNow Local Terminal. It
   first runs an isolated `apt update` against the new URL using the installed trusted
   keyring. Failure leaves existing sources unchanged. Only references to the exact legacy
   base URL in APT sources and mirror files are replaced, and the originals are backed up.
6. Keep `dynaz/termux-app` Pages and its signed package repository available for old app
   versions. This change does not rename or remove that repository.

## Existing device

Transfer `scripts/terminow/migrate-apt-repo.sh` to the device and run it in **TermiNow Local
Terminal** (not Ubuntu or an SSH session):

```bash
bash migrate-apt-repo.sh
pkg update
pkg install nodejs npm
node --version
npm --version
```

The migration prints a backup directory under `$PREFIX/var/backups`. To roll back, copy its
`etc/apt` and `etc/termux/mirrors` files over the matching paths under `$PREFIX`, then run
`pkg update`. If the new endpoint is not ready, keep the old URL.

The migration updates the current files only. An older `termux-tools` or `apt` package can
restore legacy defaults during an upgrade; migrate after updating the old installation, and
ship the new bootstrap for new installations.

## Scope

Internal compatibility names (`etc/termux`, `termux-tools`, `termux-change-repo`), package
IDs, upstream attribution and licenses remain as defined by their respective code. No official
`com.termux` package repository is added: its binaries target another application data path.
Signature verification stays enabled throughout (`signed-by`, never `trusted=yes`). Full
cross-compilation, Pages publication and on-device Node.js execution must pass before treating
the new endpoint as production ready.
