package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * A small HTTPS helper for the cloud importers: one request, with timeouts and a response-size cap,
 * over TLS only. TLS validation is left at the platform default and never disabled. It carries no
 * provider logic and logs nothing, so a token or signature is never written anywhere.
 */
public final class TerminowCloudHttp {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        public boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    private TerminowCloudHttp() {}

    /**
     * Make one HTTPS request. {@code body} is sent when non-null. The response body is read whether or
     * not the status is a success, so the caller can show a provider error message.
     *
     * @throws IOException on a network failure or a non-HTTPS URL.
     */
    @NonNull
    public static Response request(@NonNull String method, @NonNull String urlString,
                                   @NonNull Map<String, String> headers, @Nullable String body) throws IOException {
        URL url = new URL(urlString);
        if (!"https".equalsIgnoreCase(url.getProtocol()))
            throw new IOException("Refusing a non-HTTPS request");

        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> e : headers.entrySet()) connection.setRequestProperty(e.getKey(), e.getValue());

            if (body != null) {
                connection.setDoOutput(true);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(code, read(stream));
        } finally {
            connection.disconnect();
        }
    }

    @NonNull
    private static String read(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_RESPONSE_BYTES) throw new IOException("The response is too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
