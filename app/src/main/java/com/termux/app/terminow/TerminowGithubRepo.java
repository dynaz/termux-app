package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a <em>public</em> GitHub repository for the "From GitHub" install flow. The URL is first
 * normalized and validated to an owner/repo (with an optional ref); then the repository metadata, the
 * README and a few manifest files are fetched from the public GitHub API, each capped in size and the
 * whole pinned to the commit the ref resolves to, so the same revision is analysed and recorded.
 *
 * <p>No token or credential is ever sent — only public repositories are supported — and nothing from
 * the repository is executed. The returned text is untrusted input for the analyser and the UI: callers
 * must treat it as data, never as instructions. Network only; no Android dependencies, so it is unit
 * testable and the parsing is pure.</p>
 */
public final class TerminowGithubRepo {

    /** Per-file cap when reading a manifest, and a smaller cap for the README. */
    public static final int MAX_FILE_BYTES = 64 * 1024;
    public static final int MAX_README_BYTES = 32 * 1024;
    /** At most this many manifest files are read, to keep the number of API calls small. */
    public static final int MAX_MANIFESTS = 6;

    static final String API = "https://api.github.com";
    /** Manifests worth reading, in priority order; the first {@link #MAX_MANIFESTS} that exist are kept. */
    static final String[] MANIFESTS = {
        "package.json", "pyproject.toml", "requirements.txt", "setup.py", "Cargo.toml",
        "go.mod", "Gemfile", "composer.json", "Dockerfile", "Makefile", "install.sh"
    };

    private TerminowGithubRepo() {}

    /** A parsed GitHub reference: the owner, the repository, and an optional branch/tag/commit. */
    public static final class Parsed {
        public final String owner;
        public final String repo;
        @Nullable public final String ref;

        Parsed(@NonNull String owner, @NonNull String repo, @Nullable String ref) {
            this.owner = owner;
            this.repo = repo;
            this.ref = ref;
        }

        @NonNull public String slug() { return owner + "/" + repo; }
    }

    /** One file read from the repository, its text capped and flagged if it was truncated. */
    public static final class Blob {
        public final String path;
        public final String text;
        public final boolean truncated;

        Blob(@NonNull String path, @NonNull String text, boolean truncated) {
            this.path = path;
            this.text = text;
            this.truncated = truncated;
        }
    }

    /** What was read from the repository, pinned to {@link #sha}. */
    public static final class Repo {
        public final String owner;
        public final String repo;
        public final String ref;
        public final String sha;
        public final String description;
        public final String htmlUrl;
        public final String readme;
        public final boolean readmeTruncated;
        public final List<Blob> manifests;
        @Nullable public final String releaseTag;
        public final List<String> releaseAssets;

        Repo(@NonNull String owner, @NonNull String repo, @NonNull String ref, @NonNull String sha,
             @NonNull String description, @NonNull String htmlUrl, @NonNull String readme, boolean readmeTruncated,
             @NonNull List<Blob> manifests, @Nullable String releaseTag, @NonNull List<String> releaseAssets) {
            this.owner = owner;
            this.repo = repo;
            this.ref = ref;
            this.sha = sha;
            this.description = description;
            this.htmlUrl = htmlUrl;
            this.readme = readme;
            this.readmeTruncated = readmeTruncated;
            this.manifests = manifests;
            this.releaseTag = releaseTag;
            this.releaseAssets = releaseAssets;
        }

        /** A short, traceable reference like {@code owner/repo@abc1234}. */
        @NonNull public String revision() { return owner + "/" + repo + "@" + (sha.length() > 7 ? sha.substring(0, 7) : sha); }
    }

    public static final class RepoException extends IOException {
        public RepoException(@NonNull String message) { super(message); }
    }

