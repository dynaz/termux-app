package com.termux.app.terminow.linux;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminow.files.TerminowFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The files of a Linux environment, by their Linux paths: node ids are paths like "/root/app",
 * which is also what is shown. Paths are resolved like the environment resolves them, see
 * {@link TerminowLinuxPaths}, so a link never reaches files of the app outside the environment
 * and its bound folders. Links are listed as links and kept by copies between environments,
 * with the permission bits.
 *
 * /proc, /sys and /dev exist only in a running environment and are not opened here, with a
 * message that says so. A copy for scanning, see {@link #forScan()}, also leaves out the bound
 * folders, so that search and the space analyzer do not count shared storage twice.
 */
public class TerminowLinuxFs implements TerminowFs, TerminowFs.Metadata {

    /** Opening a folder that only exists while the environment runs. */
    public static class VirtualFolderException extends IOException {
        public VirtualFolderException(@NonNull String path) {
            super(path + " only exists while the environment runs. Open it in a terminal of the environment.");
        }
    }

    /** Android or the environment's permissions do not let the app read it, which is not empty. */
    public static class AccessDeniedException extends IOException {
        public AccessDeniedException(@NonNull String path) {
            super("Permission denied: " + path);
        }
    }

    private final TerminowLinuxEnv mEnv;
    private final List<TerminowLinuxPaths.Mount> mMounts;
    private final TerminowLinuxFileOps mOps;
    private final boolean mScan;

    public TerminowLinuxFs(@NonNull TerminowLinuxEnv env, @NonNull List<TerminowLinuxPaths.Mount> mounts,
                           @NonNull TerminowLinuxFileOps ops) {
        this(env, mounts, ops, false);
    }

    private TerminowLinuxFs(@NonNull TerminowLinuxEnv env, @NonNull List<TerminowLinuxPaths.Mount> mounts,
                            @NonNull TerminowLinuxFileOps ops, boolean scan) {
        mEnv = env;
        mMounts = mounts;
        mOps = ops;
        mScan = scan;
    }

    /** The same files without the bound folders and the virtual ones, for search and space. */
    @NonNull
    public TerminowLinuxFs forScan() {
        return new TerminowLinuxFs(mEnv, mMounts, mOps, true);
    }

    @NonNull
    public static String idFor(@NonNull TerminowLinuxEnv env) {
        return "linux:" + env.alias;
    }

    @NonNull
    public TerminowLinuxEnv getEnv() {
        return mEnv;
    }

    @NonNull
    public List<TerminowLinuxPaths.Mount> getMounts() {
        return mMounts;
    }

    @NonNull
    @Override
    public String getId() {
        return idFor(mEnv) + (mScan ? ":scan" : "");
    }

    @NonNull
    @Override
    public String getTitle() {
        return mEnv.getTitle();
    }

    @NonNull
    @Override
    public Capabilities getCapabilities() {
        Capabilities capabilities = new Capabilities();
        capabilities.terminal = true;
        if (!mEnv.isInstalled()) {
            capabilities.write = capabilities.rename = capabilities.delete = false;
            capabilities.readOnlyReason = "The environment is not installed completely";
        }
        return capabilities;
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    public TerminowLinuxPaths.Resolved resolve(@NonNull String path, boolean followLast) throws IOException {
        return TerminowLinuxPaths.resolve(mEnv.rootfs, mMounts, path, followLast, mOps);
    }

    /** The file of a folder to work in, refused for the virtual ones. */
    @NonNull
    private File directoryFile(@NonNull String path) throws IOException {
        TerminowLinuxPaths.Resolved resolved = resolve(path, true);
        if (resolved.kind == TerminowLinuxPaths.Kind.VIRTUAL || resolved.file == null) throw new VirtualFolderException(resolved.path);
        return resolved.file;
    }

    /** The entry itself, a link is not followed. */
    @NonNull
    private File entryFile(@NonNull String path) throws IOException {
        String parent = TerminowLinuxPaths.parent(path);
        String name = path.substring(path.lastIndexOf('/') + 1);
        checkName(name);
        return new File(directoryFile(parent), name);
    }

    static void checkName(@NonNull String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\0"))
            throw new IOException("\"" + name + "\" is not a valid name");
    }

    private boolean isMountPoint(@NonNull String path) {
        for (TerminowLinuxPaths.Mount mount : mMounts) if (mount.guest.equals(path)) return true;
        return false;
    }

    private boolean isProtected(@NonNull String path) {
        return path.equals("/") || isMountPoint(path) || TerminowLinuxPaths.VIRTUAL.contains(path);
    }

    @NonNull
    private Node toNode(@NonNull String path, @NonNull File file, @Nullable TerminowLinuxFileOps.Stat stat) {
        String name = path.equals("/") ? mEnv.getTitle() : path.substring(path.lastIndexOf('/') + 1);
        if (stat == null) return new Node(path, name, false, false, -1, 0, null);
        boolean directory = stat.directory;
        if (stat.link) {
            // A link is a folder to open only when it leads to a folder that can be opened
            try {
                TerminowLinuxPaths.Resolved target = resolve(path, true);
                directory = target.kind != TerminowLinuxPaths.Kind.VIRTUAL && target.file != null && target.file.isDirectory();
            } catch (IOException e) {
                directory = false;
            }
        }
        return new Node(path, name, directory, stat.link, directory ? -1 : stat.size, stat.modified, null);
    }

    @NonNull
    @Override
    public Node getRoot() {
        return new Node("/", mEnv.getTitle(), true, false, -1, 0, null);
    }

    /** The node of a path, like the home of a user, {@code null} if it does not exist. */
    @Nullable
    public Node nodeOf(@NonNull String path) throws IOException {
        String normalized = TerminowLinuxPaths.normalize(path);
        if (normalized.equals("/")) return getRoot();
        if (TerminowLinuxPaths.VIRTUAL.contains(normalized) || isMountPoint(normalized))
            return new Node(normalized, normalized.substring(normalized.lastIndexOf('/') + 1), true, false, -1, 0, null);
        File file = entryFile(normalized);
        TerminowLinuxFileOps.Stat stat = mOps.lstat(file);
        return stat == null ? null : toNode(normalized, file, stat);
    }

    /** The nodes from "/" to a path, for the breadcrumbs of the browser. */
    @NonNull
    public List<Node> crumbsOf(@NonNull String path) throws IOException {
        List<Node> crumbs = new ArrayList<>();
        crumbs.add(getRoot());
        String current = "/";
        for (String part : TerminowLinuxPaths.normalize(path).split("/")) {
            if (part.isEmpty()) continue;
            current = TerminowLinuxPaths.child(current, part);
            Node node = nodeOf(current);
            if (node == null) throw new FileNotFoundException(current + " does not exist");
            crumbs.add(node);
        }
        return crumbs;
    }

    @NonNull
    @Override
    public List<Node> list(@NonNull Node directory, @NonNull Cancel cancel) throws IOException {
        File dir = directoryFile(directory.id);
        String[] names = dir.list();
        if (names == null) {
            if (mOps.lstat(dir) == null) throw new FileNotFoundException(directory.id + " does not exist");
            throw new AccessDeniedException(directory.id);
        }
        List<Node> nodes = new ArrayList<>(names.length);
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            cancel.check();
            String path = TerminowLinuxPaths.child(directory.id, name);
            boolean special = TerminowLinuxPaths.VIRTUAL.contains(path) || isMountPoint(path);
            if (mScan && special) continue;
            seen.add(name);
            if (special) {
                nodes.add(new Node(path, name, true, false, -1, 0, null));
                continue;
            }
            File file = new File(dir, name);
            TerminowLinuxFileOps.Stat stat = mOps.lstat(file);
            // Search and the space analyzer never follow links
            if (mScan && stat != null && stat.link) continue;
            nodes.add(toNode(path, file, stat));
        }
        // Bound folders appear where they are bound, also when the rootfs has no folder there
        if (!mScan) {
            for (TerminowLinuxPaths.Mount mount : mMounts) {
                if (!TerminowLinuxPaths.parent(mount.guest).equals(TerminowLinuxPaths.normalize(directory.id))) continue;
                String name = mount.guest.substring(mount.guest.lastIndexOf('/') + 1);
                if (name.isEmpty() || seen.contains(name) || !mount.host.isDirectory()) continue;
                nodes.add(new Node(mount.guest, name, true, false, -1, 0, null));
            }
        }
        return nodes;
    }

    @Nullable
    @Override
    public Node find(@NonNull Node directory, @NonNull String name) throws IOException {
        checkName(name);
        return nodeOf(TerminowLinuxPaths.child(directory.id, name));
    }

    @NonNull
    @Override
    public Node createDirectory(@NonNull Node parent, @NonNull String name) throws IOException {
        checkName(name);
        File file = new File(directoryFile(parent.id), name);
        if (mOps.lstat(file) != null) throw new IOException(name + " exists already");
        if (!file.mkdir()) throw new IOException("Could not create " + name + ": permission denied or no space");
        return toNode(TerminowLinuxPaths.child(parent.id, name), file, mOps.lstat(file));
    }

    @NonNull
    @Override
    public Node rename(@NonNull Node node, @NonNull Node parent, @NonNull String newName) throws IOException {
        checkName(newName);
        if (isProtected(node.id)) throw new IOException(node.id + " can not be renamed");
        File from = entryFile(node.id);
        File to = new File(directoryFile(parent.id), newName);
        if (mOps.lstat(to) != null) throw new IOException(newName + " exists already");
        if (!from.renameTo(to)) throw new IOException("Could not rename " + node.name + ": permission denied");
        return toNode(TerminowLinuxPaths.child(parent.id, newName), to, mOps.lstat(to));
    }

    @Nullable
    @Override
    public Node move(@NonNull Node node, @NonNull Node fromParent, @NonNull Node toParent) throws IOException {
        if (isProtected(node.id)) throw new IOException(node.id + " can not be moved");
        if (node.directory && !node.link && TerminowLinuxPaths.isWithin(TerminowLinuxPaths.normalize(toParent.id), node.id))
            throw new IOException("A folder can not be moved into itself");
        File from = entryFile(node.id);
        File to = new File(directoryFile(toParent.id), node.name);
        if (mOps.lstat(to) != null) throw new IOException(node.name + " exists already");
        // Across a bound folder this fails, and the caller copies instead
        if (!from.renameTo(to)) return null;
        return toNode(TerminowLinuxPaths.child(toParent.id, node.name), to, mOps.lstat(to));
    }

    @Override
    public void delete(@NonNull Node node, @NonNull Node parent, @NonNull Cancel cancel) throws IOException {
        if (isProtected(node.id)) throw new IOException(node.id + " can not be deleted here");
        deleteFile(entryFile(node.id), cancel);
    }

    private void deleteFile(@NonNull File file, @NonNull Cancel cancel) throws IOException {
        cancel.check();
        TerminowLinuxFileOps.Stat stat = mOps.lstat(file);
        if (stat == null) return;
        // A link is removed itself, never what it points to
        if (stat.directory && !stat.link) {
            String[] children = file.list();
            if (children == null) throw new AccessDeniedException(file.getName());
            for (String child : children) deleteFile(new File(file, child), cancel);
        }
        if (!file.delete() && mOps.lstat(file) != null) throw new IOException("Could not delete " + file.getName() + ": permission denied");
    }

    @NonNull
    @Override
    public InputStream openInput(@NonNull Node file) throws IOException {
        TerminowLinuxPaths.Resolved resolved = resolve(file.id, true);
        if (resolved.kind == TerminowLinuxPaths.Kind.VIRTUAL || resolved.file == null) throw new VirtualFolderException(resolved.path);
        if (!resolved.file.isFile()) throw new IOException(file.name + " is not a regular file");
        if (!resolved.file.canRead()) throw new AccessDeniedException(file.id);
        return new FileInputStream(resolved.file);
    }

    @NonNull
    @Override
    public OutputStream openOutput(@NonNull Node directory, @NonNull String name, boolean replace) throws IOException {
        checkName(name);
        File file = new File(directoryFile(directory.id), name);
        TerminowLinuxFileOps.Stat stat = mOps.lstat(file);
        if (stat != null) {
            if (stat.link) throw new IOException(name + " is a link, which is not written through");
            if (!replace) throw new IOException(name + " exists already");
            if (stat.directory) throw new IOException(name + " is a folder");
        }
        try {
            return new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            throw new AccessDeniedException(TerminowLinuxPaths.child(directory.id, name));
        }
    }

    @NonNull
    @Override
    public String getDisplayPath(@NonNull List<Node> breadcrumbs) {
        Node last = breadcrumbs.isEmpty() ? null : breadcrumbs.get(breadcrumbs.size() - 1);
        return mEnv.getShortName() + ":" + (last == null ? "/" : last.id);
    }

    @Override
    public void close() {
    }

    // ---------------------------------------------------------------------------------------
    // Linux metadata
    // ---------------------------------------------------------------------------------------

    @NonNull
    @Override
    public String readLink(@NonNull Node link) throws IOException {
        return mOps.readLink(entryFile(link.id));
    }

    @Override
    public void createLink(@NonNull Node directory, @NonNull String name, @NonNull String target) throws IOException {
        checkName(name);
        if (target.isEmpty() || target.contains("\0")) throw new IOException("Not a valid link target");
        mOps.symlink(target, new File(directoryFile(directory.id), name));
    }

    @Override
    public int getMode(@NonNull Node node) {
        try {
            TerminowLinuxFileOps.Stat stat = mOps.lstat(entryFile(node.id));
            return stat == null ? -1 : stat.mode;
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public void setMode(@NonNull Node directory, @NonNull String name, int mode) throws IOException {
        checkName(name);
        mOps.chmod(new File(directoryFile(directory.id), name), mode);
    }

    /** Where a Linux path of this environment is a real folder, for the size of a selection. */
    @Nullable
    public File hostFileOf(@NonNull String path) {
        try {
            TerminowLinuxPaths.Resolved resolved = resolve(path, true);
            return resolved.kind == TerminowLinuxPaths.Kind.VIRTUAL ? null : resolved.file;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Disk usage
    // ---------------------------------------------------------------------------------------

    /**
     * The bytes that the rootfs takes on disk: links are not followed, and the virtual folders
     * and the bound folders are not counted, they are not part of it.
     */
    public static long diskUsage(@NonNull TerminowLinuxEnv env, @NonNull List<TerminowLinuxPaths.Mount> mounts,
                                 @NonNull TerminowLinuxFileOps ops, @NonNull Cancel cancel) throws IOException {
        Set<String> skip = new HashSet<>(TerminowLinuxPaths.VIRTUAL);
        for (TerminowLinuxPaths.Mount mount : mounts) skip.add(mount.guest);
        return usage(env.rootfs, "/", skip, ops, cancel);
    }

    private static long usage(@NonNull File dir, @NonNull String path, @NonNull Set<String> skip, @NonNull TerminowLinuxFileOps ops,
                              @NonNull Cancel cancel) throws IOException {
        cancel.check();
        String[] names = dir.list();
        if (names == null) return 0;
        long total = 0;
        for (String name : names) {
            String child = TerminowLinuxPaths.child(path, name);
            if (skip.contains(child)) continue;
            File file = new File(dir, name);
            TerminowLinuxFileOps.Stat stat = ops.lstat(file);
            if (stat == null) continue;
            total += stat.allocated > 0 ? stat.allocated : (stat.link ? 0 : stat.size);
            if (stat.directory && !stat.link) total += usage(file, child, skip, ops, cancel);
        }
        return total;
    }

}
