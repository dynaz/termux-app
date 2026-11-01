package com.termux.app.terminow;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Microsoft OneDrive backup provider (Microsoft Graph), storing backups in the app's own folder
 * ({@code special/approot}), so it never touches the user's other files. A key is a path under the
 * app folder; listing walks the folders under {@code terminow-backups} recursively. Simple upload
 * (PUT …/content) is used; large-file upload sessions are a documented follow-up. Needs a Microsoft
 * OAuth access token with Files.ReadWrite.AppFolder (see the report); verified against a fake server,
 * not live.
 */
final class TerminowOneDriveProvider extends TerminowHttpProvider {

    private final String mBase;

    TerminowOneDriveProvider(@NonNull TokenSource tokens) {
        this(tokens, "https://graph.microsoft.com/v1.0");
    }

    TerminowOneDriveProvider(@NonNull TokenSource tokens, @NonNull String base) {
        super(tokens);
        mBase = base;
    }

    @NonNull @Override public String id() { return "onedrive"; }
    @NonNull @Override public String displayName() { return "OneDrive"; }

    @Override
    public void upload(@NonNull String key, @NonNull File file, @NonNull Progress p) throws IOException {
        requireOwnedKey(key);
        String url = mBase + "/me/drive/special/approot:/" + encodePath(key) + ":/content";
        uploadFile("OneDrive upload " + key, "PUT", url,
            c -> c.setRequestProperty("Content-Type", "application/octet-stream"), file, p);
    }

    @NonNull @Override
    public InputStream open(@NonNull String key) throws IOException {
        requireOwnedKey(key);
        String url = mBase + "/me/drive/special/approot:/" + encodePath(key) + ":/content";
        return download("OneDrive download " + key, "GET", url, c -> {});
    }

    @Override
    public void delete(@NonNull String key) throws IOException {
        requireOwnedKey(key);
        try {
            requestJson("OneDrive delete " + key, "DELETE", mBase + "/me/drive/special/approot:/" + encodePath(key), null, null);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) return;   // already gone
            throw e;
        }
    }

    @NonNull @Override
    public List<Remote> list() throws IOException {
        List<Remote> all = new ArrayList<>();
        String root = PREFIX.endsWith("/") ? PREFIX.substring(0, PREFIX.length() - 1) : PREFIX;
        Deque<String> folders = new ArrayDeque<>();
        folders.add(root);
        while (!folders.isEmpty()) {
            String rel = folders.removeFirst();
            String url = mBase + "/me/drive/special/approot:/" + encodePath(rel) + ":/children?$select=name,size,lastModifiedDateTime,folder,file";
            while (url != null) {
                String response;
                try {
                    response = requestJson("OneDrive list", "GET", url, null, null);
                } catch (IOException e) {
                    if (e.getMessage() != null && e.getMessage().contains("404")) { url = null; break; }   // folder absent
                    throw e;
                }
                try {
                    JSONObject o = new JSONObject(response);
                    JSONArray value = o.optJSONArray("value");
                    if (value != null) for (int i = 0; i < value.length(); i++) {
                        JSONObject item = value.optJSONObject(i);
                        if (item == null) continue;
                        String name = item.optString("name", "");
                        String childRel = rel + "/" + name;
                        if (item.has("folder")) {
                            folders.add(childRel);
                        } else if (item.has("file") || item.has("size")) {
                            if (childRel.startsWith(PREFIX))
                                all.add(new Remote(childRel, item.optLong("size"), parseIso(item.optString("lastModifiedDateTime", null))));
                        }
                    }
                    url = o.optString("@odata.nextLink", null);
                    if (url != null && url.isEmpty()) url = null;
                } catch (JSONException e) {
                    throw new IOException("OneDrive returned a listing TermiNow could not read", e);
                }
            }
        }
        return all;
    }

    /** URL-encode each path segment but keep the slashes (RFC 3986). */
    @NonNull
    private static String encodePath(@NonNull String path) {
        return TerminowAwsSigV4.uriEncode(path, false);
    }
}
