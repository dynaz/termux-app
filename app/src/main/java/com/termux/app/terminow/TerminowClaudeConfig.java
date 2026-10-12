package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Add, update and remove one MCP server entry in a Claude Code configuration file, keeping
 * everything else in the file as it is.
 *
 * The entries are the documented ones: `{"type": "http", "url": ..., "headers": {...}}` under
 * `mcpServers` at the top of `~/.claude.json` (user scope), under `projects[<dir>].mcpServers`
 * of `~/.claude.json` (local scope), or at the top of `<dir>/.mcp.json` (project scope).
 *
 * An entry that exists and was not written by TermiNow, or changed since, is a conflict, which
 * is only replaced or removed when the user confirms it.
 */
public class TerminowClaudeConfig {

    public static final String USER_CONFIG_NAME = ".claude.json";
    public static final String PROJECT_CONFIG_NAME = ".mcp.json";
    private static final int BACKUPS_KEPT = 5;
    private static final long MAX_CONFIG_SIZE = 32 * 1024 * 1024;

    public enum Action { ADD, UPDATE, UNCHANGED, CONFLICT, REMOVE, NOT_PRESENT }

    public static class ConfigException extends Exception {
        public ConfigException(@NonNull String message) {
            super(message);
        }
    }

    public static class Plan {
        public Action action;
        /** The text of the file after the change, {@code null} if nothing is written. */
        @Nullable public String newText;
        /** The entry that is there now, {@code null} if none. */
        @Nullable public JSONObject existingEntry;
        /** The other servers in the same place, which are kept. */
        public final List<String> otherServers = new ArrayList<>();
    }

