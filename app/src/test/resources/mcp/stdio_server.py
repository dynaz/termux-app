"""A small MCP server over stdio for the tests of TermiNow, like most local servers speak it."""
import json
import os
import sys
import threading
import time

VERSIONS = ["2025-11-25", "2025-06-18", "2025-03-26"]
TOOLS = [
    {"name": "echo", "description": "Echo the message",
     "inputSchema": {"type": "object", "properties": {"message": {"type": "string"}}, "required": ["message"]},
     "annotations": {"readOnlyHint": True}},
    {"name": "ping_me", "description": "Ping the client, then answer", "inputSchema": {"type": "object"}},
    {"name": "crash", "description": "Exit in the middle of the call", "inputSchema": {"type": "object"},
     "annotations": {"destructiveHint": True}},
    {"name": "slow", "description": "Answer after 5 s unless cancelled", "inputSchema": {"type": "object"}},
    {"name": "cancelled_ids", "description": "The cancelled request ids", "inputSchema": {"type": "object"}},
    {"name": "pid", "description": "The process id", "inputSchema": {"type": "object"}},
]

lock = threading.Lock()
cancelled = []
waiting_ping = {}


def send(message):
    with lock:
        sys.stdout.write(json.dumps(message) + "\n")
        sys.stdout.flush()


def result(request_id, value):
    send({"jsonrpc": "2.0", "id": request_id, "result": value})


def text(value):
    return {"content": [{"type": "text", "text": value}]}


def slow(request_id):
    end = time.time() + 5
    while time.time() < end and request_id not in cancelled:
        time.sleep(0.02)
    if request_id not in cancelled:
        result(request_id, text("slow done"))


if os.environ.get("FIXTURE_NOISE"):
    sys.stdout.write("fixture starting\n")
    sys.stdout.flush()

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    message = json.loads(line)
    method = message.get("method")
    request_id = message.get("id")
    params = message.get("params") or {}
    if method is None:
        call_id = waiting_ping.pop(request_id, None)
        if call_id is not None:
            result(call_id, text("pong " + json.dumps(message.get("result"))))
        continue
    if method == "initialize":
        requested = params.get("protocolVersion")
        version = os.environ.get("FIXTURE_VERSION") or (requested if requested in VERSIONS else VERSIONS[0])
        result(request_id, {"protocolVersion": version, "capabilities": {"tools": {}},
                            "serverInfo": {"name": "fixture", "version": "0.1"}})
    elif method == "notifications/initialized":
        pass
    elif method == "notifications/cancelled":
        cancelled.append(params.get("requestId"))
    elif method == "tools/list":
        start = int(params.get("cursor") or 0)
        page = {"tools": TOOLS[start:start + 4]}
        if start + 4 < len(TOOLS):
            page["nextCursor"] = str(start + 4)
        result(request_id, page)
    elif method == "tools/call":
        name = params.get("name")
        arguments = params.get("arguments") or {}
        if name == "echo":
            value = text("Echo: " + str(arguments.get("message")))
            value["structuredContent"] = {"echoed": arguments.get("message")}
            result(request_id, value)
        elif name == "ping_me":
            waiting_ping["srv-ping"] = request_id
            send({"jsonrpc": "2.0", "id": "srv-ping", "method": "ping"})
        elif name == "crash":
            sys.stderr.write("fatal: the fixture crashed on purpose, token=" + os.environ.get("FIXTURE_SECRET", "") + "\n")
            sys.stderr.flush()
            os._exit(3)
        elif name == "slow":
            threading.Thread(target=slow, args=(request_id,), daemon=True).start()
        elif name == "cancelled_ids":
            result(request_id, text(json.dumps(cancelled)))
        elif name == "pid":
            result(request_id, text(str(os.getpid())))
        else:
            send({"jsonrpc": "2.0", "id": request_id, "error": {"code": -32602, "message": "Unknown tool: " + str(name)}})
    elif request_id is not None:
        send({"jsonrpc": "2.0", "id": request_id, "error": {"code": -32601, "message": "Method not found"}})
