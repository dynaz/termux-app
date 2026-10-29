package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the TermiNow DevOps backend ({@code cp.accmove.com/terminow/devops.php}). GitHub OAuth,
 * repository/Actions reads and token storage all happen on the server, which verifies the Firebase ID
 * token and Premium entitlement on every call — this client only carries the signed-in user's ID token
 * (supplied through {@link TerminowAiService.Tokens}) and parses the JSON. It never sees a provider token.
 *
 * <p>Each call runs the network off the main thread (callers use a background thread). A non-200 response
 * becomes a {@link DevOpsException} carrying the server's error code ("premium_required", "not_connected",
 * "no_access", "bad_state", ...), so the UI can show the upgrade flow or an accurate message.</p>
 */
public final class TerminowDevOps {

    public static final String ENDPOINT = "https://cp.accmove.com/terminow/devops.php";

    private final TerminowAiService.Tokens mTokens;

    public TerminowDevOps(@NonNull TerminowAiService.Tokens tokens) {
        mTokens = tokens;
    }

    /** A backend error with the server's machine-readable code (not just a message). */
    public static final class DevOpsException extends IOException {
        public final int httpCode;
        @NonNull public final String code;
        DevOpsException(int httpCode, @NonNull String code, @NonNull String message) {
            super(message);
            this.httpCode = httpCode;
            this.code = code;
        }
        /** Whether this means the user needs Premium (or it lapsed) — the UI shows the upgrade flow. */
        public boolean isPremiumRequired() { return "premium_required".equals(code); }
        public boolean isNotConnected() { return "not_connected".equals(code); }
    }

    /** One provider's state on the home screen. */
    public static final class Provider {
        public String id = "";            // "github" | "gitlab" | "bitbucket"
        public boolean configured;        // the backend has this provider's OAuth app configured
        public boolean connected;
        public String login = "";
    }

    public static final class Status {
        public boolean connected;         // for the queried provider (back-compat)
        public String login = "";
        public final List<Provider> providers = new ArrayList<>();
    }

    public static final class Repo {
        public String provider = "github";
        public String id = "";            // the provider repo reference for API calls (owner/name or a numeric id)
        public String fullName = "", defaultBranch = "", pushedAt = "", htmlUrl = "";
        public boolean isPrivate;
    }

    public static final class Run {
        public String id = "";            // numeric (GitHub/GitLab) or a Bitbucket uuid
        public String name = "", status = "", conclusion = "", branch = "", createdAt = "", htmlUrl = "";
    }

    public static final class Step {
        public String name = "", status = "", conclusion = "";
    }

    public static final class Job {
        public String name = "", status = "", conclusion = "", htmlUrl = "";
        public final List<Step> steps = new ArrayList<>();
    }

    // ---- Actions (call off the main thread) -----------------------------------------------------

    @NonNull
    public Status status(@NonNull String provider) throws IOException {
        JSONObject o = post(req("status", provider));
        Status s = new Status();
        s.connected = o.optBoolean("connected");
        s.login = o.optString("login");
        JSONObject provs = o.optJSONObject("providers");
        if (provs != null) {
            java.util.Iterator<String> it = provs.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject p = provs.optJSONObject(id);
                if (p == null) continue;
                Provider pr = new Provider();
                pr.id = id;
                pr.configured = p.optBoolean("configured");
                pr.connected = p.optBoolean("connected");
                pr.login = p.optString("login");
                s.providers.add(pr);
            }
        }
        return s;
    }

    /** The provider authorize URL to open in a browser; the server created a single-use state. */
    @NonNull
    public String connectUrl(@NonNull String provider) throws IOException {
        String url = post(req("connect", provider)).optString("authorizeUrl");
        if (url.isEmpty()) throw new DevOpsException(502, "provider_error", "No authorize URL");
        return url;
    }

    @NonNull
    public List<Repo> repos(@NonNull String provider) throws IOException {
        JSONArray array = post(req("repos", provider)).optJSONArray("repos");
        List<Repo> list = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject r = array.optJSONObject(i);
            if (r == null) continue;
            Repo repo = new Repo();
            repo.provider = r.optString("provider", provider);
            repo.id = r.optString("id");
            repo.fullName = r.optString("fullName");
            repo.isPrivate = r.optBoolean("private");
            repo.defaultBranch = r.optString("defaultBranch");
            repo.pushedAt = r.optString("pushedAt");
            repo.htmlUrl = r.optString("htmlUrl");
            if (repo.id.isEmpty()) repo.id = repo.fullName;   // GitHub/Bitbucket use owner/name as the ref
            if (!repo.fullName.isEmpty()) list.add(repo);
        }
        return list;
    }

    @NonNull
    public List<Run> runs(@NonNull String provider, @NonNull String repoId) throws IOException {
        JSONObject body = req("runs", provider);
        putOpt(body, "repo", repoId);
        JSONArray array = post(body).optJSONArray("runs");
        List<Run> list = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject r = array.optJSONObject(i);
            if (r == null) continue;
            Run run = new Run();
            run.id = r.optString("id");
            run.name = r.optString("name");
            run.status = r.optString("status");
            run.conclusion = r.optString("conclusion");
            run.branch = r.optString("branch");
            run.createdAt = r.optString("createdAt");
            run.htmlUrl = r.optString("htmlUrl");
            list.add(run);
        }
        return list;
    }

    @NonNull
    public List<Job> logs(@NonNull String provider, @NonNull String repoId, @NonNull String run) throws IOException {
        JSONObject body = req("logs", provider);
        putOpt(body, "repo", repoId);
        putOpt(body, "run", run);
        JSONArray array = post(body).optJSONArray("jobs");
        List<Job> list = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject j = array.optJSONObject(i);
            if (j == null) continue;
            Job job = new Job();
            job.name = j.optString("name");
            job.status = j.optString("status");
            job.conclusion = j.optString("conclusion");
            job.htmlUrl = j.optString("htmlUrl");
            JSONArray steps = j.optJSONArray("steps");
            if (steps != null) for (int k = 0; k < steps.length(); k++) {
                JSONObject s = steps.optJSONObject(k);
                if (s == null) continue;
                Step step = new Step();
                step.name = s.optString("name");
                step.status = s.optString("status");
                step.conclusion = s.optString("conclusion");
                job.steps.add(step);
            }
            list.add(job);
        }
        return list;
    }

    public void disconnect(@NonNull String provider) throws IOException { post(req("disconnect", provider)); }

    public void revoke(@NonNull String provider) throws IOException { post(req("revoke", provider)); }

    // ---- HTTP -----------------------------------------------------------------------------------

    @NonNull
    private static JSONObject req(@NonNull String action, @NonNull String provider) {
        JSONObject o = new JSONObject();
        try { o.put("action", action); o.put("provider", provider); } catch (JSONException ignored) {}
        return o;
    }

    private static void putOpt(@NonNull JSONObject o, @NonNull String key, @Nullable Object value) {
        try { o.putOpt(key, value); } catch (JSONException ignored) {}
    }

    @NonNull
    private JSONObject post(@NonNull JSONObject request) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + mTokens.idToken());
        TerminowCloudHttp.Response response = TerminowCloudHttp.request("POST", ENDPOINT, headers, request.toString());
        JSONObject body;
        try {
            body = new JSONObject(response.body == null || response.body.isEmpty() ? "{}" : response.body);
        } catch (JSONException e) {
            throw new DevOpsException(response.code, "bad_response", "Unexpected response");
        }
        if (response.code != 200) {
            String code = body.optString("error", "error");
            throw new DevOpsException(response.code, code, code);
        }
        return body;
    }
}
