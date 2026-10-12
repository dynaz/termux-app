package com.termux.app.terminow.files;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * A place that the File Explorer can browse: TermiNow HOME, a folder that the user picked with
 * the Storage Access Framework, or a saved SFTP host. All methods may block, and are only
 * called off the main thread.
 *
 * Nodes are identified by an id that only means something to their file system: a path
 * relative to HOME, a document URI, or an absolute remote path. They are never shown as
 * paths of the device, except for HOME, which is a real directory.
 */
public interface TerminowFs {

    /** What a file system can do, to disable the other actions with a reason. */
    class Capabilities {
        public boolean write = true;
        public boolean rename = true;
        public boolean delete = true;
        /** Whether it is a real directory of the device, which a terminal can start in. */
        public boolean terminal;
        /** Whether files can be shared to other apps directly, without a copy first. */
        public boolean directShare;
        /** Why writing is not possible, if it is not. */
        @Nullable public String readOnlyReason;
    }

    class Node {
        public final String id;
        public final String name;
        public final boolean directory;
        /** A symbolic link, which is never followed out of its file system or into a loop. */
        public final boolean link;
        /** -1 if not known. */
        public final long size;
        /** Milliseconds, 0 if not known. */
        public final long modified;
        @Nullable public final String mimeType;

        public Node(@NonNull String id, @NonNull String name, boolean directory, boolean link, long size, long modified,
                    @Nullable String mimeType) {
            this.id = id;
            this.name = name;
            this.directory = directory;
            this.link = link;
            this.size = size;
            this.modified = modified;
            this.mimeType = mimeType;
        }

        public boolean isHidden() {
            return name.startsWith(".");
        }
    }

    /** Cancelling a long operation from another thread. */
    class Cancel {
        private volatile boolean mCancelled;

        public void cancel() {
            mCancelled = true;
        }

        public boolean isCancelled() {
            return mCancelled;
        }

        public void check() throws CancelledException {
            if (mCancelled) throw new CancelledException();
        }
    }

    class CancelledException extends IOException {
        public CancelledException() {
            super("Cancelled");
        }
    }

    /** An identifier of the file system, like "home", "saf:<uri>" or "sftp:<host>". */
    @NonNull String getId();

    @NonNull String getTitle();

    @NonNull Capabilities getCapabilities();

    @NonNull Node getRoot() throws IOException;

    /** The entries of a directory. Hidden ones are included, the caller filters them. */
    @NonNull List<Node> list(@NonNull Node directory, @NonNull Cancel cancel) throws IOException;

    /** The entry with the name in the directory, {@code null} if there is none. */
    @Nullable Node find(@NonNull Node directory, @NonNull String name) throws IOException;

    @NonNull Node createDirectory(@NonNull Node parent, @NonNull String name) throws IOException;

    @NonNull Node rename(@NonNull Node node, @NonNull Node parent, @NonNull String newName) throws IOException;

    /**
     * Move within this file system, {@code null} if it can not do that directly, in which case the
     * caller copies and then deletes.
     */
    @Nullable Node move(@NonNull Node node, @NonNull Node fromParent, @NonNull Node toParent) throws IOException;

    /** Delete a file, a link, or a directory with everything in it. Links are never followed. */
    void delete(@NonNull Node node, @NonNull Node parent, @NonNull Cancel cancel) throws IOException;

    @NonNull InputStream openInput(@NonNull Node file) throws IOException;

    /**
     * Create a file in the directory and return a stream to write it. An existing file is only
     * replaced if {@code replace}, otherwise this fails.
     */
    @NonNull OutputStream openOutput(@NonNull Node directory, @NonNull String name, boolean replace) throws IOException;

    /** A path to show, like "~/projects/app", "Downloads / photos" or "host:/var/www". */
    @NonNull String getDisplayPath(@NonNull List<Node> breadcrumbs);

    void close();

}
