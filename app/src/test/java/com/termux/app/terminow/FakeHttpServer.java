package com.termux.app.terminow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A tiny HTTP/1.1 server for provider tests (pure {@link ServerSocket}; Android's unit-test classpath
 * has no {@code com.sun.net.httpserver}). A handler maps a parsed request to a response.
 */
final class FakeHttpServer implements AutoCloseable {

    interface Handler {
        Response handle(Request request) throws IOException;
    }

    static final class Request {
        final String method;
        final String path;
        final String query;
        final Map<String, String> headers;   // lower-cased names
        final byte[] body;

        Request(String method, String path, String query, Map<String, String> headers, byte[] body) {
            this.method = method; this.path = path; this.query = query; this.headers = headers; this.body = body;
        }

        String header(String name) { return headers.get(name.toLowerCase()); }
        String bodyText() { return new String(body, StandardCharsets.UTF_8); }
    }

    static final class Response {
        final int code;
        final byte[] body;

        Response(int code, byte[] body) { this.code = code; this.body = body; }

        static Response json(int code, String body) { return new Response(code, body.getBytes(StandardCharsets.UTF_8)); }
        static Response bytes(int code, byte[] body) { return new Response(code, body); }
    }

    private final ServerSocket mSocket;
    private final Handler mHandler;

    FakeHttpServer(Handler handler) throws IOException {
        mHandler = handler;
        mSocket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(this::acceptLoop);
        accept.setDaemon(true);
        accept.start();
    }

    String baseUrl() { return "http://127.0.0.1:" + mSocket.getLocalPort(); }

    private void acceptLoop() {
        while (!mSocket.isClosed()) {
            try {
                Socket s = mSocket.accept();
                new Thread(() -> serve(s)).start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String target = parts.length > 1 ? parts[1] : "/";
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
            Map<String, String> headers = new HashMap<>();
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                headers.put(name, value);
                if (name.equals("content-length")) contentLength = Integer.parseInt(value);
            }
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(body, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            Response response;
            try {
                response = mHandler.handle(new Request(method, path, query, headers, body));
            } catch (RuntimeException | IOException e) {
                response = Response.json(500, "{\"error\":\"" + e + "\"}");
            }
            byte[] out0 = response.body == null ? new byte[0] : response.body;
            String head = "HTTP/1.1 " + response.code + " X\r\nContent-Length: " + out0.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            if (out0.length > 0) out.write(out0);
            out.flush();
        } catch (IOException ignored) {
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
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        mSocket.close();
    }
}
