#!/usr/bin/env python3
# TermiNow Local AI Bridge helper.
#
# Claude Code launches this as an MCP stdio server. It does not implement any tool itself: it
# connects to the TermiNow Android bridge over a private local socket, authenticates with a
# per-environment token, and then relays the MCP JSON-RPC bytes both ways. Every tool call is
# answered and authorized inside the TermiNow app, never here.
#
# stdout carries ONLY the MCP protocol bytes coming from the bridge. Everything diagnostic goes
# to stderr. The token is read from a 0600 file (TERMINOW_AI_TOKEN_FILE), never from the command
# line, and is never printed.
#
# Configuration comes from the environment, set by TermiNow when it registers this server:
#   TERMINOW_AI_TRANSPORT   "unix" (default) or "tcp"
#   TERMINOW_AI_SOCK        unix socket path          (transport unix)
#   TERMINOW_AI_HOST        host, default 127.0.0.1   (transport tcp)
#   TERMINOW_AI_PORT        port, a starting value    (transport tcp)
#   TERMINOW_AI_PORT_FILE   file holding the live port, read fresh each connect (transport tcp)
#   TERMINOW_AI_TOKEN_FILE  path to a file holding the token (preferred)
#   TERMINOW_AI_TOKEN       the token itself (fallback; discouraged)

import os
import sys
import socket
import threading
import time


# A per-process client id, generated once at start. It names this one Claude Code process to the
# bridge so its host selection is kept apart from other clients of the same environment: two Ubuntu
# tabs are two processes with two ids; a bridge restart reuses the same id (same process) so the
# selection survives; a full Claude restart is a new process with a new id, so nothing stale carries
# over. TERMINOW_AI_CLIENT_ID overrides it (used by tests).
_PROCESS_CLIENT_ID = os.environ.get("TERMINOW_AI_CLIENT_ID", "").strip() or os.urandom(16).hex()


def log(*a):
    print("[terminow-ai]", *a, file=sys.stderr, flush=True)


def client_id():
    return _PROCESS_CLIENT_ID


def read_token():
    path = os.environ.get("TERMINOW_AI_TOKEN_FILE", "")
    if path:
        try:
            with open(path, "r") as f:
                return f.read().strip()
        except OSError as e:
            log("cannot read token file:", e)
            return ""
    return os.environ.get("TERMINOW_AI_TOKEN", "").strip()


def read_port():
    # The live port, written by TermiNow on every bind; falls back to the registration env.
    path = os.environ.get("TERMINOW_AI_PORT_FILE", "")
    if path:
        try:
            with open(path, "r") as f:
                value = f.read().strip()
            if value.isdigit() and int(value) > 0:
                return int(value)
        except OSError:
            pass
    try:
        return int(os.environ.get("TERMINOW_AI_PORT", "0"))
    except ValueError:
        return 0


def connect():
    transport = os.environ.get("TERMINOW_AI_TRANSPORT", "unix").lower()
    if transport == "tcp":
        host = os.environ.get("TERMINOW_AI_HOST", "127.0.0.1")
        port = read_port()
        if not port:
            log("no bridge port (set TERMINOW_AI_PORT or TERMINOW_AI_PORT_FILE)")
            return None
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(15)
        try:
            s.connect((host, port))
        except OSError:
            s.close()  # do not leak the socket when the bridge is down
            raise
        log("connected tcp", host, port)
    else:
        path = os.environ.get("TERMINOW_AI_SOCK", "")
        if not path:
            log("TERMINOW_AI_SOCK not set")
            return None
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(15)
        try:
            s.connect(path)
        except OSError:
            s.close()
            raise
        log("connected unix", path)
    s.settimeout(None)
    return s


def authenticate(sock, token):
    # AUTH <token>\n -> OK\n or ERR <reason>\n, then an optional CLIENT <id>\n -> OK\n that registers
    # this process so its host selection is private to it. The env token still authorizes the
    # environment; the client id only separates concurrent clients within it.
    sock.sendall(b"AUTH " + token.encode("utf-8") + b"\n")
    reply = b""
    while not reply.endswith(b"\n"):
        chunk = sock.recv(64)
        if not chunk:
            break
        reply += chunk
    if reply.strip() != b"OK":
        return False
    cid = client_id()
    if cid:
        sock.sendall(b"CLIENT " + cid.encode("utf-8") + b"\n")
        reply = b""
        while not reply.endswith(b"\n"):
            chunk = sock.recv(64)
            if not chunk:
                break
            reply += chunk
        if reply.strip() != b"OK":
            return False
    return True


def pump(src_recv, dst_write, name):
    try:
        while True:
            data = src_recv(65536)
            if not data:
                break
            dst_write(data)
    except OSError as e:
        log(name, "ended:", e)


# How long to keep trying to reach the bridge before giving up, so a terminal reconnect or a
# bridge restart (a brief window where the listener is down or on a new port) heals itself.
CONNECT_DEADLINE_S = 60.0


