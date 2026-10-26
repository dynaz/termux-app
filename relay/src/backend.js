// The relay's client for the central Premium authority (cp.accmove.com relay_check.php). The relay
// never decides Premium itself and never trusts a uid or premium flag from the app: it forwards the
// user's Firebase ID token here, and this authority verifies the token server-side and checks WHMCS
// and Google Play. Protected by a shared secret sent only server-to-server. The token and secret are
// never logged. fetchImpl is injectable so the flow can be tested without reaching the backend.

export function backendConfigured(config) {
  return !!(config.backend && config.backend.url && config.backend.secret);
}

// Verify a Firebase ID token and get its Premium status. Returns { uid, email, premium, exp } on a
// clean answer, or { error } (fail closed: any uncertainty is treated as not authorized by callers).
export async function checkPremium(config, firebaseIdToken, fetchImpl = globalThis.fetch) {
  if (!backendConfigured(config)) return { error: 'backend_not_configured' };
  if (!firebaseIdToken) return { error: 'no_token' };
  let res;
  try {
    res = await fetchImpl(config.backend.url, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + firebaseIdToken,
        'X-Relay-Secret': config.backend.secret,
        'Content-Type': 'application/json',
      },
      body: '{}',
    });
  } catch {
    return { error: 'backend_unreachable' };
  }
  if (res.status === 401) return { error: 'unauthorized' };
  if (!res.ok) return { error: 'backend_error' };
  const data = await res.json().catch(() => null);
  if (!data || typeof data.uid !== 'string' || !data.uid) return { error: 'bad_response' };
  return {
    uid: data.uid,
    email: typeof data.email === 'string' ? data.email : '',
    premium: data.premium === true,
    exp: Number.isInteger(data.exp) ? data.exp : 0,
  };
}
