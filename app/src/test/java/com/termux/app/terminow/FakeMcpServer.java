package com.termux.app.terminow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A controlled MCP server for the tests, on 127.0.0.1. It records every method it receives,
 * and has a tool with side effects, `delete_everything`, that a connection test must never call.
 *
 * A minimal HTTP/1.1 server on a plain socket, since the JDK HTTP server is not part of the
 * Android API that the unit tests compile against. Every response closes the connection.
 */
class FakeMcpServer implements AutoCloseable {

    /** A request and its response, with the parts of the JDK HttpExchange API that are used. */
    static class HttpExchange {
        final String method;
        final Headers requestHeaders = new Headers();
        final Headers responseHeaders = new Headers();
        final byte[] body;
        final Socket socket;

        HttpExchange(String method, byte[] body, Socket socket) {
            this.method = method;
            this.body = body;
            this.socket = socket;
        }

        String getRequestMethod() { return method; }
        Headers getRequestHeaders() { return requestHeaders; }
        Headers getResponseHeaders() { return responseHeaders; }
    }

    static class Headers {
        final Map<String, String> values = new LinkedHashMap<>();

        String getFirst(String name) { return values.get(name.toLowerCase(Locale.ROOT)); }
        void add(String name, String value) { values.put(name.toLowerCase(Locale.ROOT), value); }
    }

    enum Mode {
        /** Revision 2026-07-28, server/discover and stateless requests. */
        MODERN,
        /** Revision 2025-11-25 with JSON responses and a session. */
        LEGACY_JSON,
        /** Revision 2025-06-18 with SSE responses, a ping request and notifications before the result. */
        LEGACY_SSE,
        /** A modern server that only supports a future version. */
        MODERN_FUTURE_ONLY,
        /** 401 with an OAuth challenge. */
        OAUTH,
        /** Needs the bearer token "good-token-1234". */
        BEARER,
        /** Never answers in time. */
        SLOW,
        /** Answers with an HTML page and status 200. */
        HTML,
        /** The deprecated HTTP+SSE transport, POST is not allowed. */
        OLD_SSE,
        REDIRECT,
        /** A legacy server whose tools/list cursor never ends. */
        LOOPING_CURSOR
    }

    static final int TOOL_COUNT = 7;
    static final int PAGE_SIZE = 3;
    static final String GOOD_TOKEN = "good-token-1234";
    /** A 1x1 PNG. */
    static final String PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    final List<String> methods = Collections.synchronizedList(new ArrayList<>());
    final List<String> httpMethods = Collections.synchronizedList(new ArrayList<>());
    final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
    /** The responses of the client to requests of the server. */
    final List<JSONObject> responses = Collections.synchronizedList(new ArrayList<>());
    /** The request ids of notifications/cancelled. */
    final List<String> cancelled = Collections.synchronizedList(new ArrayList<>());
    /** The tools that were called, with the Mcp-Name header of a modern request. */
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final ServerSocket mServer;
    private final Mode mMode;
    /** Each server has its own, so that a restarted server does not know the session of the last one. */
    private final String mSessionId = "session-" + java.util.UUID.randomUUID();

    FakeMcpServer(Mode mode) throws IOException {
        this(mode, 0);
    }