    private static final Pattern OWNER_REPO = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,99})$");

    /**
     * Normalize a GitHub URL or {@code owner/repo} to a {@link Parsed}. Accepts https, the {@code .git}
     * suffix, {@code git@github.com:owner/repo}, and a {@code /tree/<ref>} path. Returns {@code null}
     * when it is not a GitHub repository reference, so the caller can reject it before any network call.
     */
    @Nullable
    public static Parsed parse(@Nullable String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        String ref = null;
        String path;
        // git@github.com:owner/repo(.git)
        if (s.startsWith("git@github.com:")) {
            path = s.substring("git@github.com:".length());
        } else if (s.contains("github.com")) {
            // strip scheme and host, keep the path after github.com
            int at = s.indexOf("github.com");
            path = s.substring(at + "github.com".length());
            if (path.startsWith("/") || path.startsWith(":")) path = path.substring(1);
        } else if (!s.contains("://") && !s.contains(" ") && s.chars().filter(c -> c == '/').count() == 1) {
            // a bare owner/repo
            path = s;
        } else {
            return null;
        }
        // drop query/fragment
        int q = path.indexOf('?'); if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#'); if (h >= 0) path = path.substring(0, h);
        String[] parts = path.split("/");
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null;
        String owner = parts[0];
        String repo = parts[1];
        if (repo.toLowerCase(Locale.ROOT).endsWith(".git")) repo = repo.substring(0, repo.length() - 4);
        // owner/repo/tree/<ref...> or /commit/<sha>
        if (parts.length >= 4 && ("tree".equals(parts[2]) || "commit".equals(parts[2]))) {
            StringBuilder r = new StringBuilder();
            for (int i = 3; i < parts.length; i++) { if (r.length() > 0) r.append('/'); r.append(parts[i]); }
            if (r.length() > 0) ref = r.toString();
        }
        if (!OWNER_REPO.matcher(owner).matches() || !OWNER_REPO.matcher(repo).matches()) return null;
        return new Parsed(owner, repo, ref);
    }

    /** Fetch the repository at its ref (or default branch), pinned to the resolved commit. Blocks. */
    @NonNull
    public static Repo fetch(@NonNull Parsed p) throws IOException {
        JSONObject meta = getJson(API + "/repos/" + p.owner + "/" + p.repo);
        String defaultBranch = meta.optString("default_branch", "main");
        String description = meta.optString("description", "");
        String htmlUrl = meta.optString("html_url", "https://github.com/" + p.slug());
        if (meta.optBoolean("private", false)) throw new RepoException("Only public repositories are supported");
        String ref = p.ref != null ? p.ref : defaultBranch;

        // Resolve the ref to a commit SHA so the analysis is pinned to one revision.
        String sha;
        try {
            JSONObject commit = getJson(API + "/repos/" + p.owner + "/" + p.repo + "/commits/" + enc(ref));
            sha = commit.optString("sha", ref);
        } catch (IOException e) {
            sha = ref;
        }

        String readme = "";
        boolean readmeTruncated = false;
        try {
            JSONObject rd = getJson(API + "/repos/" + p.owner + "/" + p.repo + "/readme?ref=" + enc(sha));
            String[] out = decodeContent(rd, MAX_README_BYTES);
            readme = out[0];
            readmeTruncated = "1".equals(out[1]);
        } catch (IOException ignored) {
            // No README is not fatal
        }

        List<Blob> manifests = new ArrayList<>();
        for (String name : MANIFESTS) {
            if (manifests.size() >= MAX_MANIFESTS) break;
            try {
                JSONObject file = getJson(API + "/repos/" + p.owner + "/" + p.repo + "/contents/" + enc(name) + "?ref=" + enc(sha));
                if (!"file".equals(file.optString("type", ""))) continue;
                String[] out = decodeContent(file, MAX_FILE_BYTES);
                manifests.add(new Blob(name, out[0], "1".equals(out[1])));
            } catch (IOException ignored) {
                // Missing manifest: skip
            }
        }

        String releaseTag = null;
        List<String> assets = new ArrayList<>();
        try {
            JSONObject rel = getJson(API + "/repos/" + p.owner + "/" + p.repo + "/releases/latest");
            releaseTag = rel.optString("tag_name", null);
            JSONArray arr = rel.optJSONArray("assets");
            if (arr != null) for (int i = 0; i < arr.length() && i < 20; i++) {
                JSONObject a = arr.optJSONObject(i);
                if (a != null) assets.add(a.optString("name", ""));
            }
        } catch (IOException ignored) {
            // No releases
        }

        return new Repo(p.owner, p.repo, ref, sha, description, htmlUrl, readme, readmeTruncated, manifests, releaseTag, assets);
    }

    // --- HTTP (public, no token, https only, timeouts, capped reads) ---

    @NonNull
    static JSONObject getJson(@NonNull String url) throws IOException {
        try {
            return new JSONObject(get(url, 512 * 1024));
        } catch (org.json.JSONException e) {
            throw new RepoException("GitHub returned an unexpected response");
        }
    }

    @NonNull
    static String get(@NonNull String urlStr, int maxBytes) throws IOException {
        URL url = new URL(urlStr);
        if (!"https".equals(url.getProtocol()) || !"api.github.com".equalsIgnoreCase(url.getHost()))
            throw new RepoException("Not a GitHub API request");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "TermiNow");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            int status = c.getResponseCode();
            if (status == 404) throw new RepoException("Repository or file not found");
            if (status == 403 && "0".equals(c.getHeaderField("X-RateLimit-Remaining")))
                throw new RepoException("GitHub rate limit reached; try again later");
            if (status != 200) throw new RepoException("GitHub request failed (HTTP " + status + ")");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    if (out.size() > maxBytes) throw new RepoException("GitHub response is too large");
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** Decode a GitHub "contents" object's base64 body to text, capped; returns {text, "1"/"0" truncated}. */
    @NonNull
    static String[] decodeContent(@NonNull JSONObject file, int maxBytes) throws RepoException {
        if (!"base64".equals(file.optString("encoding", ""))) {
            String raw = file.optString("content", "");
            return cap(raw, maxBytes);
        }
        String b64 = file.optString("content", "").replace("\n", "");
        byte[] bytes;
        try {
            bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new RepoException("A file could not be decoded");
        }
        return cap(new String(bytes, StandardCharsets.UTF_8), maxBytes);
    }

    @NonNull
    private static String[] cap(@NonNull String text, int maxBytes) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return new String[]{text, "0"};
        return new String[]{new String(b, 0, maxBytes, StandardCharsets.UTF_8), "1"};
    }

    @NonNull
    static String enc(@NonNull String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}
