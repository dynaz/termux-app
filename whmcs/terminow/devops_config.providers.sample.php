<?php
/**
 * Sample for the EXTRA DevOps providers (GitLab.com, Bitbucket Cloud). Copy to
 * devops_config.providers.php on the server (NOT in the repo); devops_lib.php merges it over the main
 * devops_config.php when present. Keeping it separate means adding a provider never touches the working
 * GitHub secret file. The helper script /root/setup_devops_provider_config.sh writes this file for you
 * with the secret entered hidden (never in chat/argv/history) — prefer it over editing by hand.
 *
 * A provider turns on ("Connect" in the app) only when BOTH its *_client_id and *_client_secret are set
 * here; otherwise it stays "Coming soon". You can also hold one back with  '<provider>_enabled' => false.
 */

return [
    // ---- GitLab.com (User Settings → Applications → Add new application) ----
    // Redirect URI: https://cp.accmove.com/terminow/devops_callback.php?provider=gitlab
    // Scopes (read-only): read_api  read_user     Confidential: yes
    // 'gitlab_client_id'      => 'REPLACE_ME',
    // 'gitlab_client_secret'  => 'REPLACE_ME',
    // 'gitlab_redirect_uri'   => 'https://cp.accmove.com/terminow/devops_callback.php?provider=gitlab',
    // 'gitlab_scopes'         => 'read_api read_user',   // optional; this is the default

    // ---- Bitbucket Cloud (Workspace settings → OAuth consumers → Add consumer) ----
    // Callback URL: https://cp.accmove.com/terminow/devops_callback.php?provider=bitbucket
    // Permissions (read-only): Account: Read, Repositories: Read, Pipelines: Read     This is a private
    // consumer (it has a secret). Bitbucket takes permissions from the consumer, not the authorize URL.
    // 'bitbucket_client_id'     => 'REPLACE_ME',
    // 'bitbucket_client_secret' => 'REPLACE_ME',
    // 'bitbucket_redirect_uri'  => 'https://cp.accmove.com/terminow/devops_callback.php?provider=bitbucket',
];
