<?php
/**
 * Sample DevOps config. Copy to devops_config.php on the server (NOT in the repo) and fill in real
 * values from the host's secret store. devops_lib.php requires this file via CONFIG_FILE and fails
 * closed (503 "DevOps not configured") when it is missing or incomplete.
 *
 * Keep this out of version control and the database. The token encryption key is held here, separate
 * from the DB, so a DB dump alone cannot decrypt stored provider tokens.
 */

return [
    // From the GitHub App / OAuth App you create (see docs/devops/SETUP_CHECKLIST.md).
    'github_client_id'     => 'REPLACE_ME',
    'github_client_secret' => 'REPLACE_ME',

    // Must match the GitHub app's Authorization callback URL exactly. This is the real file path, so no
    // URL rewriting is needed (devops_callback.php is served directly, like devops.php / premium.php).
    'redirect_uri'         => 'https://cp.accmove.com/terminow/devops_callback.php',

    // 32 raw bytes for AES-256-GCM, base64-encoded. Generate once and keep secret, e.g.:
    //   php -r "echo base64_encode(random_bytes(32)), PHP_EOL;"
    'token_key_b64'        => 'REPLACE_WITH_BASE64_OF_32_RANDOM_BYTES',
];
