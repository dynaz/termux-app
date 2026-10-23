# Deploy the TermiNow MCP relay with Docker + Nginx Proxy Manager

This deploys the **existing** relay (`relay/`) as a container behind [Nginx Proxy Manager](https://nginxproxymanager.com)
(NPM), which handles HTTPS with Let's Encrypt. Target endpoint: **`https://mcp.terminow.online/mcp`**.

## 1. DNS

Create an **A** (and AAAA if you have IPv6) record pointing the hostname at your server:

```
mcp.terminow.online.   A   <your server's public IP>
```

Port **80** and **443** must reach the NPM container (for the ACME challenge and HTTPS).

## 2. Run the relay container

The relay joins the same Docker network as NPM so NPM can reach it by name; it is **not** published
to the host.

```bash
docker network create proxy            # once, the network NPM also uses
cd relay
cp .env.example .env
docker compose up -d --build
```

### Required environment variables (`.env`)

| Variable | Required | Example | Notes |
| --- | --- | --- | --- |
| `RELAY_PUBLIC_URL` | **yes** | `https://mcp.terminow.online` | Must be the public **https** origin. Used in OAuth metadata and redirect checks. No trailing slash. |
| `RELAY_DEVICE_TOKENS` | **yes** | `dvc_abc=alice` | `token=userId` pairs, comma-separated. The token is the one TermiNow shows in **MCP Bridge → Relay → Connect**. |
| `RELAY_PAIRING_CODES` | **yes** | `PAIR-ALICE-7Q2=alice` | `code=userId` pairs. The person enters the code on the OAuth consent screen. Treat as passwords; rotate them. |
| `RELAY_ACCESS_TOKEN_TTL_MS` | no | `3600000` | Access-token lifetime. |
| `RELAY_REQUEST_TIMEOUT_MS` | no | `60000` | How long a relayed call waits for the device. |
| `RELAY_ALLOW_DCR` | no | `true` | Dynamic client registration; keep on for broad client support. |

A user's phone and their OAuth sign-in must map to the **same** `userId`: that is how the relay
routes an AI client's calls to the right device.

## 3. HTTPS + proxying in Nginx Proxy Manager

In the NPM admin UI → **Hosts → Proxy Hosts → Add Proxy Host**:

**Details**
- Domain Names: `mcp.terminow.online`
- Scheme: `http`
- Forward Hostname / IP: `terminow-relay`  (the compose service name on the shared `proxy` network)
- Forward Port: `8787`
- Block Common Exploits: on
- Websockets Support: on (harmless; the device channel is SSE, not WebSocket)

**SSL**
- SSL Certificate: **Request a new SSL Certificate** (Let's Encrypt)
- Force SSL: **on**
- HTTP/2 Support: **on**
- HSTS: optional

**Advanced → Custom Nginx Configuration** (the device channel is a long-lived Server-Sent Events
stream, so disable buffering and raise the timeout):

```nginx
location /device/stream {
    proxy_pass http://terminow-relay:8787;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 1h;
    proxy_send_timeout 1h;
}
```

Do **not** put an NPM **Access List** (basic auth) in front of this host: the relay does its own
OAuth, and an extra auth layer would break the ChatGPT/Claude Desktop sign-in and the OAuth
discovery endpoints.

## 4. Authentication setup (what each secret does)

- **Device token** — issued by TermiNow (per device), added to `RELAY_DEVICE_TOKENS`. Authenticates
  the phone's outbound channel. Revoke by removing it and restarting the relay, or **Disconnect** in
  the app.
- **Pairing code** — chosen by you per user in `RELAY_PAIRING_CODES`. The person types it on the
  relay's consent screen so the AI client is bound to that user. Rotate by editing `.env`.
- **OAuth access/refresh tokens** — minted by the relay to the AI client after consent; the client
  sends the access token as a `Bearer` on every `/mcp` call. The relay also honours per-client
  TermiNow pairing tokens (shown in **MCP Bridge → Pair a client**) end to end.

## 5. Persistent storage

- **Configuration** persists in the host `.env` file (mounted read-only into the container). Keep it
  under your secret management / backups.
- **OAuth tokens, codes and DCR registrations are in memory** in this build. That is fine for a
  single container: on restart, clients simply re-authenticate. Do **not** run more than one replica
  without first backing these with shared storage (Redis/DB) and the device hub with a shared bus —
  otherwise a client may hit an instance that lacks its token or its device. Pin one instance per
  user, or add that shared layer, before scaling out.
- No task content is stored on the relay at all; the device holds it.

## 6. Verify

```bash
curl -s https://mcp.terminow.online/.well-known/oauth-protected-resource | jq .
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://mcp.terminow.online/mcp   # expect 401
```

A `401` with a `WWW-Authenticate: Bearer resource_metadata=...` header means the endpoint is up and
correctly demanding OAuth. Then add the connector in ChatGPT/Claude Desktop (see `../README.md`).

> **Still pending:** a real phone + a real ChatGPT/Claude Desktop connector over this public TLS
> endpoint has not been exercised here. Only the on-machine integration tests have run.
