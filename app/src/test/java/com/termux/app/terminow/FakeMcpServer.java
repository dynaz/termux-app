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
    static final String SESSION_ID = "session-abc-123";
    static final String GOOD_TOKEN = "good-token-1234";

    final List<String> methods = Collections.synchronizedList(new ArrayList<>());
    final List<String> httpMethods = Collections.synchronizedList(new ArrayList<>());
    final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
    private final ServerSocket mServer;
    private final Mode mMode;

    FakeMcpServer(Mode mode) throws IOException {
        mMode = mode;
        mServer = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
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
                send(exchange, SESSION_ID.equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")) ? 200 : 404, null, "");
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
            String method = message.optString("method");
            methods.add(method);
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
            exchange.getResponseHeaders().add("Mcp-Session-Id", SESSION_ID);
            sendResult(exchange, id, new JSONObject()
                .put("protocolVersion", version)
                .put("capabilities", new JSONObject().put("tools", new JSONObject().put("listChanged", true)))
                .put("serverInfo", new JSONObject().put("name", "fake-legacy").put("version", "1.2.3"))
                .put("instructions", "Use carefully"), sse);
            return;
        }
        if (!SESSION_ID.equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
            sendError(exchange, 400, id, -32000, "Bad Request: No valid session ID provided", null);
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
        } else {
            sendError(exchange, 200, id, -32601, "Method not found", null);
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
            case 307: return "Temporary Redirect";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            default: return "Status";
        }
    }

}
