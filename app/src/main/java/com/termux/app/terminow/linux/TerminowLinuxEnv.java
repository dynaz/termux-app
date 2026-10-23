package com.termux.app.terminow.linux;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * An installed Linux environment, a proot-distro container: what was found on disk, and the
 * settings that the user chose for it in TermiNow.
 */
public class TerminowLinuxEnv {

    public enum Status {
        /** The rootfs is there and has a shell. */
        INSTALLED,
        /** The folder is there but not a usable rootfs, like after an install that was killed. */
        INCOMPLETE,
        /** TermiNow is installing it right now. */
        INSTALLING
    }

    /** A user that can log in, and their home in the environment. */
    public static class User {
        public final String name;
        public final String home;

        public User(@NonNull String name, @NonNull String home) {
            this.name = name;
            this.home = home;
        }
    }

    /** A host folder that the user shared into the environment, bound with proot's --bind. */
    public static class SharedFolder {
        public final String hostPath;
        public final String guestPath;

        public SharedFolder(@NonNull String hostPath, @NonNull String guestPath) {
            this.hostPath = hostPath;
            this.guestPath = TerminowLinuxPaths.normalize(guestPath);
        }

        @NonNull
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("host", hostPath).put("guest", guestPath);
        }
    }

    /** "proot:<alias>", stable for as long as the container keeps its name. */
    public final String id;
    /** The name of the container in proot-distro, like "ubuntu". */
    public final String alias;
    public final File containerDir;
    public final File rootfs;
    public final Status status;
    /** From os-release: ID like "ubuntu", NAME like "Ubuntu", VERSION_ID like "24.04". */
    public final String distroId;
    public final String idLike;
    public final String name;
    public final String version;
    public final String prettyName;
    /** Like "aarch64", empty if not known. */
    public final String arch;
    public final List<User> users;
    /** Chosen by the user, or "root". */
    public final String defaultUser;
    /** Chosen by the user, empty for the home of the default user. */
    public final String defaultFolder;
    public final List<SharedFolder> sharedFolders;

    TerminowLinuxEnv(@NonNull String alias, @NonNull File containerDir, @NonNull File rootfs, @NonNull Status status,
                     @NonNull String distroId, @NonNull String idLike, @NonNull String name, @NonNull String version,
                     @NonNull String prettyName, @NonNull String arch, @NonNull List<User> users, @NonNull String defaultUser,
                     @NonNull String defaultFolder, @NonNull List<SharedFolder> sharedFolders) {
        this.id = idFor(alias);
        this.alias = alias;
        this.containerDir = containerDir;
        this.rootfs = rootfs;
        this.status = status;
        this.distroId = distroId;
        this.idLike = idLike;
        this.name = name;
        this.version = version;
        this.prettyName = prettyName;
        this.arch = arch;
        this.users = Collections.unmodifiableList(users);
        this.defaultUser = defaultUser;
        this.defaultFolder = defaultFolder;
        this.sharedFolders = Collections.unmodifiableList(sharedFolders);
    }

    @NonNull
    public static String idFor(@NonNull String alias) {
        return "proot:" + alias;
    }

    public boolean isInstalled() {
        return status == Status.INSTALLED;
    }

    /** "Ubuntu", "Debian", or the alias for something unknown. */
    @NonNull
    public String getShortName() {
        if (!name.isEmpty()) {
            String first = name.split(" ")[0];
            if (first.equalsIgnoreCase("Debian")) return "Debian";
            return first;
        }
        return alias;
    }

    /** "Ubuntu 24.04", for cards and tabs. */
    @NonNull
    public String getTitle() {
        return getShortName() + (version.isEmpty() ? "" : " " + version);
    }

    @Nullable
    public User getUser(@NonNull String user) {
        for (User u : users) if (u.name.equals(user)) return u;
        return null;
    }

    /** The home of a user in the environment, like "/root" or "/home/me". */
    @NonNull
    public String homeOf(@NonNull String user) {
        User u = getUser(user);
        if (u != null) return u.home;
        return user.equals("root") ? "/root" : "/home/" + user;
    }

    /** The folder to start in: the chosen default folder, or the home of the user. */
    @NonNull
    public String startFolder(@NonNull String user) {
        return defaultFolder.isEmpty() ? homeOf(user) : defaultFolder;
    }

    /** The package manager of the distribution, for installing tmux and the like. */
    @NonNull
    public String packageManager() {
        String ids = (distroId + " " + idLike).toLowerCase(Locale.ROOT);
        if (ids.contains("alpine")) return "apk";
        if (ids.contains("debian") || ids.contains("ubuntu")) return "apt";
        if (ids.contains("fedora") || ids.contains("rhel")) return "dnf";
        if (ids.contains("arch")) return "pacman";
        if (ids.contains("suse")) return "zypper";
        return "";
    }

    /** The command that installs a package as root, empty if the package manager is not known. */
    @NonNull
    public String installCommand(@NonNull String pkg) {
        switch (packageManager()) {
            case "apt": return "apt-get update && apt-get install -y " + pkg;
            case "apk": return "apk add " + pkg;
            case "dnf": return "dnf install -y " + pkg;
            case "pacman": return "pacman -Sy --noconfirm " + pkg;
            case "zypper": return "zypper --non-interactive install " + pkg;
            default: return "";
        }
    }

    /** The bindings that proot-distro login gets for the shared folders. */
    @NonNull
    public List<String> bindArguments() {
        List<String> args = new ArrayList<>();
        for (SharedFolder folder : sharedFolders) {
            args.add("--bind");
            args.add(folder.hostPath + ":" + folder.guestPath);
        }
        return args;
    }

    @NonNull
    static JSONArray sharedToJson(@NonNull List<SharedFolder> folders) {
        JSONArray array = new JSONArray();
        for (SharedFolder folder : folders) {
            try {
                array.put(folder.toJson());
            } catch (JSONException e) {
                // Skipped
            }
        }
        return array;
    }

}