    /** A server on a port, to start one again on the port of a stopped one. */
    FakeMcpServer(Mode mode, int port) throws IOException {
        mMode = mode;
        mServer = new ServerSocket();
        mServer.setReuseAddress(true);
        mServer.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50);
        Thread thread = new Thread(() -> {
            while (!mServer.isClosed()) {
                try {
                    Socket socket = mServer.accept();
                    new Thread(() -> serve(socket)).start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    String getUrl() {
        return "http://127.0.0.1:" + mServer.getLocalPort() + "/mcp";
    }

    int getPort() {
        return mServer.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        mServer.close();
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            InputStream in = s.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            HttpExchange exchange;
            Headers headers = new Headers();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
            int length = headers.getFirst("Content-Length") != null ? Integer.parseInt(headers.getFirst("Content-Length")) : 0;
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) break;
                read += n;
            }
            exchange = new HttpExchange(requestLine.split(" ")[0], body, s);
            exchange.requestHeaders.values.putAll(headers.values);
            handle(exchange);
        } catch (IOException e) {
            // The client went away
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') out.write(c);
        }
        if (c == -1 && out.size() == 0) return null;
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            httpMethods.add(exchange.getRequestMethod());
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String body = new String(exchange.body, StandardCharsets.UTF_8);

            if (exchange.getRequestMethod().equals("DELETE")) {
                send(exchange, mSessionId.equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")) ? 200 : 404, null, "");
                return;
            }
            if (mMode == Mode.OLD_SSE) {
                send(exchange, 405, null, "");
                return;
            }
            if (mMode == Mode.REDIRECT) {
                exchange.getResponseHeaders().add("Location", "https://elsewhere.example/mcp");
                send(exchange, 307, null, "");
                return;
            }
            if (mMode == Mode.OAUTH) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer resource_metadata=\"https://auth.example/.well-known/oauth-protected-resource\", scope=\"tools:read\"");
                send(exchange, 401, null, "");
                return;
            }
            if (mMode == Mode.BEARER && !("Bearer " + GOOD_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, "application/json", "{\"error\":\"invalid_token\",\"detail\":\"token Bearer " +
                    exchange.getRequestHeaders().getFirst("Authorization") + " rejected\"}");
                return;
            }
            if (mMode == Mode.SLOW) {
                Thread.sleep(3000);
                send(exchange, 200, "application/json", "{}");
                return;
            }
            if (mMode == Mode.HTML) {
                send(exchange, 200, "text/html", "<html><body>Welcome</body></html>");
                return;
            }

            JSONObject message = new JSONObject(body);
            if (!message.has("method")) {
                responses.add(message);
                send(exchange, 202, null, "");
                return;
            }
            String method = message.optString("method");
            methods.add(method);
            if (method.equals("notifications/cancelled"))
                cancelled.add(String.valueOf(message.optJSONObject("params").opt("requestId")));
            Object id = message.opt("id");
            JSONObject params = message.optJSONObject("params");

            boolean modernServer = mMode == Mode.MODERN || mMode == Mode.MODERN_FUTURE_ONLY || mMode == Mode.BEARER;
            if (modernServer) {
                handleModern(exchange, method, id, params);
            } else {
                handleLegacy(exchange, method, id, params);
            }
        } catch (Exception e) {
            send(exchange, 500, "text/plain", String.valueOf(e));
        }
    }

    private void handleModern(HttpExchange exchange, String method, Object id, JSONObject params) throws Exception {
        String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
        if (id == null) {
            send(exchange, 202, null, "");
            return;
        }
        JSONObject meta = params != null ? params.optJSONObject("_meta") : null;
        if (meta == null || version == null) {
            // What a modern-only server does with a legacy initialize request
            sendError(exchange, 400, id, -32600, "Missing _meta; supported versions: 2026-07-28", null);
            return;
        }
        if (!version.equals(meta.optString("io.modelcontextprotocol/protocolVersion")) ||
            !method.equals(exchange.getRequestHeaders().getFirst("Mcp-Method"))) {
            sendError(exchange, 400, id, -32020, "Header mismatch", null);
            return;
        }
        String supported = mMode == Mode.MODERN_FUTURE_ONLY ? "2099-01-01" : "2026-07-28";
        if (!version.equals(supported)) {
            sendError(exchange, 400, id, -32022, "Unsupported protocol version",
                new JSONObject().put("supported", new JSONArray().put(supported)).put("requested", version));
            return;
        }
        if (method.equals("server/discover")) {
            sendResult(exchange, id, new JSONObject()
                .put("resultType", "complete")
                .put("supportedVersions", new JSONArray().put("2026-07-28"))
                .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                .put("_meta", new JSONObject().put("io.modelcontextprotocol/serverInfo",
                    new JSONObject().put("name", "fake-modern").put("version", "9.1"))), false);
        } else if (method.equals("tools/list")) {
            sendResult(exchange, id, toolsPage(params.optString("cursor", "")), false);
        } else if (method.equals("tools/call")) {
            if (!params.optString("name").equals(exchange.getRequestHeaders().getFirst("Mcp-Name"))) {
                sendError(exchange, 400, id, -32020, "Header mismatch", null);
                return;
            }
            call(exchange, id, params, false);
        } else {
            sendError(exchange, 404, id, -32601, "Method not found", null);
        }
    }

    private void handleLegacy(HttpExchange exchange, String method, Object id, JSONObject params) throws Exception {
        boolean sse = mMode == Mode.LEGACY_SSE;
        if (method.equals("server/discover")) {
            // Like the TypeScript SDK of a legacy server, without a session
            sendError(exchange, 400, null, -32000, "Bad Request: No valid session ID provided", null);
            return;
        }
        if (method.equals("initialize")) {
            String requested = params.optString("protocolVersion");
            String version = sse ? "2025-06-18" : requested;
            exchange.getResponseHeaders().add("Mcp-Session-Id", mSessionId);
            sendResult(exchange, id, new JSONObject()
                .put("protocolVersion", version)
                .put("capabilities", new JSONObject().put("tools", new JSONObject().put("listChanged", true)))
                .put("serverInfo", new JSONObject().put("name", "fake-legacy").put("version", "1.2.3"))
                .put("instructions", "Use carefully"), sse);
            return;
        }
        String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (session == null) {
            sendError(exchange, 400, id, -32000, "Bad Request: No valid session ID provided", null);
            return;
        }
        if (!mSessionId.equals(session)) {
            // What the specification says for a session the server does not know
            sendError(exchange, 404, id, -32001, "Session not found", null);
            return;
        }
        if (method.equals("notifications/initialized")) {
            send(exchange, 202, null, "");
        } else if (method.equals("tools/list")) {
            if (exchange.getRequestHeaders().getFirst("MCP-Protocol-Version") == null) {
                sendError(exchange, 400, id, -32000, "Missing MCP-Protocol-Version", null);
                return;
            }
            String cursor = params != null ? params.optString("cursor", "") : "";
            JSONObject page = toolsPage(cursor);
            if (mMode == Mode.LOOPING_CURSOR) page.put("nextCursor", "same");
            sendResult(exchange, id, page, sse);
        } else if (method.equals("tools/call")) {
            call(exchange, id, params, sse);
        } else if (method.startsWith("notifications/")) {
            send(exchange, 202, null, "");
        } else {
            sendError(exchange, 200, id, -32601, "Method not found", null);
        }
    }

    /** The tools that can be called, whatever tools/list says. */
    private void call(HttpExchange exchange, Object id, JSONObject params, boolean sse) throws Exception {
        String name = params.optString("name");
        JSONObject arguments = params.optJSONObject("arguments");
        calls.add(name);
        switch (name) {
            case "echo": {
                String text = arguments != null ? arguments.optString("message") : "";
                sendResult(exchange, id, new JSONObject()
                    .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "Echo: " + text)))
                    .put("structuredContent", new JSONObject().put("echoed", text)), sse);
                return;
            }
            case "fail_tool":
                sendResult(exchange, id, new JSONObject().put("isError", true)
                    .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "The tool failed"))), sse);
                return;
            case "image_tool":
                sendResult(exchange, id, new JSONObject().put("content", new JSONArray()
                    .put(new JSONObject().put("type", "image").put("mimeType", "image/png").put("data", PNG_BASE64))
                    .put(new JSONObject().put("type", "resource_link").put("uri", "file:///tmp/a.txt").put("name", "a.txt"))), sse);
                return;
            case "slow_tool": {
                // Until the client cancels, or for 10 s. An SSE server starts its stream at once
                if (sse) startEventStream(exchange);
                long end = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < end && !cancelled.contains(String.valueOf(id))) Thread.sleep(20);
                if (cancelled.contains(String.valueOf(id))) return;
                JSONObject done = new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", new JSONObject().put("content", new JSONArray()));
                if (sse) {
                    writeChunk(exchange.socket.getOutputStream(), "data: " + done + "\n\n");
                    exchange.socket.getOutputStream().write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    return;
                }
                send(exchange, 200, "application/json", done.toString());
                return;
            }
            default:
                sendError(exchange, 200, id, -32602, "Unknown tool: " + name, null);
        }
    }

    private static JSONObject toolsPage(String cursor) throws Exception {
        int start = cursor.isEmpty() || cursor.equals("same") ? 0 : Integer.parseInt(cursor.substring("page-".length()));
        JSONArray tools = new JSONArray();
        for (int i = start; i < Math.min(TOOL_COUNT, start + PAGE_SIZE); i++) {
            String name = i == 0 ? "delete_everything" : "tool_" + i;
            tools.put(new JSONObject().put("name", name).put("description", "Tool number " + i)
                .put("inputSchema", new JSONObject().put("type", "object")));
        }
        JSONObject result = new JSONObject().put("tools", tools);
        if (start + PAGE_SIZE < TOOL_COUNT) result.put("nextCursor", "page-" + (start + PAGE_SIZE));
        return result;
    }

    private void sendResult(HttpExchange exchange, Object id, JSONObject result, boolean sse) throws Exception {
        JSONObject response = new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
        if (!sse) {
            send(exchange, 200, "application/json", response.toString());
            return;
        }
        // A ping request and a notification of the server come first, then the response
        String events = "id: 1\ndata:\n\n" +
            ": keep-alive\n\n" +
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"srv-1\",\"method\":\"ping\"}\n\n" +
            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{\"level\":\"info\",\"data\":\"hi\"}}\n\n" +
            "event: message\ndata: " + response + "\n\n";
        send(exchange, 200, "text/event-stream", events);
    }

    /**
     * The headers of a chunked SSE response and a comment, with the stream left open, like an
     * Express server sends a stream.
     */
    private static void startEventStream(HttpExchange exchange) throws IOException {
        OutputStream out = exchange.socket.getOutputStream();
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        writeChunk(out, ": working\n\n");
    }

    private static void writeChunk(OutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void sendError(HttpExchange exchange, int status, Object id, int code, String message, JSONObject data) throws Exception {
        JSONObject error = new JSONObject().put("code", code).put("message", message);
        if (data != null) error.put("data", data);
        JSONObject response = new JSONObject().put("jsonrpc", "2.0").put("error", error);
        response.put("id", id == null ? JSONObject.NULL : id);
        send(exchange, status, "application/json", response.toString());
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder("HTTP/1.1 " + status + " " + reason(status) + "\r\n");
        if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n");
        for (Map.Entry<String, String> header : exchange.responseHeaders.values.entrySet())
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        head.append("Content-Length: ").append(bytes.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        OutputStream out = exchange.socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 500: return "Internal Server Error";
            case 307: return "Temporary Redirect";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            default: return "Status";
        }
    }

}