    /** The entry for an HTTP server. Header values are written as they are. */
    @NonNull
    public static JSONObject buildHttpEntry(@NonNull String url, @NonNull Map<String, String> headers) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("type", "http");
        entry.put("url", url);
        if (!headers.isEmpty()) {
            JSONObject headersObject = new JSONObject();
            for (Map.Entry<String, String> header : headers.entrySet())
                headersObject.put(header.getKey(), header.getValue());
            entry.put("headers", headersObject);
        }
        return entry;
    }

    /** A fingerprint of an entry that does not depend on the order of its keys. */
    @NonNull
    public static String fingerprint(@NonNull JSONObject entry) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical(entry).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @NonNull
    static String canonical(@Nullable Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = object.keys(); it.hasNext(); ) keys.add(it.next());
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) {
                if (out.length() > 1) out.append(',');
                out.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key)));
            }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(',');
                out.append(canonical(array.opt(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        return String.valueOf(value);
    }

    /**
     * Plan to add or update the entry {@code name}.
     *
     * @param existingText The file now, {@code null} if it does not exist.
     * @param scope One of the scopes of {@link TerminowMcpProfile}.
     * @param projectDir The project, as a path in the environment, for the local scope.
     * @param lastFingerprint The fingerprint of the entry that TermiNow wrote last, if any.
     * @param replaceConflict Whether the user confirmed to replace a conflicting entry.
     */
    @NonNull
    public static Plan planAdd(@Nullable String existingText, @NonNull String scope, @NonNull String projectDir,
                               @NonNull String name, @NonNull JSONObject entry, @Nullable String lastFingerprint,
                               boolean replaceConflict) throws ConfigException {
        JSONObject root = parse(existingText);
        JSONObject servers = getServers(root, scope, projectDir, true);
        Plan plan = new Plan();
        collectOthers(plan, servers, name);
        JSONObject existing = servers.optJSONObject(name);
        plan.existingEntry = existing;
        if (existing == null && servers.has(name))
            throw new ConfigException("The entry " + name + " in the configuration is not an object. Fix the file first");

        if (existing == null) {
            plan.action = Action.ADD;
        } else if (fingerprint(existing).equals(fingerprint(entry))) {
            plan.action = Action.UNCHANGED;
            return plan;
        } else if (lastFingerprint != null && fingerprint(existing).equals(lastFingerprint)) {
            plan.action = Action.UPDATE;
        } else if (replaceConflict) {
            plan.action = Action.UPDATE;
        } else {
            plan.action = Action.CONFLICT;
            return plan;
        }
        try {
            servers.put(name, entry);
        } catch (JSONException e) {
            throw new ConfigException(String.valueOf(e.getMessage()));
        }
        plan.newText = format(root);
        return plan;
    }

    /** Plan to remove the entry {@code name}, the same way as {@link #planAdd}. */
    @NonNull
    public static Plan planRemove(@Nullable String existingText, @NonNull String scope, @NonNull String projectDir,
                                  @NonNull String name, @Nullable String lastFingerprint, boolean removeConflict) throws ConfigException {
        JSONObject root = parse(existingText);
        JSONObject servers = getServers(root, scope, projectDir, false);
        Plan plan = new Plan();
        if (servers == null || !servers.has(name)) {
            plan.action = Action.NOT_PRESENT;
            return plan;
        }
        collectOthers(plan, servers, name);
        JSONObject existing = servers.optJSONObject(name);
        plan.existingEntry = existing;
        boolean ours = existing != null && lastFingerprint != null && fingerprint(existing).equals(lastFingerprint);
        if (!ours && !removeConflict) {
            plan.action = Action.CONFLICT;
            return plan;
        }
        servers.remove(name);
        plan.action = Action.REMOVE;
        plan.newText = format(root);
        return plan;
    }

    private static void collectOthers(@NonNull Plan plan, @Nullable JSONObject servers, @NonNull String name) {
        if (servers == null) return;
        for (Iterator<String> it = servers.keys(); it.hasNext(); ) {
            String key = it.next();
            if (!key.equals(name)) plan.otherServers.add(key);
        }
        Collections.sort(plan.otherServers);
    }

    @NonNull
    private static JSONObject parse(@Nullable String text) throws ConfigException {
        if (text == null || text.trim().isEmpty()) return new JSONObject();
        try {
            Object value = new org.json.JSONTokener(text).nextValue();
            if (!(value instanceof JSONObject))
                throw new ConfigException("The configuration file is not a JSON object. It is left as it is");
            return (JSONObject) value;
        } catch (JSONException e) {
            // Never replace a file that could not be read, it would lose its content
            throw new ConfigException("The configuration file is not valid JSON, so it is left as it is: " + e.getMessage());
        }
    }

    /** The object of servers of the scope, created if {@code create}. */
    @Nullable
    private static JSONObject getServers(@NonNull JSONObject root, @NonNull String scope, @NonNull String projectDir,
                                         boolean create) throws ConfigException {
        try {
            JSONObject parent = root;
            if (TerminowMcpProfile.SCOPE_LOCAL.equals(scope)) {
                if (projectDir.isEmpty()) throw new ConfigException("The local scope needs a project directory");
                JSONObject projects = child(root, "projects", create);
                if (projects == null) return null;
                parent = child(projects, projectDir, create);
                if (parent == null) return null;
            } else if (!TerminowMcpProfile.SCOPE_USER.equals(scope) && !TerminowMcpProfile.SCOPE_PROJECT.equals(scope)) {
                throw new ConfigException("Unknown scope " + scope);
            }
            return child(parent, "mcpServers", create);
        } catch (JSONException e) {
            throw new ConfigException(String.valueOf(e.getMessage()));
        }
    }

    @Nullable
    private static JSONObject child(@NonNull JSONObject parent, @NonNull String key, boolean create) throws JSONException, ConfigException {
        if (parent.has(key) && !parent.isNull(key)) {
            JSONObject child = parent.optJSONObject(key);
            if (child == null) throw new ConfigException("\"" + key + "\" in the configuration is not an object. It is left as it is");
            return child;
        }
        if (!create) return null;
        JSONObject child = new JSONObject();
        parent.put(key, child);
        return child;
    }

    @NonNull
    static String format(@NonNull JSONObject root) throws ConfigException {
        try {
            // Android escapes every "/" as "\/", which is valid but hard to read. Each such "/"
            // has its own backslash before it, so removing that one keeps the same JSON value.
            return root.toString(2).replace("\\/", "/") + "\n";
        } catch (JSONException e) {
            throw new ConfigException(String.valueOf(e.getMessage()));
        }
    }

    /** Read a configuration file, {@code null} if it does not exist. Symlinks are refused. */
    @Nullable
    public static String read(@NonNull File file) throws ConfigException {
        checkNotSymlink(file);
        if (!file.exists()) return null;
        if (!file.isFile()) throw new ConfigException(file.getName() + " is not a regular file");
        if (file.length() > MAX_CONFIG_SIZE) throw new ConfigException(file.getName() + " is too large");
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("Failed to read " + file.getName() + ": " + e.getMessage());
        }
    }

    private static void checkNotSymlink(@NonNull File file) throws ConfigException {
        try {
            File parent = file.getParentFile();
            if (parent == null) throw new ConfigException("Invalid path " + file);
            File resolved = new File(parent.getCanonicalFile(), file.getName());
            if (file.exists() && !resolved.getCanonicalPath().equals(resolved.getAbsolutePath()))
                throw new ConfigException(file.getName() + " is a symlink, which TermiNow does not follow. Edit it yourself");
        } catch (IOException e) {
            throw new ConfigException("Failed to check " + file + ": " + e.getMessage());
        }
    }

    /**
     * Back up the file, if it exists, next to it. Returns the backup, {@code null} if there was
     * nothing to back up. Only the newest backups are kept.
     */
    @Nullable
    public static File backup(@NonNull File file, @NonNull Date time) throws ConfigException {
        if (!file.isFile()) return null;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(time);
        File backup = new File(file.getParentFile(), file.getName() + ".terminow-backup-" + stamp);
        for (int i = 2; backup.exists(); i++)
            backup = new File(file.getParentFile(), file.getName() + ".terminow-backup-" + stamp + "-" + i);
        String text = read(file);
        writeFile(backup, text == null ? "" : text);

        File[] backups = file.getParentFile().listFiles((dir, n) -> n.startsWith(file.getName() + ".terminow-backup-"));
        if (backups != null && backups.length > BACKUPS_KEPT) {
            Arrays.sort(backups, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < backups.length - BACKUPS_KEPT; i++)
                //noinspection ResultOfMethodCallIgnored
                backups[i].delete();
        }
        return backup;
    }

    /**
     * Replace the file with the text atomically: write a file next to it, readable only by the
     * owner, flush it to disk and rename it over the file.
     */
    public static void writeAtomically(@NonNull File file, @NonNull String text) throws ConfigException {
        checkNotSymlink(file);
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new ConfigException("The directory of " + file.getName() + " does not exist");
        File temp = new File(parent, "." + file.getName() + ".terminow-" + System.nanoTime() + ".tmp");
        try {
            writeFile(temp, text);
            if (!temp.renameTo(file)) throw new ConfigException("Failed to replace " + file.getName());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static void writeFile(@NonNull File file, @NonNull String text) throws ConfigException {
        try {
            // Created empty and made private before anything is written to it
            if (!file.exists() && !file.createNewFile()) throw new IOException("Failed to create " + file.getName());
            setOwnerOnly(file);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
        } catch (IOException e) {
            throw new ConfigException("Failed to write " + file.getName() + ": " + e.getMessage());
        }
    }

    /** Make the file readable and writable only by its owner, 0600. */
    static void setOwnerOnly(@NonNull File file) throws IOException {
        boolean ok = file.setReadable(false, false) & file.setWritable(false, false) & file.setExecutable(false, false) &
            file.setReadable(true, true) & file.setWritable(true, true);
        if (!ok) throw new IOException("Failed to set the permissions of " + file.getName());
    }

}
