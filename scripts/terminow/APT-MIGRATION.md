# TermiNow APT URL migration

Canonical repository: https://dynaz.github.io/terminow/apt/terminow-main

## Deployment order

1. Enable GitHub Pages for dynaz/terminow, serving the gh-pages branch at its root.
   This repository is private: confirm the account plan permits Pages and that the
   package endpoint is publicly accessible. Do not change repository visibility
   just to enable hosting.
2. Ensure TERMINOW_REPO_GPG_PRIVATE_KEY is configured in this repository using
   the existing signing key. Do not generate a replacement key for this migration.
3. Merge these changes, then manually run the TermiNow bootstrap workflow on main.
   It builds packages for com.terminow, publishes gh-pages, checks the public signed
   repository, and updates app/terminow-bootstrap.properties.
4. Confirm the published repository check passes and build the next APK from the
   resulting bootstrap update. The repository package list now includes nodejs and npm;
   the existing dependency and apt installation checks include it automatically.
5. For existing installations, run migrate-apt-repo.sh from TermiNow Local Terminal.
   It first runs an isolated apt update against the new URL using the installed
   trusted keyring. Failure leaves existing sources unchanged. Only references to
   the exact legacy base URL in APT sources and mirror files are replaced.
6. Keep dynaz/termux-app Pages and its signed package repository available for old
   app versions. This change does not rename or remove that repository.

## Existing device

Transfer scripts/terminow/migrate-apt-repo.sh to the device and run:

```bash
bash migrate-apt-repo.sh
pkg update
pkg install nodejs npm
npm --version
```

The migration prints a backup directory under $PREFIX/var/backups. To roll back,
copy its etc/apt and etc/termux/mirrors files over the matching paths under
$PREFIX, then run pkg update. If the new endpoint is not ready, keep the old URL.

The migration updates the current files only. An older termux-tools or apt package
can restore legacy defaults during an upgrade; migrate after updating the old
installation, and ship the new bootstrap for new installations.

## Scope

Internal compatibility names (etc/termux, termux-tools, termux-change-repo), package
IDs, upstream attribution and licenses remain as defined by their respective code.
No official com.termux package repository is added: its binaries target another
application data path. Full cross-compilation, Pages publication and on-device
Node.js execution must pass before treating the new endpoint as production ready.