def connect_with_backoff():
    """Connect and authenticate, retrying with bounded backoff. Returns (sock, error)."""
    deadline = time.monotonic() + CONNECT_DEADLINE_S
    delay = 0.5
    last = "unknown"
    while True:
        token = read_token()
        if not token:
            return None, "no token; run 'Connect Claude Code' in TermiNow"
        sock = None
        try:
            sock = connect()  # reads the live port each attempt
        except OSError as e:
            last = str(e)
        if sock is not None:
            try:
                ok = authenticate(sock, token)
            except OSError as e:
                ok = False
                last = str(e)
            if ok:
                return sock, None
            try:
                sock.close()
            except OSError:
                pass
            last = "the bridge rejected authentication"
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None, last
        time.sleep(min(delay, remaining))
        delay = min(delay * 2, 8.0)


def _recv_line(sock):
    buf = b""
    while not buf.endswith(b"\n"):
        chunk = sock.recv(65536)
        if not chunk:
            break
        buf += chunk
    return buf.decode("utf-8", "replace").strip()


def _rpc(sock, mid, method, params):
    import json
    sock.sendall((json.dumps({"jsonrpc": "2.0", "id": mid, "method": method, "params": params}) + "\n").encode("utf-8"))
    line = _recv_line(sock)
    return json.loads(line) if line else None


def doctor(verbose):
    # Connect to the bridge from inside the environment and run the real MCP handshake. Prints a
    # sanitized report to stdout; never prints the token or any credential. Exit 0 only if usable.
    import json
    print("TermiNow Local AI Bridge — doctor")
    token = read_token()
    print("token:", "present" if token else "MISSING (run Connect & Test in TermiNow)")
    port = read_port()
    print("port:", port if port else "unknown")
    if not token:
        return 2
    try:
        sock = connect()
    except OSError as e:
        print("bridge: UNREACHABLE (%s)" % e)
        return 3
    if sock is None:
        print("bridge: UNREACHABLE (no transport configured)")
        return 3
    try:
        if not authenticate(sock, token):
            print("auth: REJECTED (token not accepted — reconnect in TermiNow)")
            return 3
        print("auth: OK")
        init = _rpc(sock, 1, "initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                            "clientInfo": {"name": "terminow-doctor", "version": "1"}})
        res = (init or {}).get("result") or {}
        print("mcp:", "OK protocol", res.get("protocolVersion", "?"), "server", (res.get("serverInfo") or {}).get("name", "?"))
        sock.sendall((json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n").encode("utf-8"))
        tl = _rpc(sock, 2, "tools/list", {})
        tools = [t.get("name") for t in ((tl or {}).get("result") or {}).get("tools", [])]
        print("tools:", ", ".join(tools) if tools else "NONE")
        hl = _rpc(sock, 3, "tools/call", {"name": "hosts_list", "arguments": {}})
        content = ((hl or {}).get("result") or {}).get("content") or []
        hosts = []
        if content:
            try:
                hosts = json.loads(content[0].get("text", "{}")).get("hosts", [])
            except (ValueError, AttributeError):
                hosts = []
        print("authorized hosts:", len(hosts))
        if verbose:
            for h in hosts:
                print("  - %s  %s@%s:%s  caps=%s" % (h.get("host_id"), h.get("username"), h.get("hostname"),
                                                     h.get("port"), ",".join(h.get("capabilities", []))))
        return 0
    finally:
        try:
            sock.close()
        except OSError:
            pass


def status():
    token = read_token()
    port = read_port()
    print("token:", "present" if token else "missing")
    print("port:", port if port else "unknown")
    if not token:
        print("bridge: not provisioned")
        return 2
    try:
        sock = connect()
        ok = sock is not None and authenticate(sock, token)
        if sock is not None:
            sock.close()
        print("bridge:", "connected" if ok else "reachable but auth failed" if sock is not None else "unreachable")
        return 0 if ok else 3
    except OSError as e:
        print("bridge: unreachable (%s)" % e)
        return 3


def main():
    # Subcommands for the `terminow bridge` CLI; with no subcommand this is the MCP stdio relay.
    args = sys.argv[1:]
    if args and args[0] in ("doctor", "status"):
        return doctor("-v" in args or "--verbose" in args) if args[0] == "doctor" else status()
    if not read_token():
        log("no token; run 'Connect Claude Code' in TermiNow")
        return 2
    sock, error = connect_with_backoff()
    if sock is None:
        log("could not reach the TermiNow Local AI Bridge (" + str(error) + "). "
            "Open TermiNow > AI, turn the Local AI Bridge on, then reconnect Claude Code.")
        return 3

    stdin = sys.stdin.buffer
    stdout = sys.stdout.buffer

    def to_bridge():
        pump(stdin.read1 if hasattr(stdin, "read1") else stdin.read, sock.sendall, "stdin->bridge")
        try:
            sock.shutdown(socket.SHUT_WR)
        except OSError:
            pass

    def from_bridge():
        def write(data):
            stdout.write(data)
            stdout.flush()
        pump(sock.recv, write, "bridge->stdout")

    t = threading.Thread(target=to_bridge, daemon=True)
    t.start()
    from_bridge()
    try:
        sock.close()
    except OSError:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
