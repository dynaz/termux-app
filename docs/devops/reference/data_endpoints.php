<?php
/**
 * REFERENCE ONLY — DRAFT, NOT FOR DEPLOY. Data + disconnect/revoke endpoints.
 * Every data endpoint: Firebase token + entitlement + per-resource access. Sync off when Premium expired;
 * disconnect/revoke still allowed. [VERIFY] markers bind to the real backend source.
 */
require __DIR__ . '/_common.php';

// GET /devops/repos  — list the connected user's repositories.
function devops_repos(): void {
    $uid = devops_require_firebase_uid();
    if (!devops_is_premium($uid)) devops_fail(403, 'premium_required');   // expired -> no sync
    $token = devops_connection_token($uid);                               // decrypted server-side only
    if ($token === null) devops_fail(409, 'not_connected');
    // $repos = github_get($token, '/user/repos?per_page=50&sort=pushed'); // [VERIFY] http client
    // echo json_encode(array_map('devops_map_repo', $repos));
}

// GET /devops/runs?repo=owner/name  — Actions runs for a repo the user can access.
function devops_runs(): void {
    $uid = devops_require_firebase_uid();
    if (!devops_is_premium($uid)) devops_fail(403, 'premium_required');
    $token = devops_connection_token($uid);
    if ($token === null) devops_fail(409, 'not_connected');
    $repo = $_GET['repo'] ?? '';
    if (!preg_match('~^[\w.-]+/[\w.-]+$~', $repo)) devops_fail(400, 'bad_request');
    // Verify access via the token (do not trust $repo blindly); 404/403 from GitHub -> no_access.
    // $runs = github_get($token, "/repos/$repo/actions/runs?per_page=30");  // [VERIFY]
    // echo json_encode(...);
}

// GET /devops/logs?repo=owner/name&run=ID  — logs for one run the user can access.
function devops_logs(): void {
    $uid = devops_require_firebase_uid();
    if (!devops_is_premium($uid)) devops_fail(403, 'premium_required');
    $token = devops_connection_token($uid);
    if ($token === null) devops_fail(409, 'not_connected');
    // GitHub returns a redirect to a log archive; the server fetches it with the token and streams it.
    // The token is never exposed to the client. [VERIFY] streaming + size limits.
}

// POST /devops/disconnect  — allowed even when Premium expired.
function devops_disconnect(): void {
    $uid = devops_require_firebase_uid();
    devops_delete_connection($uid);                                       // [VERIFY] store
    echo json_encode(['status' => 'disconnected']);
}

// POST /devops/revoke  — revoke at GitHub then delete. Allowed when expired.
function devops_revoke(): void {
    $uid = devops_require_firebase_uid();
    $token = devops_connection_token($uid);
    if ($token !== null) { /* github revoke token/installation [VERIFY] */ }
    devops_delete_connection($uid);
    echo json_encode(['status' => 'revoked']);
}

function devops_connection_token(string $uid): ?string { return null; }   // [VERIFY] load+decrypt
function devops_delete_connection(string $uid): void { /* [VERIFY] store */ }
