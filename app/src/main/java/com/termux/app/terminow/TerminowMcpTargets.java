package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The environments that Claude Code can be configured in, and how their paths map to the files
 * of the app. TermiNow itself and each installed proot-distro container have their own home
 * directory and Claude Code configuration, so configuring one never touches another.
 *
 * Containers are only discovered, never installed. Paths inside a container are resolved
 * against its rootfs and must stay inside it.
 */
public class TerminowMcpTargets {

    public static final String TARGET_TERMINOW = "terminow";

    /** The container names that proot-distro accepts. */
    private static final Pattern CONTAINER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final Pattern USER_NAME_PATTERN = Pattern.compile("[a-z_][a-z0-9_-]*[$]?");

    public static class Container {
        public final String name;
        public final File rootfs;
        /** Like "Ubuntu 24.04.1 LTS", or the name if not known. */
        public final String label;
        public final boolean isUbuntu;

        Container(@NonNull String name, @NonNull File rootfs, @NonNull String label, boolean isUbuntu) {
            this.name = name;
            this.rootfs = rootfs;
            this.label = label;
            this.isUbuntu = isUbuntu;
        }
    }

    private final File mPrefixDir;
    private final File mHomeDir;

    public TerminowMcpTargets(@NonNull File prefixDir, @NonNull File homeDir) {
        mPrefixDir = prefixDir;
        mHomeDir = homeDir;
    }

    @NonNull
    public File getPrefixDir() {
        return mPrefixDir;
    }

    @NonNull
    public File getTerminowHome() {
        return mHomeDir;
    }

    public static boolean isValidContainerName(@Nullable String name) {
        return name != null && name.length() <= 64 && CONTAINER_NAME_PATTERN.matcher(name).matches();
    }

