<?php
/**
 * TermiNow Premium check: the logic, without WHMCS, so that it can be tested on its own.
 *
 * The app sends the Firebase ID token of the signed-in user. The token is verified with the
 * public keys of Google, and only a verified email address is trusted. Premium is then whether
 * that email has an active TermiNow service in WHMCS.
 */

namespace TermiNow\Premium;

const FIREBASE_PROJECT_ID = 'linex-bf945';
const CERTS_URL = 'https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com';
const CLOCK_SKEW = 60;

class TokenException extends \Exception {}

function base64url_decode(string $data): string
{
    $decoded = base64_decode(strtr($data, '-_', '+/') . str_repeat('=', (4 - strlen($data) % 4) % 4), true);
    if ($decoded === false) throw new TokenException('Malformed token');
    return $decoded;
}

/**
 * Verify a Firebase ID token and return its claims.
 *
 * @param array $certs The public certificates of Google, by key id.
 * @param int $now The current time.
 */
function verify_id_token(string $token, array $certs, int $now, string $projectId = FIREBASE_PROJECT_ID): array
{
    $parts = explode('.', $token);
    if (count($parts) !== 3) throw new TokenException('Malformed token');
    $header = json_decode(base64url_decode($parts[0]), true);
    $claims = json_decode(base64url_decode($parts[1]), true);
    if (!is_array($header) || !is_array($claims)) throw new TokenException('Malformed token');

    // Only RS256 with one of the current keys of Google, never "none" or HMAC
    if (($header['alg'] ?? '') !== 'RS256') throw new TokenException('Wrong algorithm');
    $kid = $header['kid'] ?? '';
    if (!is_string($kid) || !isset($certs[$kid])) throw new TokenException('Unknown key');
    $key = openssl_pkey_get_public($certs[$kid]);
    if ($key === false) throw new TokenException('Bad certificate');
    if (openssl_verify($parts[0] . '.' . $parts[1], base64url_decode($parts[2]), $key, OPENSSL_ALGO_SHA256) !== 1)
        throw new TokenException('Bad signature');

    if (($claims['aud'] ?? null) !== $projectId) throw new TokenException('Wrong audience');
    if (($claims['iss'] ?? null) !== 'https://securetoken.google.com/' . $projectId) throw new TokenException('Wrong issuer');
    if (!is_int($claims['exp'] ?? null) || $claims['exp'] < $now - CLOCK_SKEW) throw new TokenException('Expired');
    if (!is_int($claims['iat'] ?? null) || $claims['iat'] > $now + CLOCK_SKEW) throw new TokenException('Issued in the future');
    if (isset($claims['auth_time']) && (!is_int($claims['auth_time']) || $claims['auth_time'] > $now + CLOCK_SKEW))
        throw new TokenException('Bad auth time');
    $sub = $claims['sub'] ?? '';
    if (!is_string($sub) || $sub === '' || strlen($sub) > 128) throw new TokenException('Bad subject');
    // An unverified email could be anyone's
    if (($claims['email_verified'] ?? false) !== true) throw new TokenException('Email not verified');
    $email = $claims['email'] ?? '';
    if (!is_string($email) || !filter_var($email, FILTER_VALIDATE_EMAIL)) throw new TokenException('No email');
    return $claims;
}

/**
 * Whether a product of WHMCS is TermiNow Premium: one of the configured product ids, or, when
 * none are configured, a product whose name contains "TermiNow".
 */
function is_premium_product(array $product, array $productIds): bool
{
    if (!empty($productIds)) return in_array((int)($product['pid'] ?? 0), array_map('intval', $productIds), true);
    return stripos((string)($product['name'] ?? ''), 'terminow') !== false ||
        stripos((string)($product['groupname'] ?? ''), 'terminow') !== false;
}

/**
 * Decide Premium from the services of a client, as GetClientsProducts returns them. Only an
 * Active service counts, not a Pending, Suspended, Terminated or Cancelled one.
 */
function decide(array $products, array $productIds): array
{
    $best = null;
    foreach ($products as $product) {
        if (!is_array($product) || !is_premium_product($product, $productIds)) continue;
        if (($product['status'] ?? '') !== 'Active') continue;
        $due = (string)($product['nextduedate'] ?? '');
        if ($best === null || strcmp($due, (string)$best['nextduedate']) > 0) $best = $product;
    }
    if ($best === null) return ['premium' => false];
    $due = (string)($best['nextduedate'] ?? '');
    return [
        'premium' => true,
        'plan' => (string)($best['name'] ?? 'TermiNow Premium'),
        // "0000-00-00" is a one-time or free service without a due date
        'nextDueDate' => ($due === '' || $due === '0000-00-00') ? null : $due,
    ];
}

/** The public certificates of Google, cached for as long as Google says. */
function fetch_certs(string $cacheFile): array
{
    $cached = @json_decode((string)@file_get_contents($cacheFile), true);
    if (is_array($cached) && ($cached['expires'] ?? 0) > time() && is_array($cached['certs'] ?? null)) return $cached['certs'];

    $curl = curl_init(CERTS_URL);
    $maxAge = 3600;
    curl_setopt_array($curl, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 10,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_SSL_VERIFYHOST => 2,
        CURLOPT_HEADERFUNCTION => function ($curl, $line) use (&$maxAge) {
            if (preg_match('/^cache-control:.*max-age=(\d+)/i', $line, $m)) $maxAge = (int)$m[1];
            return strlen($line);
        },
    ]);
    $body = curl_exec($curl);
    $status = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
    curl_close($curl);
    $certs = is_string($body) ? json_decode($body, true) : null;
    if ($status !== 200 || !is_array($certs) || empty($certs)) {
        // A stale copy is better than refusing everyone while Google can not be reached
        if (is_array($cached['certs'] ?? null)) return $cached['certs'];
        throw new \RuntimeException('Failed to get the keys of Google');
    }
    $tmp = $cacheFile . '.' . bin2hex(random_bytes(4));
    if (@file_put_contents($tmp, json_encode(['expires' => time() + min($maxAge, 86400), 'certs' => $certs])) !== false)
        @rename($tmp, $cacheFile);
    return $certs;
}

/** The bearer token of the request, or null. */
function bearer_token(array $server): ?string
{
    $header = $server['HTTP_AUTHORIZATION'] ?? $server['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if (!preg_match('/^Bearer\s+([A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+)$/', trim($header), $m)) return null;
    return strlen($m[1]) <= 8192 ? $m[1] : null;
}
