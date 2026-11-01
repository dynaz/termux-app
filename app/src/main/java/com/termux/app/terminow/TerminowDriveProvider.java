package com.termux.app.terminow;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Drive backup provider (Drive REST v3), storing backups in the hidden, app-scoped
 * {@code appDataFolder} (scope {@code drive.appdata}), so it never touches the user's own files and
 * needs no folder picking. A key is used as the Drive file name (the appDataFolder is flat); delete
 * and open resolve the name to a file id. Multipart upload; large-file resumable upload is a
 * documented follow-up. Needs a Google OAuth access token with the appdata scope (see the report);
 * verified here against a fake server, not live.
 */
final class TerminowDriveProvider extends TerminowHttpProvider {

    private static final String BOUNDARY = "terminowBackupBoundary7MA4YWxkTrZu0gW";
    private final String mApiBase;

    TerminowDriveProvider(@NonNull TokenSource tokens) {
        this(tokens, "https://www.googleapis.com");
    }

    TerminowDriveProvider(@NonNull TokenSource tokens, @NonNull String apiBase) {
        super(tokens);
        mApiBase = apiBase;
    }

    @NonNull @Override public String id() { return "gdrive"; }
    @NonNull @Override public String displayName() { return "Google Drive"; }

    @Override
    public void upload(@NonNull String key, @NonNull File file, @NonNull Progress p) throws IOException {
        requireOwnedKey(key);
        final String metadata = metadataJson(key);
        final byte[] pre = ("--" + BOUNDARY + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
            + metadata + "\r\n--" + BOUNDARY + "\r\nContent-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
        final byte[] post = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
        final long total = pre.length + file.length() + post.length;
        withRetries("Drive upload " + key, () -> {
            if (p.cancelled()) throw new IOException("Cancelled");
            HttpURLConnection c = open("POST", mApiBase + "/upload/drive/v3/files?uploadType=multipart&fields=id");
            c.setRequestProperty("Content-Type", "multipart/related; boundary=" + BOUNDARY);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(total);
            try (InputStream in = new BufferedInputStream(new FileInputStream(file)); OutputStream out = c.getOutputStream()) {
                out.write(pre);
                byte[] buf = new byte[1 << 16];
                long sent = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (p.cancelled()) throw new IOException("Cancelled");
                    out.write(buf, 0, n);
                    sent += n;
                    p.onProgress(sent, file.length());
                }
                out.write(post);
            }
            int code = c.getResponseCode();
            String body = readBody(c);
            c.disconnect();
            if (code < 200 || code >= 300) throw httpError("Drive upload", code, body);
            return null;
        });
    }

    @NonNull @Override
    public InputStream open(@NonNull String key) throws IOException {
        requireOwnedKey(key);
        String id = idOf(key);
        if (id == null) throw new IOException("The backup " + key + " was not found in Drive");
        return download("Drive download " + key, "GET", mApiBase + "/drive/v3/files/" + id + "?alt=media", c -> {});
    }

    @Override
    public void delete(@NonNull String key) throws IOException {
        requireOwnedKey(key);
        String id = idOf(key);
        if (id == null) return;   // already gone
        requestJson("Drive delete " + key, "DELETE", mApiBase + "/drive/v3/files/" + id, null, null);
    }

    @NonNull @Override
    public List<Remote> list() throws IOException {
        List<Remote> all = new ArrayList<>();
        String pageToken = null;
        do {
            String url = mApiBase + "/drive/v3/files?spaces=appDataFolder&pageSize=1000"
                + "&fields=" + encode("nextPageToken,files(id,name,size,modifiedTime)")
                + "&q=" + encode("name contains 'terminow-backups'")
                + (pageToken != null ? "&pageToken=" + encode(pageToken) : "");
            String response = requestJson("Drive list", "GET", url, null, null);
            try {
                JSONObject o = new JSONObject(response);
                JSONArray files = o.optJSONArray("files");
                if (files != null) for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.optJSONObject(i);
                    if (f == null) continue;
                    String name = f.optString("name", "");
                    if (!name.startsWith(PREFIX)) continue;
                    long size = parseLong(f.optString("size", "0"));
                    all.add(new Remote(name, size, parseIso(f.optString("modifiedTime", null))));
                }
                pageToken = o.optString("nextPageToken", null);
                if (pageToken != null && pageToken.isEmpty()) pageToken = null;
            } catch (JSONException e) {
                throw new IOException("Drive returned a listing TermiNow could not read", e);
            }
        } while (pageToken != null);
        return all;
    }

    /** Resolve a backup's Drive file id from its name (appDataFolder is flat, names are unique). */
    private String idOf(@NonNull String key) throws IOException {
        String url = mApiBase + "/drive/v3/files?spaces=appDataFolder&fields=" + encode("files(id)")
            + "&q=" + encode("name = '" + key.replace("'", "\\'") + "'");
        String response = requestJson("Drive lookup " + key, "GET", url, null, null);
        try {
            JSONArray files = new JSONObject(response).optJSONArray("files");
            if (files != null && files.length() > 0) return files.getJSONObject(0).optString("id", null);
        } catch (JSONException e) {
            throw new IOException("Drive returned a lookup TermiNow could not read", e);
        }
        return null;
    }

    @NonNull
    private static String metadataJson(@NonNull String key) {
        try {
            return new JSONObject().put("name", key).put("parents", new JSONArray().put("appDataFolder")).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @NonNull
    private static String encode(@NonNull String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long parseLong(@NonNull String s) {
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
