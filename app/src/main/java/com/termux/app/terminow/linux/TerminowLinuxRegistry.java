package com.termux.app.terminow.linux;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The single source of truth for the installed Linux environments: the proot-distro containers
 * of the TermiNow runtime, found where the runtime keeps them, never reinstalled or changed.
 * Their distribution and version come from their own os-release, read inside the rootfs.
 *
 * The runtime keeps containers in `var/lib/proot-distro/containers/<name>/rootfs`, with the
 * legacy `installed-rootfs/<name>` of older proot-distro versions too. Entries that are links are
 * skipped, like proot-distro itself refuses them.
 */
public class TerminowLinuxRegistry {

    /** The names that proot-distro accepts. */
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    private static final Pattern USER = Pattern.compile("[a-z_][a-z0-9_-]{0,31}[$]?");
    private static final Pattern GUEST_PATH = Pattern.compile("/[^\\x00\\n:]*");

    /** Where the settings of the environments are kept, a JSON object by alias. */
    public interface Settings {
        @NonNull JSONObject load();

        void save(@NonNull JSONObject settings);
    }

    /** Which alias TermiNow is installing right now, {@code null} for none. */
    public interface Installing {
        @Nullable String getInstallingAlias();
    }

    private final File mPrefix;
    private final File mHome;
    private final Settings mSettings;
    private final TerminowLinuxPaths.Links mLinks;
    @Nullable private final Installing mInstalling;

    public TerminowLinuxRegistry(@NonNull File prefix, @NonNull File home, @NonNull Settings settings,
                                 @NonNull TerminowLinuxPaths.Links links, @Nullable Installing installing) {
        mPrefix = prefix;
        mHome = home;
        mSettings = settings;
        mLinks = links;
        mInstalling = installing;
    }

    public static boolean isValidAlias(@Nullable String alias) {
        return alias != null && ALIAS.matcher(alias).matches();
    }

    public static boolean isValidUser(@Nullable String user) {
        return user != null && USER.matcher(user).matches();
    }

    @NonNull
    public File getRuntimeDir() {
        return new File(mPrefix, "var/lib/proot-distro");
    }

    @NonNull
    public File getContainersDir() {
        return new File(getRuntimeDir(), "containers");
    }

    @NonNull
    public File getPrefix() {
        return mPrefix;
    }

    /** All environments, installed ones first, Ubuntu first among them. */
    @NonNull
    public List<TerminowLinuxEnv> list() {
        Map<String, TerminowLinuxEnv> found = new LinkedHashMap<>();
        File[] current = getContainersDir().listFiles();
        if (current != null) {
            for (File dir : current) {
                if (!isValidAlias(dir.getName()) || mLinks.isLink(dir) || !dir.isDirectory()) continue;
                File rootfs = new File(dir, "rootfs");
                if (mLinks.isLink(rootfs)) continue;
                found.put(dir.getName(), read(dir.getName(), dir, rootfs));
            }
        }
        File[] legacy = new File(getRuntimeDir(), "installed-rootfs").listFiles();
        if (legacy != null) {
            for (File rootfs : legacy) {
                if (!isValidAlias(rootfs.getName()) || found.containsKey(rootfs.getName()) || mLinks.isLink(rootfs) || !rootfs.isDirectory())
                    continue;
                found.put(rootfs.getName(), read(rootfs.getName(), rootfs, rootfs));
            }
        }
        List<TerminowLinuxEnv> list = new ArrayList<>(found.values());
        Collections.sort(list, (a, b) -> {
            if (a.isInstalled() != b.isInstalled()) return a.isInstalled() ? -1 : 1;
            boolean ua = a.distroId.equals("ubuntu"), ub = b.distroId.equals("ubuntu");
            if (ua != ub) return ua ? -1 : 1;
            return a.alias.compareTo(b.alias);
        });
        return list;
    }

    /** The installed environments only, which can be opened. */
    @NonNull
    public List<TerminowLinuxEnv> installed() {
        List<TerminowLinuxEnv> list = new ArrayList<>();
        for (TerminowLinuxEnv env : list()) if (env.isInstalled()) list.add(env);
        return list;
    }

    @Nullable
    public TerminowLinuxEnv get(@NonNull String alias) {
        for (TerminowLinuxEnv env : list()) if (env.alias.equals(alias)) return env;
        return null;
    }

