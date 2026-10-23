package com.termux.app.terminow.files;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * TermiNow HOME, a real directory of the app. Node ids are paths relative to HOME, "" for HOME.
 *
 * Nothing outside HOME is reachable: names with "/" or ".." are refused, and a symbolic link is
 * listed as a link and only opened if what it points to is inside HOME. Recursive operations
 * never follow links, so a link can not make them loop or reach other files.
 */
public class TerminowLocalFs implements TerminowFs {

    public static final String ID = "home";

    private final File mHome;
    private final String mTitle;
    private final String mCanonicalHome;
    private final String mId;

    public TerminowLocalFs(@NonNull File home, @NonNull String title) throws IOException {
        this(home, ID, title);
    }

    /** A real directory of the device as the root, which is never left, like HOME. */
    protected TerminowLocalFs(@NonNull File root, @NonNull String id, @NonNull String title) throws IOException {
        mHome = root;
        mTitle = title;
        mCanonicalHome = root.getCanonicalPath();
        mId = id;
    }

    @NonNull
    @Override
    public String getId() {
        return mId;
    }

    @NonNull
    @Override
    public String getTitle() {
        return mTitle;
    }

    @NonNull
    @Override
    public Capabilities getCapabilities() {
        Capabilities capabilities = new Capabilities();
        capabilities.terminal = true;
        capabilities.directShare = true;
        return capabilities;
    }

    @NonNull
    public File getHome() {
        return mHome;
    }

    /** The file of a node, checked to be inside HOME. */
    @NonNull
    public File toFile(@NonNull String id) throws IOException {
        if (id.isEmpty()) return mHome;
        for (String part : id.split("/"))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Invalid path " + id);
        return new File(mHome, id);
    }

    /** Whether the real location of a file, after links, is inside HOME. */
    public boolean isInsideHome(@NonNull File file) {
        try {
            String path = file.getCanonicalPath();
            return path.equals(mCanonicalHome) || path.startsWith(mCanonicalHome + "/");
        } catch (IOException e) {
            return false;
        }
    }

    /** Listing a folder failed because Android denied access, which is not the same as empty. */
    public static class AccessDeniedException extends IOException {
        public AccessDeniedException(@NonNull String what) {
            super("Android denied access to " + what);
        }
    }

    static boolean isLink(@NonNull File file) {
        try {
            File parent = file.getParentFile();
            if (parent == null) return false;
            File inCanonicalParent = new File(parent.getCanonicalFile(), file.getName());
            return !inCanonicalParent.getCanonicalPath().equals(inCanonicalParent.getAbsolutePath());
        } catch (IOException e) {
            return false;
        }
    }

    @NonNull
    private Node toNode(@NonNull String id, @NonNull File file) {
        boolean link = isLink(file);
        // A link is a directory to open only if it points to a directory in HOME
        boolean directory = file.isDirectory() && (!link || isInsideHome(file));
        return new Node(id, id.isEmpty() ? mTitle : file.getName(), directory, link,
            directory ? -1 : (file.exists() ? file.length() : -1), file.lastModified(), null);
    }

    @NonNull
    private static String childId(@NonNull Node parent, @NonNull String name) throws IOException {
        checkName(name);
        return parent.id.isEmpty() ? name : parent.id + "/" + name;
    }

    static void checkName(@NonNull String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\0"))
            throw new IOException("\"" + name + "\" is not a valid name");
    }

    @NonNull
    @Override
    public Node getRoot() {
        return toNode("", mHome);
    }

    @NonNull
    @Override
    public List<Node> list(@NonNull Node directory, @NonNull Cancel cancel) throws IOException {
        File dir = toFile(directory.id);
        if (!isInsideHome(dir)) throw new IOException("This link points outside TermiNow Home");
        File[] files = dir.listFiles();
        // Not an empty folder: Android did not let the app read it
        if (files == null) throw new AccessDeniedException(directory.id.isEmpty() ? mTitle : directory.id);
        List<Node> nodes = new ArrayList<>(files.length);
        for (File file : files) {
            cancel.check();
            nodes.add(toNode(childId(directory, file.getName()), file));
        }
        return nodes;
    }

    @Nullable
    @Override
    public Node find(@NonNull Node directory, @NonNull String name) throws IOException {
        String id = childId(directory, name);
        File file = toFile(id);
        // A dangling link exists too, though exists() says no
        if (!file.exists() && !isLink(file)) return null;
        return toNode(id, file);
    }

    @NonNull
    @Override
    public Node createDirectory(@NonNull Node parent, @NonNull String name) throws IOException {
        String id = childId(parent, name);
        File file = toFile(id);
        if (file.exists()) throw new IOException(name + " exists already");
        if (!file.mkdir()) throw new IOException("Failed to create " + name);
        return toNode(id, file);
    }

    @NonNull
    @Override
    public Node rename(@NonNull Node node, @NonNull Node parent, @NonNull String newName) throws IOException {
        String id = childId(parent, newName);
        File target = toFile(id);
        if (target.exists() || isLink(target)) throw new IOException(newName + " exists already");
        if (!toFile(node.id).renameTo(target)) throw new IOException("Failed to rename " + node.name);
        return toNode(id, target);
    }

    @Nullable
    @Override
    public Node move(@NonNull Node node, @NonNull Node fromParent, @NonNull Node toParent) throws IOException {
        // A directory can not go into itself
        if (node.directory && (toParent.id.equals(node.id) || toParent.id.startsWith(node.id + "/")))
            throw new IOException("A folder can not be moved into itself");
        String id = childId(toParent, node.name);
        File target = toFile(id);
        if (target.exists() || isLink(target)) throw new IOException(node.name + " exists already");
        if (!toFile(node.id).renameTo(target)) return null;
        return toNode(id, target);
    }

    @Override
    public void delete(@NonNull Node node, @NonNull Node parent, @NonNull Cancel cancel) throws IOException {
        if (node.id.isEmpty()) throw new IOException("Home can not be deleted");
        deleteFile(toFile(node.id), cancel);
    }

    private void deleteFile(@NonNull File file, @NonNull Cancel cancel) throws IOException {
        cancel.check();
        // A link is removed itself, never what it points to
        if (file.isDirectory() && !isLink(file)) {
            File[] children = file.listFiles();
            if (children != null)
                for (File child : children) deleteFile(child, cancel);
        }
        if (!file.delete() && (file.exists() || isLink(file)))
            throw new IOException("Failed to delete " + file.getName());
    }

    @NonNull
    @Override
    public InputStream openInput(@NonNull Node file) throws IOException {
        File f = toFile(file.id);
        if (!isInsideHome(f)) throw new IOException(file.name + " points outside TermiNow Home");
        return new FileInputStream(f);
    }

    @NonNull
    @Override
    public OutputStream openOutput(@NonNull Node directory, @NonNull String name, boolean replace) throws IOException {
        File file = toFile(childId(directory, name));
        if (isLink(file)) throw new IOException(name + " is a link, which is not written through");
        if (file.exists()) {
            if (!replace) throw new IOException(name + " exists already");
            if (file.isDirectory()) throw new IOException(name + " is a folder");
        } else if (!file.createNewFile()) {
            throw new FileNotFoundException("Failed to create " + name);
        }
        return new FileOutputStream(file);
    }

    @NonNull
    @Override
    public String getDisplayPath(@NonNull List<Node> breadcrumbs) {
        Node last = breadcrumbs.isEmpty() ? null : breadcrumbs.get(breadcrumbs.size() - 1);
        return last == null || last.id.isEmpty() ? "~" : "~/" + last.id;
    }

    @Override
    public void close() {
    }

}
