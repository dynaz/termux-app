<?php
/**
 * REFERENCE ONLY — DRAFT, NOT FOR DEPLOY.
 *
 * Shows the shared checks every DevOps endpoint must do. The function bodies marked [VERIFY] must be
 * replaced with the REAL helpers from terminow/ai.php + terminow/premium.php on cp.accmove.com. Do not
 * deploy this over the existing backend; it exists so the contract can be reviewed.
 */

// [VERIFY] Reuse ai.php's existing Firebase ID-token verification. Returns the uid, or sends 401 and exits.
function devops_require_firebase_uid(): string {
    $auth = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if (stripos($auth, 'Bearer ') !== 0) devops_fail(401, 'unauthenticated');
    $idToken = substr($auth, 7);
    // $uid = terminow_verify_firebase_id_token($idToken);   // [VERIFY] real helper name from ai.php
    $uid = null; // placeholder
    if (!$uid) devops_fail(401, 'unauthenticated');
    return $uid;
}

// [VERIFY] Reuse premium.php's entitlement check as the single source of truth. No duplicate system.
function devops_require_premium(string $uid): void {
    // if (!terminow_is_premium($uid)) devops_fail(403, 'premium_required');   // [VERIFY] real function/table
}

// Whether Premium is active now (for gating sync vs allowing disconnect/revoke on expiry).
function devops_is_premium(string $uid): bool {
    // return terminow_is_premium($uid);   // [VERIFY]
    return false;
}

// Single-use, short-lived OAuth state bound to a uid, stored server-side.
// [VERIFY] Use the existing store (Firestore or WHMCS MySQL) — same one premium.php uses.
function devops_state_create(string $uid): string {
    $state = bin2hex(random_bytes(32));
    // store { state => { uid, expires_at = now + 600s, used = false } }   // [VERIFY] storage
    return $state;
}
/** Consume a state exactly once; return its uid, or null if unknown/expired/used. */
function devops_state_consume(string $state): ?string {
    // $row = store_get_state($state);                                      // [VERIFY]
    // if (!$row || $row['used'] || $row['expires_at'] < time()) return null;
    // store_mark_state_used($state);   // single use: mark/delete immediately
    // return $row['uid'];
    return null;
}

// Encrypt/decrypt provider tokens at rest. Key is loaded from the host secret store, NOT the DB.
// [VERIFY] reuse the host's existing secret mechanism (same place ai.php loads its keys).
function devops_encrypt(string $plaintext): string {
    $key = devops_load_key();                  // [VERIFY] from secret store, never the DB/repo
    $iv = random_bytes(12);
    $tag = '';
    $ct = openssl_encrypt($plaintext, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag);
    return base64_encode($iv . $tag . $ct);
}
function devops_decrypt(string $stored): string {
    $key = devops_load_key();
    $raw = base64_decode($stored);
    $iv = substr($raw, 0, 12); $tag = substr($raw, 12, 16); $ct = substr($raw, 28);
    return openssl_decrypt($ct, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag);
}
function devops_load_key(): string {
    // return getenv('TERMINOW_DEVOPS_KEY');   // [VERIFY] host secret mechanism; separate from DB
    return str_repeat("\0", 32);
}

function devops_fail(int $code, string $error, $detail = null): void {
    http_response_code($code);
    header('Content-Type: application/json');
    // NEVER include tokens/codes/secrets in $detail.
    echo json_encode($detail === null ? ['error' => $error] : ['error' => $error, 'detail' => $detail]);
    exit;
}
