// Minimal "Sign in with Google" (OpenID Connect) helpers for the relay consent screen. The relay
// is a confidential web client: it exchanges the authorization code with its client secret directly
// with Google over TLS, so the id_token Google returns on that back-channel is trusted and its email
// claim read without a separate JWKS verification. No Google token is stored or logged.

const AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';

export function googleConfigured(config) {
  return !!(config.google && config.google.clientId && config.google.clientSecret);
}

export function googleAuthUrl(config, state) {
  const p = new URLSearchParams({
    client_id: config.google.clientId,
    redirect_uri: config.google.redirectUri,
    response_type: 'code',
    scope: 'openid email',
    state,
    prompt: 'select_account',
    access_type: 'online',
  });
  return `${AUTH_URL}?${p.toString()}`;
}

function decodeJwtPayload(jwt) {
  const parts = String(jwt || '').split('.');
  if (parts.length < 2) return null;
  try {
    const json = Buffer.from(parts[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8');
    return JSON.parse(json);
  } catch {
    return null;
  }
}

// Exchange a Google authorization code for the signed-in email. Returns { email } or { error }.
// fetchImpl is injectable so the flow can be tested without reaching Google.
export async function fetchGoogleEmail(config, code, fetchImpl = globalThis.fetch) {
  const body = new URLSearchParams({
    code,
    client_id: config.google.clientId,
    client_secret: config.google.clientSecret,
    redirect_uri: config.google.redirectUri,
    grant_type: 'authorization_code',
  });
  let res;
  try {
    res = await fetchImpl(TOKEN_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    });
  } catch {
    return { error: 'google_unreachable' };
  }
  if (!res.ok) return { error: 'google_token_error' };
  const data = await res.json().catch(() => null);
  const claims = data && decodeJwtPayload(data.id_token);
  if (!claims || !claims.email) return { error: 'no_email' };
  if (claims.email_verified === false || claims.email_verified === 'false') return { error: 'email_unverified' };
  return { email: String(claims.email).toLowerCase() };
}
