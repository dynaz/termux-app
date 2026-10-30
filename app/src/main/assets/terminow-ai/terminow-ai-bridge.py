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

import hashlib
import json
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

# The CLI subcommands set this to a terminal-stable id, so a `connect` is still in effect for the
# next `status` in the same tab. The MCP relay leaves it None and uses the per-process id, which is
# what keeps two tabs apart.
_CLIENT_ID_OVERRIDE = None


def log(*a):
    print("[terminow-ai]", *a, file=sys.stderr, flush=True)


def client_id():
    return _CLIENT_ID_OVERRIDE or _PROCESS_CLIENT_ID


def cli_client_id():
    # Stable within one terminal and distinct per tab, via the controlling terminal. A pipe with no
    # tty falls back to this process's id. This CLI client is separate from Claude Code's own client.
    override = os.environ.get("TERMINOW_AI_CLIENT_ID", "").strip()
    if override:
        return override
    for fd in (0, 1, 2):
        try:
            return "cli-" + hashlib.sha256(os.ttyname(fd).encode("utf-8")).hexdigest()[:24]
        except OSError:
            continue
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


def _close(sock):
    try:
        sock.close()
    except OSError:
        pass


def _open():
    # Connect, authenticate (which registers this client), and run the MCP handshake. Returns the
    # live socket, or prints the reason and returns None. Never prints a token or credential.
    token = read_token()
    if not token:
        print("bridge: not provisioned (run Connect & Test in TermiNow)")
        return None
    try:
        sock = connect()
    except OSError as e:
        print("bridge: unreachable (%s)" % e)
        return None
    if sock is None:
        print("bridge: no transport configured")
        return None
    if not authenticate(sock, token):
        print("bridge: authentication failed (reconnect in TermiNow)")
        _close(sock)
        return None
    _rpc(sock, 1, "initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                 "clientInfo": {"name": "terminow-cli", "version": "1"}})
    sock.sendall((json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n").encode("utf-8"))
    return sock


def _tool(sock, mid, name, arguments):
    resp = _rpc(sock, mid, "tools/call", {"name": name, "arguments": arguments})
    result = (resp or {}).get("result") or {}
    content = result.get("content") or []
    body = {}
    if content:
        try:
            body = json.loads(content[0].get("text", "{}"))
        except (ValueError, AttributeError):
            body = {}
    return bool(result.get("isError", False)), body


def status():
    token = read_token()
    port = read_port()
    print("token:", "present" if token else "missing")
    print("port:", port if port else "unknown")
    if not token:
        print("bridge: not provisioned")
        return 2
    sock = _open()
    if sock is None:
        return 3
    try:
        print("bridge: connected")
        is_error, body = _tool(sock, 2, "bridge_status", {})
        selected = None if is_error else body.get("selected_host")
        if selected:
            print("selected host:", selected.get("host_id"), "(%s)" % selected.get("alias", ""))
        else:
            print("selected host: none (use: terminow bridge connect <host-id>)")
        return 0
    finally:
        _close(sock)


def cmd_hosts():
    sock = _open()
    if sock is None:
        return 3
    try:
        is_error, body = _tool(sock, 2, "hosts_list", {})
        if is_error:
            print("error:", body.get("message", body.get("error", "failed")))
            return 3
        hosts = body.get("hosts", [])
        if not hosts:
            print("No hosts are available to this environment. Enable AI access for a host in TermiNow.")
            return 0
        for h in hosts:
            print("%-16s %-20s %s@%s:%s  [%s]" % (
                h.get("host_id", "?"), h.get("alias", ""), h.get("username", ""),
                h.get("hostname", ""), h.get("port", ""), ",".join(h.get("capabilities", []))))
        return 0
    finally:
        _close(sock)


def cmd_connect(host_id):
    if not host_id:
        print("usage: terminow bridge connect <host-id>   (list ids with: terminow bridge hosts)")
        return 2
    sock = _open()
    if sock is None:
        return 3
    try:
        is_error, body = _tool(sock, 2, "select_host", {"host_id": host_id})
        if is_error:
            print("could not select:", body.get("message", body.get("error", "failed")))
            return 3
        print("selected %s (%s) for this terminal." % (body.get("host_id"), body.get("alias", "")))
        print("note: Claude Code chooses its own host with /bridge; this selection is the CLI client's.")
        return 0
    finally:
        _close(sock)


def cmd_disconnect():
    sock = _open()
    if sock is None:
        return 3
    try:
        is_error, _ = _tool(sock, 2, "disconnect_host", {})
        print("the CLI client's host selection was cleared." if not is_error else "could not clear the selection.")
        return 0 if not is_error else 3
    finally:
        _close(sock)


def main():
    # Subcommands for the `terminow bridge` CLI; with no subcommand this is the MCP stdio relay.
    args = sys.argv[1:]
    if args and args[0] in ("doctor", "status", "hosts", "connect", "disconnect"):
        global _CLIENT_ID_OVERRIDE
        _CLIENT_ID_OVERRIDE = cli_client_id()
        cmd = args[0]
        if cmd == "doctor":
            return doctor("-v" in args or "--verbose" in args)
        if cmd == "status":
            return status()
        if cmd == "hosts":
            return cmd_hosts()
        if cmd == "connect":
            return cmd_connect(args[1] if len(args) > 1 else "")
        return cmd_disconnect()
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
