package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A saved MCP server. Its secrets, the bearer token and the values of the custom headers, are
 * never part of {@link #toJson()}: {@link TerminowStore} keeps them encrypted with a key of the
 * Android keystore.
 *
 * Only the Streamable HTTP transport is offered. The fields for a local stdio server are kept
 * for a later release, and a profile with that transport is shown as not supported.
 */
public class TerminowMcpProfile {

    public static final String TRANSPORT_HTTP = "http";
    public static final String TRANSPORT_STDIO = "stdio";

    public static final String AUTH_NONE = "none";
    public static final String AUTH_BEARER = "bearer";
    public static final String AUTH_HEADERS = "headers";

    /** All projects of the user, `~/.claude.json`. */
    public static final String SCOPE_USER = "user";
    /** One project, only for the user, `~/.claude.json` under the project. */
    public static final String SCOPE_LOCAL = "local";
    /** One project, shared with everyone who has it, `.mcp.json` in the project. */
    public static final String SCOPE_PROJECT = "project";

    /** The server names that Claude Code accepts. */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** HTTP header names, RFC 9110 tokens. */
    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}");

    /** Headers that the MCP client sets itself. */
    private static final String[] RESERVED_HEADERS = {"accept", "content-type", "content-length", "host", "connection",
        "transfer-encoding", "mcp-session-id", "mcp-protocol-version", "mcp-method", "mcp-name", "last-event-id", "user-agent"};

    public static class Header {
        public String name = "";
        /** Secret, only in memory and in the encrypted store. */
        public String value = "";

        public Header() {}

        public Header(@NonNull String name, @NonNull String value) {
            this.name = name;
            this.value = value;
        }
    }

    /** What happened when Claude Code was last configured with the profile in an environment. */
    public static class Applied {
        public String target = "";
        public String scope = "";
        public String projectDir = "";
        /** The file that was written, as a path in the environment. */
        public String destination = "";
        /** The fingerprint of the entry that was written, to find changes made by others. */
        public String fingerprint = "";
        public long time;
        public String claudeVersion = "";
        /** Whether `claude mcp get` found the server afterwards. */
        public boolean verified;
        public String verifyOutput = "";
        /** Whether the entry is in the configuration, or was removed by disabling it. */
        public boolean enabled;
        /** Whether the configuration has a secret in it. */
        public boolean storesSecret;
    }

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String transport = TRANSPORT_HTTP;
    public String url = "";
    public String authType = AUTH_NONE;
    /** Secret. */
    public String bearerToken = "";
    public final List<Header> headers = new ArrayList<>();
    /** {@link TerminowMcpTargets#TARGET_TERMINOW} or the name of a proot-distro container. */
    public String target = TerminowMcpTargets.TARGET_TERMINOW;
    public String containerUser = "root";
    public String scope = SCOPE_USER;
    public String projectDir = "";

    // A local stdio server, not offered yet
    public String command = "";
    public final List<String> args = new ArrayList<>();
    public final Map<String, String> env = new LinkedHashMap<>();
    public String workingDir = "";

    // The last connection test from the app
    public long testTime;
    public boolean testOk;
    public String testFailure = "";
    public String testMessage = "";
    public String testServer = "";
    public String testProtocol = "";
    public final List<String> testTools = new ArrayList<>();

    /** By target, the configuration of Claude Code. */
    public final Map<String, Applied> applied = new LinkedHashMap<>();

    public static boolean isValidName(@Nullable String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Whether another profile has the name. Claude Code keys servers by name, so names must be
     * unique, ignoring case to avoid two entries that look the same.
     */
    public static boolean isNameTaken(@NonNull List<TerminowMcpProfile> profiles, @NonNull String name, @Nullable String exceptId) {
        for (TerminowMcpProfile profile : profiles)
            if (profile.name.equalsIgnoreCase(name) && !profile.id.equals(exceptId)) return true;
        return false;
    }

    /** Returns {@code null} if the header is valid, or why it is not. */
    @Nullable
    public static String validateHeader(@NonNull String name, @NonNull String value) {
        if (!HEADER_NAME_PATTERN.matcher(name).matches()) return "\"" + name + "\" is not a valid header name";
        for (String reserved : RESERVED_HEADERS)
            if (reserved.equals(name.toLowerCase(Locale.ROOT))) return "The " + name + " header is set by the MCP client";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0 || c > 0x7e || (c < 0x20 && c != '\t'))
                return "The value of " + name + " has characters that are not allowed in a header";
        }
        if (value.trim().isEmpty()) return "Enter a value for " + name;
        return null;
    }

    public boolean isSupported() {
        return TRANSPORT_HTTP.equals(transport);
    }

    /** The headers that are sent to the server, with their secret values. */
    @NonNull
    public Map<String, String> getRequestHeaders() {
        Map<String, String> result = new LinkedHashMap<>();
        if (AUTH_BEARER.equals(authType) && !bearerToken.isEmpty())
            result.put("Authorization", "Bearer " + bearerToken);
        else if (AUTH_HEADERS.equals(authType))
            for (Header header : headers) result.put(header.name, header.value);
        return result;
    }

    /** All secret values, to remove them from text. */
    @NonNull
    public List<String> getSecrets() {
        List<String> secrets = new ArrayList<>();
        if (!bearerToken.isEmpty()) secrets.add(bearerToken);
        for (Header header : headers) if (!header.value.isEmpty()) secrets.add(header.value);
        return secrets;
    }

    public boolean hasSecrets() {
        return !getRequestHeaders().isEmpty();
    }

    @NonNull
    public String getTargetKey() {
        return TerminowMcpTargets.TARGET_TERMINOW.equals(target) ? target : target + "/" + containerUser;
    }

    @Nullable
    public Applied getApplied() {
        return applied.get(getTargetKey());
    }

    /** Without the secrets. */
    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("name", name);
        object.put("transport", transport);
        object.put("url", url);
        object.put("authType", authType);
        JSONArray headerNames = new JSONArray();
        for (Header header : headers) headerNames.put(header.name);
        object.put("headerNames", headerNames);
        object.put("target", target);
        object.put("containerUser", containerUser);
        object.put("scope", scope);
        object.put("projectDir", projectDir);
        object.put("command", command);
        object.put("args", new JSONArray(args));
        object.put("envNames", new JSONArray(new ArrayList<>(env.keySet())));
        object.put("workingDir", workingDir);
        object.put("testTime", testTime);
        object.put("testOk", testOk);
        object.put("testFailure", testFailure);
        object.put("testMessage", testMessage);
        object.put("testServer", testServer);
        object.put("testProtocol", testProtocol);
        object.put("testTools", new JSONArray(testTools));
        JSONObject appliedObject = new JSONObject();
        for (Map.Entry<String, Applied> entry : applied.entrySet()) {
            Applied a = entry.getValue();
            appliedObject.put(entry.getKey(), new JSONObject()
                .put("target", a.target).put("scope", a.scope).put("projectDir", a.projectDir)
                .put("destination", a.destination).put("fingerprint", a.fingerprint).put("time", a.time)
                .put("claudeVersion", a.claudeVersion).put("verified", a.verified).put("verifyOutput", a.verifyOutput)
                .put("enabled", a.enabled).put("storesSecret", a.storesSecret));
        }
        object.put("applied", appliedObject);
        return object;
    }

    /** The secrets are filled in by the store. Header values are empty until then. */
    @NonNull
    public static TerminowMcpProfile fromJson(@NonNull JSONObject object) {
        TerminowMcpProfile profile = new TerminowMcpProfile();
        profile.id = object.optString("id", profile.id);
        profile.name = object.optString("name");
        profile.transport = object.optString("transport", TRANSPORT_HTTP);
        profile.url = object.optString("url");
        profile.authType = object.optString("authType", AUTH_NONE);
        JSONArray headerNames = object.optJSONArray("headerNames");
        if (headerNames != null)
            for (int i = 0; i < headerNames.length(); i++) profile.headers.add(new Header(headerNames.optString(i), ""));
        profile.target = object.optString("target", TerminowMcpTargets.TARGET_TERMINOW);
        profile.containerUser = object.optString("containerUser", "root");
        profile.scope = object.optString("scope", SCOPE_USER);
        profile.projectDir = object.optString("projectDir");
        profile.command = object.optString("command");
        JSONArray args = object.optJSONArray("args");
        if (args != null) for (int i = 0; i < args.length(); i++) profile.args.add(args.optString(i));
        JSONArray envNames = object.optJSONArray("envNames");
        if (envNames != null) for (int i = 0; i < envNames.length(); i++) profile.env.put(envNames.optString(i), "");
        profile.workingDir = object.optString("workingDir");
        profile.testTime = object.optLong("testTime");
        profile.testOk = object.optBoolean("testOk");
        profile.testFailure = object.optString("testFailure");
        profile.testMessage = object.optString("testMessage");
        profile.testServer = object.optString("testServer");
        profile.testProtocol = object.optString("testProtocol");
        JSONArray tools = object.optJSONArray("testTools");
        if (tools != null) for (int i = 0; i < tools.length(); i++) profile.testTools.add(tools.optString(i));
        JSONObject appliedObject = object.optJSONObject("applied");
        if (appliedObject != null) {
            JSONArray keys = appliedObject.names();
            for (int i = 0; keys != null && i < keys.length(); i++) {
                JSONObject a = appliedObject.optJSONObject(keys.optString(i));
                if (a == null) continue;
                Applied applied = new Applied();
                applied.target = a.optString("target");
                applied.scope = a.optString("scope");
                applied.projectDir = a.optString("projectDir");
                applied.destination = a.optString("destination");
                applied.fingerprint = a.optString("fingerprint");
                applied.time = a.optLong("time");
                applied.claudeVersion = a.optString("claudeVersion");
                applied.verified = a.optBoolean("verified");
                applied.verifyOutput = a.optString("verifyOutput");
                applied.enabled = a.optBoolean("enabled");
                applied.storesSecret = a.optBoolean("storesSecret");
                profile.applied.put(keys.optString(i), applied);
            }
        }
        return profile;
    }

    /** Forget the last test, since the server or its credentials changed. */
    public void clearTest() {
        testTime = 0;
        testOk = false;
        testFailure = "";
        testMessage = "";
        testServer = "";
        testProtocol = "";
        testTools.clear();
    }

}
