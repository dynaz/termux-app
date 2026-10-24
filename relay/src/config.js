// Configuration for the TermiNow MCP relay, read from the environment. Nothing here is a secret
// of a device: the relay never learns a device's local address. Device tokens and user pairing
// codes identify who may connect, and map to a user id. In production these live in a secret store
// or a database; for a self-host they come from the environment.

function parseMap(value) {
  // "code1=user1,code2=user2" -> { code1: "user1", code2: "user2" }
  const out = {};
  if (!value) return out;
  for (const pair of value.split(',')) {
    const i = pair.indexOf('=');
    if (i > 0) out[pair.slice(0, i).trim()] = pair.slice(i + 1).trim();
  }
  return out;
}

export function loadConfig(env = process.env) {
  const publicUrl = (env.RELAY_PUBLIC_URL || 'http://localhost:8787').replace(/\/$/, '');
  return {
    // The address the relay binds to. Terminate TLS in front of it (see deploy/) or set certs.
    host: env.RELAY_HOST || '0.0.0.0',
    port: parseInt(env.RELAY_PORT || '8787', 10),
    // The public HTTPS origin AI clients reach, used in OAuth metadata and redirect validation.
    publicUrl,
    // device token -> user id. A device presents its token on the outbound channel.
    deviceTokens: parseMap(env.RELAY_DEVICE_TOKENS),
    // user pairing code -> user id. A human enters this code at the OAuth consent screen.
    pairingCodes: parseMap(env.RELAY_PAIRING_CODES),
    // How long an access token lives, and how long a relayed request waits for the device.
    accessTokenTtlMs: parseInt(env.RELAY_ACCESS_TOKEN_TTL_MS || String(3600 * 1000), 10),
    requestTimeoutMs: parseInt(env.RELAY_REQUEST_TIMEOUT_MS || '60000', 10),
    // Permit dynamic client registration (RFC 7591). Trending to CIMD in newer MCP revisions,
    // kept here for clients (including some ChatGPT/Claude Desktop builds) that rely on it.
    allowDynamicRegistration: env.RELAY_ALLOW_DCR !== 'false',
    // "Sign in with Google" at the consent screen, as an alternative to the pairing code. The relay
    // is a Google OAuth web client; set the id and secret from the Google Cloud console, and add
    // <publicUrl>/oauth/google/callback as an authorized redirect URI. A signed-in Google email is
    // mapped to a user id through RELAY_GOOGLE_USERS ("email=userId,...") or a device registration.
    google: {
      clientId: env.RELAY_GOOGLE_CLIENT_ID || '',
      clientSecret: env.RELAY_GOOGLE_CLIENT_SECRET || '',
      redirectUri: env.RELAY_GOOGLE_REDIRECT_URI || (publicUrl + '/oauth/google/callback'),
      users: parseMap(env.RELAY_GOOGLE_USERS), // Google email -> user id
    },
    // The central Premium authority (cp.accmove.com relay_check.php): it verifies a Firebase ID
    // token server-side and answers { uid, premium } for WHMCS and Google Play. The relay trusts
    // only this, never a uid or premium flag from the app. The secret lives only here and there.
    backend: {
      url: env.RELAY_BACKEND_URL || '', // e.g. https://cp.accmove.com/terminow/relay_check.php
      secret: env.RELAY_BACKEND_SECRET || '',
    },
    // How long an enrolled device credential (and a Google-login session) is trusted before Premium
    // must be re-confirmed by enrolling again with a fresh Firebase ID token.
    enrollTtlMs: parseInt(env.RELAY_ENROLL_TTL_MS || String(65 * 60 * 1000), 10),
    // Public Firebase web config for "Sign in with Google" on the consent page (not secrets). The
    // browser signs in and submits a Firebase ID token, which the backend authority then verifies.
    firebaseWeb: {
      apiKey: env.RELAY_FIREBASE_API_KEY || 'AIzaSyD-6m-iehXMSyiltMTAAd0soxsj4iWLgmo',
      authDomain: env.RELAY_FIREBASE_AUTH_DOMAIN || 'linex-bf945.firebaseapp.com',
      projectId: env.RELAY_FIREBASE_PROJECT_ID || 'linex-bf945',
    },
    // Temporarily hide "Sign in with Google" on the consent page (e.g. while the backend authority
    // is being deployed) without unsetting RELAY_BACKEND_URL. The pairing code stays as a fallback.
    googleSigninDisabled: env.RELAY_GOOGLE_SIGNIN_DISABLED === 'true' || env.RELAY_GOOGLE_SIGNIN_DISABLED === '1',
  };
}