    @NonNull
    private TerminowLinuxEnv read(@NonNull String alias, @NonNull File containerDir, @NonNull File rootfs) {
        List<TerminowLinuxPaths.Mount> none = Collections.emptyList();
        Map<String, String> os = readOsRelease(rootfs, none);
        boolean shell = resolvesToFile(rootfs, none, "/bin/sh");
        TerminowLinuxEnv.Status status;
        String installing = mInstalling != null ? mInstalling.getInstallingAlias() : null;
        if (alias.equals(installing)) status = TerminowLinuxEnv.Status.INSTALLING;
        else status = !os.isEmpty() && shell ? TerminowLinuxEnv.Status.INSTALLED : TerminowLinuxEnv.Status.INCOMPLETE;

        List<TerminowLinuxEnv.User> users = readUsers(rootfs, none);
        JSONObject settings = mSettings.load().optJSONObject(alias);
        String defaultUser = settings != null ? settings.optString("defaultUser", "root") : "root";
        boolean known = false;
        for (TerminowLinuxEnv.User user : users) if (user.name.equals(defaultUser)) known = true;
        if (!known) defaultUser = "root";
        String defaultFolder = settings != null ? settings.optString("defaultFolder") : "";
        if (!defaultFolder.isEmpty() && !GUEST_PATH.matcher(defaultFolder).matches()) defaultFolder = "";
        List<TerminowLinuxEnv.SharedFolder> shared = new ArrayList<>();
        JSONArray sharedArray = settings != null ? settings.optJSONArray("shared") : null;
        for (int i = 0; sharedArray != null && i < sharedArray.length(); i++) {
            JSONObject entry = sharedArray.optJSONObject(i);
            if (entry == null) continue;
            String host = entry.optString("host");
            String guest = entry.optString("guest");
            if (isValidShare(host, guest)) shared.add(new TerminowLinuxEnv.SharedFolder(host, guest));
        }
        return new TerminowLinuxEnv(alias, containerDir, rootfs, status, os.getOrDefault("ID", ""), os.getOrDefault("ID_LIKE", ""),
            os.getOrDefault("NAME", ""), os.getOrDefault("VERSION_ID", ""), os.getOrDefault("PRETTY_NAME", ""),
            readArch(containerDir, rootfs), users, defaultUser, defaultFolder, shared);
    }

    /** A host folder can be shared at a guest path that is absolute, not virtual, and not "/". */
    public static boolean isValidShare(@NonNull String host, @NonNull String guest) {
        if (!host.startsWith("/") || host.contains("\0") || host.contains(":") || host.contains("\n")) return false;
        if (!GUEST_PATH.matcher(guest).matches()) return false;
        String normalized = TerminowLinuxPaths.normalize(guest);
        if (normalized.equals("/") || normalized.contains("/..") || TerminowLinuxPaths.virtualRoot(normalized) != null) return false;
        for (String system : new String[]{"/bin", "/etc", "/lib", "/sbin", "/usr", "/var", "/boot"})
            if (TerminowLinuxPaths.isWithin(normalized, system)) return false;
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------

    private void update(@NonNull String alias, @NonNull String key, @NonNull Object value) {
        JSONObject all = mSettings.load();
        try {
            JSONObject settings = all.optJSONObject(alias);
            if (settings == null) settings = new JSONObject();
            settings.put(key, value);
            all.put(alias, settings);
        } catch (JSONException e) {
            return;
        }
        mSettings.save(all);
    }

    public void setDefaultUser(@NonNull String alias, @NonNull String user) {
        if (isValidUser(user)) update(alias, "defaultUser", user);
    }

    public void setDefaultFolder(@NonNull String alias, @NonNull String folder) {
        if (folder.isEmpty() || GUEST_PATH.matcher(folder).matches()) update(alias, "defaultFolder", folder);
    }

    public void setSharedFolders(@NonNull String alias, @NonNull List<TerminowLinuxEnv.SharedFolder> folders) {
        update(alias, "shared", TerminowLinuxEnv.sharedToJson(folders));
    }

    /** Forget the settings of an environment that was uninstalled. */
    public void forget(@NonNull String alias) {
        JSONObject all = mSettings.load();
        all.remove(alias);
        mSettings.save(all);
    }

    // ---------------------------------------------------------------------------------------
    // What the files of the environment say
    // ---------------------------------------------------------------------------------------

    /**
     * The folders that proot-distro binds into every environment that is not isolated, as it
     * does in 5.9: shared storage, the TermiNow runtime and home, and /dev/shm. Then the
     * folders that the user shared.
     */
    @NonNull
    public List<TerminowLinuxPaths.Mount> mountsOf(@NonNull TerminowLinuxEnv env) {
        List<TerminowLinuxPaths.Mount> mounts = new ArrayList<>();
        File primary = new File("/storage/emulated/0");
        mounts.add(new TerminowLinuxPaths.Mount("/storage", new File("/storage"), false));
        mounts.add(new TerminowLinuxPaths.Mount("/sdcard", primary, false));
        mounts.add(new TerminowLinuxPaths.Mount("/mnt/sdcard", primary, false));
        mounts.add(new TerminowLinuxPaths.Mount(mPrefix.getAbsolutePath(), mPrefix, false));
        mounts.add(new TerminowLinuxPaths.Mount(mHome.getAbsolutePath(), mHome, false));
        mounts.add(new TerminowLinuxPaths.Mount("/dev/shm", new File(env.containerDir, "shm"), false));
        for (TerminowLinuxEnv.SharedFolder folder : env.sharedFolders)
            mounts.add(new TerminowLinuxPaths.Mount(folder.guestPath, new File(folder.hostPath), true));
        return mounts;
    }

    private boolean resolvesToFile(@NonNull File rootfs, @NonNull List<TerminowLinuxPaths.Mount> mounts, @NonNull String path) {
        try {
            TerminowLinuxPaths.Resolved resolved = TerminowLinuxPaths.resolve(rootfs, mounts, path, true, mLinks);
            return resolved.kind == TerminowLinuxPaths.Kind.ROOTFS && resolved.file != null && resolved.file.isFile();
        } catch (IOException e) {
            return false;
        }
    }

    @Nullable
    private File fileOf(@NonNull File rootfs, @NonNull List<TerminowLinuxPaths.Mount> mounts, @NonNull String path) {
        try {
            TerminowLinuxPaths.Resolved resolved = TerminowLinuxPaths.resolve(rootfs, mounts, path, true, mLinks);
            if (resolved.kind != TerminowLinuxPaths.Kind.ROOTFS || resolved.file == null || !resolved.file.isFile()) return null;
            return resolved.file;
        } catch (IOException e) {
            return null;
        }
    }

    /** os-release of the environment, /etc/os-release or /usr/lib/os-release, resolved inside it. */
    @NonNull
    Map<String, String> readOsRelease(@NonNull File rootfs, @NonNull List<TerminowLinuxPaths.Mount> mounts) {
        for (String path : new String[]{"/etc/os-release", "/usr/lib/os-release"}) {
            File file = fileOf(rootfs, mounts, path);
            if (file != null) {
                Map<String, String> values = parseOsRelease(readText(file, 64 * 1024));
                if (!values.isEmpty()) return values;
            }
        }
        return Collections.emptyMap();
    }

    @NonNull
    public static Map<String, String> parseOsRelease(@NonNull String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            int equals = line.indexOf('=');
            if (equals <= 0 || line.startsWith("#")) continue;
            String value = line.substring(equals + 1).trim();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'")))
                value = value.substring(1, value.length() - 1);
            values.put(line.substring(0, equals).trim().toUpperCase(Locale.ROOT), value);
        }
        return values;
    }

