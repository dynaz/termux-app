<?php
/**
 * REFERENCE ONLY — DRAFT, NOT FOR DEPLOY. GitHub connect + callback.
 * See _common.php for the shared checks. [VERIFY] markers must be bound to the real backend source.
 */
require __DIR__ . '/_common.php';

// GET /devops/connect/github  — Firebase auth + entitlement; returns the authorize URL.
function devops_connect_github(): void {
    $uid = devops_require_firebase_uid();
    devops_require_premium($uid);

    $clientId = devops_load_github_client_id();          // [VERIFY] from secret store, not the repo/DB
    $redirect = 'https://cp.accmove.com/terminow/devops/callback/github';
    $state    = devops_state_create($uid);               // random, ≤10 min, single-use, bound to uid
    // Minimal read-only scope. OAuth App example; a GitHub App uses fine-grained perms instead. No `workflow`.
    $scope    = 'read:user repo';                         // or 'read:user public_repo' if private repos not needed
    $url = 'https://github.com/login/oauth/authorize?' . http_build_query([
        'client_id'    => $clientId,
        'redirect_uri' => $redirect,
        'scope'        => $scope,
        'state'        => $state,
        'allow_signup' => 'false',
    ]);
    header('Content-Type: application/json');
    echo json_encode(['authorizeUrl' => $url]);
}

// GET /devops/callback/github?code=&state=  — NO Firebase header; authenticated by the single-use state.
function devops_callback_github(): void {
    $code  = $_GET['code']  ?? '';
    $state = $_GET['state'] ?? '';
    if ($code === '' || $state === '') devops_fail(400, 'bad_state');

    $uid = devops_state_consume($state);                 // unknown/expired/used -> null
    if ($uid === null) devops_fail(400, 'bad_state');

    // Re-check entitlement before saving — the user may have lapsed between connect and callback.
    devops_require_premium($uid);

    // Exchange code -> token server-side. Client secret stays on the server; never logged.
    $resp = devops_github_exchange_code($code);          // [VERIFY] http client; uses secret from store
    if (!isset($resp['access_token'])) devops_fail(502, 'provider_error');

    // Encrypt and store per-uid; never return the token to the app, never log it.
    devops_store_connection($uid, [                      // [VERIFY] same store/record shape as backend
        'access_token'  => devops_encrypt($resp['access_token']),
        'refresh_token' => isset($resp['refresh_token']) ? devops_encrypt($resp['refresh_token']) : null,
        'token_type'    => $resp['token_type'] ?? 'bearer',
        'scopes'        => $resp['scope'] ?? '',
        'connected_at'  => time(),
        'status'        => 'connected',
    ]);

    // Redirect to a success page (or app deep link [VERIFY scheme]). No token in the URL.
    header('Location: https://cp.accmove.com/terminow/devops/connected.html');
}

// [VERIFY] implement with the host's HTTP client + secret store.
function devops_github_exchange_code(string $code): array { return []; }
function devops_load_github_client_id(): string { return ''; }
function devops_store_connection(string $uid, array $record): void { /* [VERIFY] existing store */ }
