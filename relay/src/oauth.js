// OAuth 2.1 for the relay, as the MCP authorization spec requires: the relay is the resource
// server, and it is its own authorization server here for a self-host. It publishes Protected
// Resource Metadata (RFC 9728) and Authorization Server Metadata (RFC 8414), runs the
// authorization-code flow with PKCE (S256 only), and supports Dynamic Client Registration
// (RFC 7591) for clients that still use it. Access tokens are opaque and bound to a user id.
//
// Storage is in memory: fine for a single-process self-host, but a multi-instance deployment must
// back these maps with shared storage (see README). No token is ever logged.

import crypto from 'node:crypto';

const b64url = (buf) => buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const randomToken = () => b64url(crypto.randomBytes(32));

function verifyPkce(verifier, challenge) {
  if (!verifier || !challenge) return false;
  const computed = b64url(crypto.createHash('sha256').update(verifier).digest());
  // Constant-time compare on equal-length buffers
  const a = Buffer.from(computed);
  const b = Buffer.from(challenge);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

export class OAuth {
  constructor(config) {
    this.config = config;
    this.clients = new Map();        // client_id -> { redirectUris:Set }
    this.codes = new Map();          // code -> { userId, challenge, redirectUri, clientId, expiresAt }
    this.accessTokens = new Map();   // token -> { userId, expiresAt }
    this.refreshTokens = new Map();  // token -> { userId }
  }

  protectedResourceMetadata() {
    return {
      resource: this.config.publicUrl + '/mcp',
      authorization_servers: [this.config.publicUrl],
      bearer_methods_supported: ['header'],
      scopes_supported: ['mcp'],
    };
  }

  authorizationServerMetadata() {
    const base = this.config.publicUrl;
    const meta = {
      issuer: base,
      authorization_endpoint: base + '/authorize',
      token_endpoint: base + '/token',
      response_types_supported: ['code'],
      grant_types_supported: ['authorization_code', 'refresh_token'],
      code_challenge_methods_supported: ['S256'],
      token_endpoint_auth_methods_supported: ['none'],
      scopes_supported: ['mcp'],
    };
    if (this.config.allowDynamicRegistration) meta.registration_endpoint = base + '/register';
    return meta;
  }

  // RFC 7591 dynamic client registration: accept the client's redirect URIs.
  register(body) {
    const clientId = 'mcp-' + randomToken().slice(0, 24);
    const redirectUris = Array.isArray(body.redirect_uris) ? body.redirect_uris : [];
    this.clients.set(clientId, { redirectUris: new Set(redirectUris) });
    return {
      client_id: clientId,
      redirect_uris: redirectUris,
      token_endpoint_auth_method: 'none',
      grant_types: ['authorization_code', 'refresh_token'],
      response_types: ['code'],
    };
  }

  knownRedirect(clientId, redirectUri) {
    const client = this.clients.get(clientId);
    // A client registered via DCR must use a registered redirect; an unregistered (static) client
    // id is allowed its stated redirect, which the consent screen shows to the user.
    if (client) return client.redirectUris.has(redirectUri);
    return !!redirectUri;
  }

  // The user approves at /authorize by entering a pairing code; a valid code identifies the user.
  issueCode({ pairingCode, clientId, redirectUri, challenge }) {
    const userId = this.config.pairingCodes[pairingCode];
    if (!userId) return null;
    if (!challenge) return null; // PKCE is required
    const code = randomToken();
    this.codes.set(code, { userId, challenge, redirectUri, clientId, expiresAt: Date.now() + 5 * 60 * 1000 });
    return code;
  }

  // authorization_code grant with PKCE.
  exchangeCode({ code, verifier, redirectUri }) {
    const entry = this.codes.get(code);
    if (!entry) return { error: 'invalid_grant' };
    this.codes.delete(code);
    if (entry.expiresAt < Date.now()) return { error: 'invalid_grant' };
    if (entry.redirectUri && redirectUri && entry.redirectUri !== redirectUri) return { error: 'invalid_grant' };
    if (!verifyPkce(verifier, entry.challenge)) return { error: 'invalid_grant' };
    return this.issueTokens(entry.userId);
  }

  refresh(refreshToken) {
    const entry = this.refreshTokens.get(refreshToken);
    if (!entry) return { error: 'invalid_grant' };
    this.refreshTokens.delete(refreshToken);
    return this.issueTokens(entry.userId);
  }

  issueTokens(userId) {
    const accessToken = randomToken();
    const refreshToken = randomToken();
    this.accessTokens.set(accessToken, { userId, expiresAt: Date.now() + this.config.accessTokenTtlMs });
    this.refreshTokens.set(refreshToken, { userId });
    return {
      access_token: accessToken,
      token_type: 'Bearer',
      expires_in: Math.floor(this.config.accessTokenTtlMs / 1000),
      refresh_token: refreshToken,
      scope: 'mcp',
    };
  }

  // The user id a bearer token is for, or null. Expired tokens are rejected and dropped.
  userForBearer(authorizationHeader) {
    if (!authorizationHeader || !authorizationHeader.startsWith('Bearer ')) return null;
    const token = authorizationHeader.slice('Bearer '.length).trim();
    const entry = this.accessTokens.get(token);
    if (!entry) return null;
    if (entry.expiresAt < Date.now()) {
      this.accessTokens.delete(token);
      return null;
    }
    return entry.userId;
  }

  // Revoke every token of a user, for an emergency stop from the device or an admin.
  revokeUser(userId) {
    for (const [token, entry] of this.accessTokens) if (entry.userId === userId) this.accessTokens.delete(token);
    for (const [token, entry] of this.refreshTokens) if (entry.userId === userId) this.refreshTokens.delete(token);
  }
}
