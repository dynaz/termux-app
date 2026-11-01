package com.termux.app.terminow;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Dropbox backup provider (Dropbox HTTP API v2), confined to the app folder, where a key maps to the
 * path {@code /<key>}. Upload/list/delete/download are real API calls; the content and API hosts are
 * injected so the app uses Dropbox and tests use a local fake server. A simple (single-request)
 * upload is used; chunked upload sessions for very large files are a documented follow-up. Needs a
 * Dropbox app's OAuth token (see the feature report); verified here against a fake server, not live.
 */
final class TerminowDropboxProvider extends TerminowHttpProvider {

    private final String mContentBase;
    private final String mApiBase;

    TerminowDropboxProvider(@NonNull TokenSource tokens) {
        this(tokens, "https://content.dropboxapi.com", "https://api.dropboxapi.com");
    }

    TerminowDropboxProvider(@NonNull TokenSource tokens, @NonNull String contentBase, @NonNull String apiBase) {
        super(tokens);
        mContentBase = contentBase;
        mApiBase = apiBase;
    }

    @NonNull @Override public String id() { return "dropbox"; }
    @NonNull @Override public String displayName() { return "Dropbox"; }

    @Override
    public void upload(@NonNull String key, @NonNull File file, @NonNull Progress p) throws IOException {
        requireOwnedKey(key);
        final String arg = asciiHeader(arg("path", "/" + key, "mode", "overwrite", "mute", Boolean.TRUE));
        uploadFile("Dropbox upload " + key, "POST", mContentBase + "/2/files/upload", c -> {
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setRequestProperty("Dropbox-API-Arg", arg);
        }, file, p);
    }

    @NonNull @Override
    public InputStream open(@NonNull String key) throws IOException {
        requireOwnedKey(key);
        final String arg = asciiHeader(arg("path", "/" + key));
        return download("Dropbox download " + key, "POST", mContentBase + "/2/files/download",
            c -> c.setRequestProperty("Dropbox-API-Arg", arg));
    }

    @Override
    public void delete(@NonNull String key) throws IOException {
        requireOwnedKey(key);
        requestJson("Dropbox delete " + key, "POST", mApiBase + "/2/files/delete_v2",
            "application/json", arg("path", "/" + key));
    }

    @NonNull @Override
    public List<Remote> list() throws IOException {
        List<Remote> all = new ArrayList<>();
        String folder = "/" + PREFIX;
        while (folder.endsWith("/")) folder = folder.substring(0, folder.length() - 1);
        String response;
        try {
            response = requestJson("Dropbox list", "POST", mApiBase + "/2/files/list_folder",
                "application/json", arg("path", folder, "recursive", Boolean.TRUE));
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("not_found")) return all;   // folder not created yet
            throw e;
        }
        parsePage(response, all);
        String cursor = cursorOf(response);
        boolean more = hasMore(response);
        while (more) {
            String page = requestJson("Dropbox list continue", "POST", mApiBase + "/2/files/list_folder/continue",
                "application/json", arg("cursor", cursor));
            parsePage(page, all);
            cursor = cursorOf(page);
            more = hasMore(page);
        }
        return all;
    }

    private void parsePage(@NonNull String json, @NonNull List<Remote> out) throws IOException {
        try {
            JSONArray entries = new JSONObject(json).optJSONArray("entries");
            if (entries == null) return;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.optJSONObject(i);
                if (e == null || !"file".equals(e.optString(".tag"))) continue;
                String path = e.optString("path_display", e.optString("path_lower", ""));
                String key = path.startsWith("/") ? path.substring(1) : path;
                if (!key.startsWith(PREFIX)) continue;
                out.add(new Remote(key, e.optLong("size"), parseIso(e.optString("server_modified", null))));
            }
        } catch (JSONException ex) {
            throw new IOException("Dropbox returned a listing TermiNow could not read", ex);
        }
    }

    private static boolean hasMore(@NonNull String json) {
        try { return new JSONObject(json).optBoolean("has_more", false); } catch (JSONException e) { return false; }
    }

    @NonNull
    private static String cursorOf(@NonNull String json) {
        try { return new JSONObject(json).optString("cursor", ""); } catch (JSONException e) { return ""; }
    }

    /** Build a small JSON object body/arg from alternating key/value pairs. */
    @NonNull
    private static String arg(@NonNull Object... pairs) {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i + 1 < pairs.length; i += 2) o.put((String) pairs[i], pairs[i + 1]);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Dropbox requires the API-Arg header to be ASCII; escape any non-ASCII as \\uXXXX. */
    @NonNull
    private static String asciiHeader(@NonNull String json) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c < 0x80) out.append(c);
            else out.append(String.format("\\u%04x", (int) c));
        }
        return out.toString();
    }
}