    public static boolean isValidUserName(@Nullable String name) {
        return name != null && name.length() <= 32 && USER_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * The installed containers of proot-distro, from its current layout
     * (`containers/<name>/rootfs`) and its legacy one (`installed-rootfs/<name>`).
     */
    @NonNull
    public List<Container> getContainers() {
        Map<String, Container> containers = new LinkedHashMap<>();
        File runtime = new File(mPrefixDir, "var/lib/proot-distro");
        File[] current = new File(runtime, "containers").listFiles();
        if (current != null) {
            for (File dir : current)
                addContainer(containers, dir.getName(), new File(dir, "rootfs"));
        }
        File[] legacy = new File(runtime, "installed-rootfs").listFiles();
        if (legacy != null) {
            for (File dir : legacy)
                if (!containers.containsKey(dir.getName())) addContainer(containers, dir.getName(), dir);
        }
        List<Container> list = new ArrayList<>(containers.values());
        // Ubuntu first, since it is what most people install
        list.sort((a, b) -> a.isUbuntu != b.isUbuntu ? (a.isUbuntu ? -1 : 1) : a.name.compareTo(b.name));
        return list;
    }

    private static void addContainer(@NonNull Map<String, Container> containers, @NonNull String name, @NonNull File rootfs) {
        if (!isValidContainerName(name) || !rootfs.isDirectory()) return;
        // A container of the termux type has no /etc/os-release and no Claude Code of its own
        Map<String, String> osRelease = readKeyValues(new File(rootfs, "etc/os-release"));
        if (osRelease.isEmpty()) osRelease = readKeyValues(new File(rootfs, "usr/lib/os-release"));
        if (osRelease.isEmpty()) return;
        String id = osRelease.getOrDefault("ID", "");
        String idLike = osRelease.getOrDefault("ID_LIKE", "");
        String label = osRelease.getOrDefault("PRETTY_NAME", osRelease.getOrDefault("NAME", name));
        containers.put(name, new Container(name, rootfs, label, id.equals("ubuntu") || idLike.contains("ubuntu")));
    }

    @Nullable
    public Container getContainer(@NonNull String name) {
        for (Container container : getContainers())
            if (container.name.equals(name)) return container;
        return null;
    }

    /** The users of a container that can log in, root and the users with a uid of 1000 or higher. */
    @NonNull
    public static List<String> getUsers(@NonNull Container container) {
        List<String> users = new ArrayList<>();
        users.add("root");
        for (String[] entry : readPasswd(container)) {
            try {
                int uid = Integer.parseInt(entry[2]);
                if (uid >= 1000 && uid < 60000 && isValidUserName(entry[0]) && !users.contains(entry[0]))
                    users.add(entry[0]);
            } catch (NumberFormatException e) {
                // Skip the entry
            }
        }
        return users;
    }

    /** The home directory of a user of a container, as a path inside the container. */
    @NonNull
    public static String getGuestHome(@NonNull Container container, @NonNull String user) {
        for (String[] entry : readPasswd(container))
            if (entry[0].equals(user) && entry[5].startsWith("/")) return entry[5];
        return user.equals("root") ? "/root" : "/home/" + user;
    }

    @NonNull
    private static List<String[]> readPasswd(@NonNull Container container) {
        List<String[]> entries = new ArrayList<>();
        for (String line : readLines(new File(container.rootfs, "etc/passwd"))) {
            String[] fields = line.split(":", -1);
            if (fields.length >= 7) entries.add(fields);
        }
        return entries;
    }

    /**
     * Resolve a path inside a container to the file of the app. Returns {@code null} if the path
     * is not absolute, or leaves the rootfs, for example with `..` or a symlink.
     */
    @Nullable
    public static File resolveGuestPath(@NonNull Container container, @NonNull String guestPath) {
        if (!guestPath.startsWith("/") || guestPath.contains("\0")) return null;
        for (String part : guestPath.split("/"))
            if (part.equals("..")) return null;
        File file = new File(container.rootfs, guestPath.substring(1));
        try {
            String root = container.rootfs.getCanonicalPath();
            String path = canonicalOfExisting(file);
            if (!path.equals(root) && !path.startsWith(root + "/")) return null;
        } catch (IOException e) {
            return null;
        }
        return file;
    }

    /**
     * Resolve a path of the TermiNow environment. It must be absolute and, since only those
     * files can be written, inside the data directory of the app.
     */
    @Nullable
    public File resolveTerminowPath(@NonNull String path) {
        if (!path.startsWith("/") || path.contains("\0")) return null;
        for (String part : path.split("/"))
            if (part.equals("..")) return null;
        File file = new File(path);
        File dataDir = mPrefixDir.getParentFile();
        if (dataDir == null) return null;
        try {
            String root = dataDir.getCanonicalPath();
            String canonical = canonicalOfExisting(file);
            if (!canonical.startsWith(root + "/")) return null;
        } catch (IOException e) {
            return null;
        }
        return file;
    }

    /** The canonical path of the nearest existing parent, with the rest of the path appended. */
    @NonNull
    private static String canonicalOfExisting(@NonNull File file) throws IOException {
        File existing = file;
        StringBuilder rest = new StringBuilder();
        while (existing != null && !existing.exists()) {
            rest.insert(0, "/" + existing.getName());
            existing = existing.getParentFile();
        }
        if (existing == null) throw new IOException("No existing parent of " + file);
        return existing.getCanonicalPath() + rest;
    }

    @NonNull
    static Map<String, String> readKeyValues(@NonNull File file) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : readLines(file)) {
            int equals = line.indexOf('=');
            if (equals <= 0 || line.startsWith("#")) continue;
            String value = line.substring(equals + 1).trim();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'")))
                value = value.substring(1, value.length() - 1);
            values.put(line.substring(0, equals).trim().toUpperCase(Locale.ROOT), value);
        }
        return values;
    }

    @NonNull
    private static List<String> readLines(@NonNull File file) {
        List<String> lines = new ArrayList<>();
        // Small text files only, these are only read to show and pick from
        if (!file.isFile() || file.length() > 1024 * 1024) return lines;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        } catch (IOException e) {
            // An empty list
        }
        return lines;
    }

}
