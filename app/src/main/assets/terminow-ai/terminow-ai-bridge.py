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
#   TERMINOW_AI_PORT        port                      (transport tcp)
#   TERMINOW_AI_TOKEN_FILE  path to a file holding the token (preferred)
#   TERMINOW_AI_TOKEN       the token itself (fallback; discouraged)

import os
import sys
import socket
import threading


def log(*a):
    print("[terminow-ai]", *a, file=sys.stderr, flush=True)


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


def connect():
    transport = os.environ.get("TERMINOW_AI_TRANSPORT", "unix").lower()
    if transport == "tcp":
        host = os.environ.get("TERMINOW_AI_HOST", "127.0.0.1")
        port = int(os.environ.get("TERMINOW_AI_PORT", "0"))
        if not port:
            log("TERMINOW_AI_PORT not set")
            return None
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(15)
        s.connect((host, port))
        log("connected tcp", host, port)
    else:
        path = os.environ.get("TERMINOW_AI_SOCK", "")
        if not path:
            log("TERMINOW_AI_SOCK not set")
            return None
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(15)
        s.connect(path)
        log("connected unix", path)
    s.settimeout(None)
    return s


def authenticate(sock, token):
    # One line: AUTH <token>\n  -> OK\n or ERR <reason>\n
    sock.sendall(b"AUTH " + token.encode("utf-8") + b"\n")
    reply = b""
    while not reply.endswith(b"\n"):
        chunk = sock.recv(64)
        if not chunk:
            break
        reply += chunk
    return reply.strip() == b"OK"


def pump(src_recv, dst_write, name):
    try:
        while True:
            data = src_recv(65536)
            if not data:
                break
            dst_write(data)
    except OSError as e:
        log(name, "ended:", e)


def main():
    token = read_token()
    if not token:
        log("no token; run 'Connect Claude Code' in TermiNow")
        return 2
    try:
        sock = connect()
    except OSError as e:
        log("connect failed:", e)
        return 3
    if sock is None:
        return 3
    if not authenticate(sock, token):
        log("authentication rejected by the bridge")
        try:
            sock.close()
        except OSError:
            pass
        return 4

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
