# TermiNow MCP relay

A standards-compliant **remote MCP** endpoint that lets an external AI client (ChatGPT developer
mode, Claude Desktop custom connectors) reach the sessions a user **explicitly shared** in TermiNow,
without ever exposing the phone. TermiNow dials **out** to the relay and holds the connection open;
the relay forwards MCP JSON-RPC to the device and returns the reply. The device runs the tasks and
keeps their state — the relay stores no session content.

```
  ChatGPT / Claude Desktop                 Relay (this)                     TermiNow (phone)
  ────────────────────────      ─────────────────────────────      ──────────────────────────────
  Streamable-HTTP MCP  ──POST /mcp──▶  OAuth 2.1 resource server
     + OAuth 2.1 Bearer                 routes to the user's device  ◀── GET /device/stream (SSE) ──
                         ◀── JSON ───   correlates the reply         ──▶ POST /device/rpc ──────────
                                                                         answers with the in-app
                                                                         MCP bridge (per shared
                                                                         session, per capability)
```

Nothing in the relay knows the device's address: the device is only ever an **outbound** SSE client
plus POSTs. The device's own local bridge (a custom-handshake loopback listener) is never published.

## Why this shape

Verified against current requirements before claiming compatibility:

- **ChatGPT** (developer mode / connectors) requires a **remote** server on **public HTTPS** speaking
  **Streamable HTTP**, with **OAuth** for user data. A localhost or plain-http URL is rejected.
- The **MCP authorization spec (2025-06-18+)** makes the server an OAuth 2.1 **resource server** that
  must publish **Protected Resource Metadata (RFC 9728)** and answer `401` with `WWW-Authenticate`,
  and clients use **PKCE**. Dynamic Client Registration (RFC 7591) is supported for clients that
  still use it (newer revisions move toward CIMD; DCR is kept for compatibility).

The relay implements exactly this: `/.well-known/oauth-protected-resource`,
`/.well-known/oauth-authorization-server`, `/authorize` (PKCE S256), `/token`, optional `/register`,
and `POST /mcp` guarded by a Bearer token.

## Endpoints

| Method | Path | For | Auth |
| --- | --- | --- | --- |
| GET | `/.well-known/oauth-protected-resource` | discovery (RFC 9728) | none |
| GET | `/.well-known/oauth-authorization-server` | discovery (RFC 8414) | none |
| POST | `/register` | dynamic client registration (RFC 7591) | none |
| GET/POST | `/authorize` | user consent + PKCE | pairing code |
| POST | `/token` | authorization_code / refresh_token | PKCE |
| POST | `/mcp` | the MCP endpoint for AI clients | OAuth Bearer |
| GET | `/device/stream` | the device's outbound request stream (SSE) | device token |
| POST | `/device/rpc` | the device's responses | device token |

## Run it

```bash
cd relay
cp .env.example .env         # edit RELAY_PUBLIC_URL, RELAY_DEVICE_TOKENS, RELAY_PAIRING_CODES
npm start                    # node src/server.js ; no third-party dependencies
npm test                     # local integration tests (node --test)
```

Put it behind TLS — it listens on plain HTTP so a proxy adds HTTPS/HTTP2:

- **Caddy**: `deploy/Caddyfile` (automatic certificates).
- **nginx**: `deploy/nginx.conf` (certbot certificate; SSE buffering disabled).
- **systemd**: `deploy/terminow-relay.service`.

## Pair a user and a device

1. In TermiNow: **MCP Bridge → Relay → Connect**, enter the relay URL. The app shows a **device
   token** once. Add it to the relay as `RELAY_DEVICE_TOKENS=<token>=<userId>` and restart the relay
   (or add it to your secret store). The device then stays connected outbound.
2. Choose a **pairing code** for that user: `RELAY_PAIRING_CODES=<code>=<userId>`. Give the code to
   the person; they enter it on the consent screen when the AI client connects.

## Connect ChatGPT (developer mode)

1. Enable developer mode (Settings → Connectors / Apps & Connectors → Advanced; availability depends
   on your plan and workspace policy).
2. Add a connector with URL `https://relay.example.com/mcp`, authentication **OAuth**.
3. ChatGPT discovers the OAuth metadata, you sign in by entering the **pairing code**, and the shared
   sessions' tools (`sessions_list`, `task_submit`, `task_status`, `task_output`, `task_cancel`,
   `task_list`) become available.

## Connect Claude Desktop (custom connector)

Settings → Connectors → Add custom connector → `https://relay.example.com/mcp`. It runs the same
OAuth flow and sees the same tools.

## Example MCP calls (what the client sends to `/mcp`)

```jsonc
// 1. discover shared sessions
{ "jsonrpc":"2.0","id":1,"method":"tools/call",
  "params":{ "name":"sessions_list","arguments":{} } }

// 2. submit a task to a managed session
{ "jsonrpc":"2.0","id":2,"method":"tools/call",
  "params":{ "name":"task_submit","arguments":{
      "session_id":"<from sessions_list>","request":"summarise TODOs in this repo",
      "idempotency_key":"abc-1" } } }

// 3. poll status, then page output
{ "jsonrpc":"2.0","id":3,"method":"tools/call",
  "params":{ "name":"task_status","arguments":{ "task_id":"<id>" } } }
{ "jsonrpc":"2.0","id":4,"method":"tools/call",
  "params":{ "name":"task_output","arguments":{ "task_id":"<id>","cursor":0 } } }
```

## Tests actually run

`npm test` (`node --test`, no network to the internet) passes locally:

- OAuth metadata is published (PRM + AS metadata, `S256`).
- An unauthenticated `/mcp` call is challenged with `WWW-Authenticate` pointing at the PRM.
- A full **OAuth 2.1 + PKCE** flow (authorize → token) then an MCP `tools/list` is **routed to a
  connected stand-in device and back**.
- A wrong device token cannot open the device channel.
- With no device connected, a call fails cleanly instead of hanging.

The stand-in device mimics the Android connector (`TerminowMcpBridgeRelay`): SSE down, POST up.

## Known limitations / pending

- **Remote end-to-end is pending.** These tests run on one machine with a stand-in device. A real
  phone + a real ChatGPT/Claude Desktop connector over public TLS has **not** been exercised here
  and must be verified in a deployment before claiming live compatibility.
- **Single device per user** is assumed for routing; multi-device, per-session device routing is a
  follow-up (the protocol already carries a session id, so the hub can target a specific device).
- **In-memory token/registration storage.** A single instance is fine; a multi-instance deployment
  must back OAuth tokens, codes and registrations with shared storage (Redis/DB) and the device hub
  with a shared bus, or pin a user to one instance.
- **Managed mode only.** Tasks run through `claude -p` on the device with Claude Code's default
  permissions; no automatic-approval or arbitrary-shell tool is exposed.