    /** The users that can log in: root, and those with a uid from 1000 on. */
    @NonNull
    List<TerminowLinuxEnv.User> readUsers(@NonNull File rootfs, @NonNull List<TerminowLinuxPaths.Mount> mounts) {
        List<TerminowLinuxEnv.User> users = new ArrayList<>();
        String rootHome = "/root";
        File passwd = fileOf(rootfs, mounts, "/etc/passwd");
        List<TerminowLinuxEnv.User> others = new ArrayList<>();
        if (passwd != null) {
            for (String line : readText(passwd, 1024 * 1024).split("\n")) {
                String[] fields = line.split(":", -1);
                if (fields.length < 7 || !isValidUser(fields[0]) || !fields[5].startsWith("/")) continue;
                int uid;
                try {
                    uid = Integer.parseInt(fields[2]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (uid == 0 && fields[0].equals("root")) rootHome = fields[5];
                else if (uid >= 1000 && uid < 60000 && !fields[6].endsWith("nologin") && !fields[6].endsWith("false"))
                    others.add(new TerminowLinuxEnv.User(fields[0], fields[5]));
            }
        }
        users.add(new TerminowLinuxEnv.User("root", rootHome));
        users.addAll(others);
        return users;
    }

    /** The architecture from proot-distro's manifest.json, or from the ELF header of /bin/sh. */
    @NonNull
    String readArch(@NonNull File containerDir, @NonNull File rootfs) {
        File manifest = new File(containerDir, "manifest.json");
        if (manifest.isFile() && !mLinks.isLink(manifest)) {
            try {
                String arch = new JSONObject(readText(manifest, 4 * 1024 * 1024)).optString("arch");
                if (!arch.isEmpty()) return arch;
            } catch (JSONException e) {
                // From the ELF header then
            }
        }
        File shell = fileOf(rootfs, Collections.emptyList(), "/bin/sh");
        return shell != null ? elfArch(shell) : "";
    }

    /** The architecture of an ELF file, from e_machine, empty if it is not one. */
    @NonNull
    public static String elfArch(@NonNull File file) {
        byte[] header = new byte[20];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(header) < 20) return "";
        } catch (IOException e) {
            return "";
        }
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') return "";
        boolean little = header[5] == 1;
        int machine = little ? (header[18] & 0xff) | (header[19] & 0xff) << 8 : (header[19] & 0xff) | (header[18] & 0xff) << 8;
        switch (machine) {
            case 183: return "aarch64";
            case 40: return "arm";
            case 62: return "x86_64";
            case 3: return "i686";
            case 243: return "riscv64";
            default: return "";
        }
    }

    @NonNull
    static String readText(@NonNull File file, int max) {
        StringBuilder out = new StringBuilder();
        if (file.length() > max) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) out.append(buffer, 0, read);
        } catch (IOException e) {
            return "";
        }
        return out.toString();
    }

}
