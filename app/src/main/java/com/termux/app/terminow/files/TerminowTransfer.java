package com.termux.app.terminow.files;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Copy or move nodes from one file system to a directory of another, or of the same one.
 *
 * Content is streamed with progress and can be cancelled. Each file is first written under a
 * temporary name and only renamed when it was copied completely, so a cancelled or failed copy
 * leaves no half file, and an existing file is only replaced after its replacement is complete.
 * A move deletes its source only after the copy was verified by its size. Symbolic links are
 * never followed, so a link can not make a copy loop or reach files outside its folder.
 */
public class TerminowTransfer {

    public enum Resolution { SKIP, REPLACE, KEEP_BOTH }

    public interface Listener {
        /** The progress, called often from the worker thread. */
        void onProgress(@NonNull String name, long bytesDone, long bytesTotal, int filesDone, int filesTotal);

        /** What to do with a file that exists already. Blocks the worker until answered. */
        @NonNull Resolution onConflict(@NonNull String name);
    }

    public static class Result {
        public int filesCopied;
        public int skipped;
        public final List<String> skippedLinks = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public boolean cancelled;
    }

    static final String PARTIAL_SUFFIX = ".terminow-partial";
    private static final int BUFFER_SIZE = 64 * 1024;

    private final TerminowFs mSource;
    private final TerminowFs mTarget;
    private final boolean mMove;
    private final Listener mListener;
    private final TerminowFs.Cancel mCancel;
    private final Result mResult = new Result();
    private long mBytesTotal;
    private long mBytesDone;
    private int mFilesTotal;
    private int mFilesDone;

    public TerminowTransfer(@NonNull TerminowFs source, @NonNull TerminowFs target, boolean move,
                            @NonNull Listener listener, @NonNull TerminowFs.Cancel cancel) {
        mSource = source;
        mTarget = target;
        mMove = move;
        mListener = listener;
        mCancel = cancel;
    }

    /**
     * @param sourcePath The directories down to the parent of the nodes, to find their parents.
     */
    @NonNull
    public Result run(@NonNull TerminowFs.Node sourceParent, @NonNull List<TerminowFs.Node> nodes, @NonNull TerminowFs.Node targetDirectory) {
        try {
            boolean sameFs = mSource.getId().equals(mTarget.getId());
            for (TerminowFs.Node node : nodes) {
                if (sameFs && node.directory && (targetDirectory.id.equals(node.id) || targetDirectory.id.startsWith(node.id + "/")))
                    throw new IOException("A folder can not be copied or moved into itself");
                if (sameFs && sourceParent.id.equals(targetDirectory.id) && mMove)
                    throw new IOException("The items are in this folder already");
            }
            for (TerminowFs.Node node : nodes) count(node);
            for (TerminowFs.Node node : nodes) {
                mCancel.check();
                if (sameFs && mMove && tryMoveDirectly(node, sourceParent, targetDirectory)) continue;
                transfer(node, sourceParent, targetDirectory);
            }
        } catch (TerminowFs.CancelledException e) {
            mResult.cancelled = true;
        } catch (IOException e) {
            mResult.errors.add(String.valueOf(e.getMessage()));
        }
        return mResult;
    }

    /** Count the files and bytes, for the progress. Links are not followed. */
    private void count(@NonNull TerminowFs.Node node) throws IOException {
        mCancel.check();
        if (node.link) return;
        if (node.directory) {
            for (TerminowFs.Node child : mSource.list(node, mCancel)) count(child);
        } else {
            mFilesTotal++;
            if (node.size > 0) mBytesTotal += node.size;
        }
    }

    /** Move within one file system without copying, if there is no conflict and it can. */
    private boolean tryMoveDirectly(@NonNull TerminowFs.Node node, @NonNull TerminowFs.Node sourceParent,
                                    @NonNull TerminowFs.Node targetDirectory) throws IOException {
        if (mTarget.find(targetDirectory, node.name) != null) return false;
        TerminowFs.Node moved = mSource.move(node, sourceParent, targetDirectory);
        if (moved == null) return false;
        if (!node.directory) {
            mFilesDone++;
            mResult.filesCopied++;
            if (node.size > 0) mBytesDone += node.size;
        } else {
            mFilesDone += countFilesQuietly(moved);
        }
        mListener.onProgress(node.name, mBytesDone, mBytesTotal, mFilesDone, mFilesTotal);
        return true;
    }

    private int countFilesQuietly(@NonNull TerminowFs.Node directory) {
        int count = 0;
        try {
            for (TerminowFs.Node child : mTarget.list(directory, mCancel))
                count += child.link ? 0 : child.directory ? countFilesQuietly(child) : 1;
        } catch (IOException e) {
            // Only for the progress
        }
        mResult.filesCopied += count;
        return count;
    }

    /** Returns whether everything of the node was copied, so that a move can delete it. */
    private boolean transfer(@NonNull TerminowFs.Node node, @NonNull TerminowFs.Node sourceParent,
                             @NonNull TerminowFs.Node targetDirectory) throws IOException {
        mCancel.check();
        if (node.link) {
            mResult.skippedLinks.add(node.name);
            return false;
        }
        if (node.directory) return transferDirectory(node, sourceParent, targetDirectory);
        return transferFile(node, sourceParent, targetDirectory);
    }

    private boolean transferDirectory(@NonNull TerminowFs.Node node, @NonNull TerminowFs.Node sourceParent,
                                      @NonNull TerminowFs.Node targetDirectory) throws IOException {
        TerminowFs.Node existing = mTarget.find(targetDirectory, node.name);
        TerminowFs.Node directory;
        if (existing != null && existing.directory && !existing.link) {
            // Folders with the same name are merged, conflicts are asked for each file
            directory = existing;
        } else if (existing != null) {
            Resolution resolution = mListener.onConflict(node.name);
            if (resolution == Resolution.SKIP) {
                mResult.skipped++;
                return false;
            }
            if (resolution == Resolution.REPLACE) {
                mResult.errors.add(node.name + ": a file has this name, it is not replaced by a folder");
                return false;
            }
            directory = mTarget.createDirectory(targetDirectory, uniqueName(targetDirectory, node.name));
        } else {
            directory = mTarget.createDirectory(targetDirectory, node.name);
        }

        boolean complete = true;
        for (TerminowFs.Node child : mSource.list(node, mCancel)) {
            try {
                complete &= transfer(child, node, directory);
            } catch (TerminowFs.CancelledException e) {
                throw e;
            } catch (IOException e) {
                complete = false;
                mResult.errors.add(child.name + ": " + e.getMessage());
            }
        }
        if (mMove && complete) mSource.delete(node, sourceParent, mCancel);
        return complete;
    }

    private boolean transferFile(@NonNull TerminowFs.Node node, @NonNull TerminowFs.Node sourceParent,
                                 @NonNull TerminowFs.Node targetDirectory) throws IOException {
        String name = node.name;
        TerminowFs.Node existing = mTarget.find(targetDirectory, name);
        boolean replace = false;
        if (existing != null) {
            Resolution resolution = mListener.onConflict(name);
            if (resolution == Resolution.SKIP) {
                mResult.skipped++;
                mFilesDone++;
                if (node.size > 0) mBytesDone += node.size;
                return false;
            }
            if (resolution == Resolution.REPLACE) {
                if (existing.directory) {
                    mResult.errors.add(name + ": a folder has this name, it is not replaced by a file");
                    return false;
                }
                replace = true;
            } else {
                name = uniqueName(targetDirectory, name);
            }
        }

        String partialName = "." + name + PARTIAL_SUFFIX;
        TerminowFs.Node stale = mTarget.find(targetDirectory, partialName);
        if (stale != null && !stale.directory) mTarget.delete(stale, targetDirectory, mCancel);

        long copied = 0;
        boolean done = false;
        try {
            try (InputStream in = mSource.openInput(node);
                 OutputStream out = mTarget.openOutput(targetDirectory, partialName, false)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    mCancel.check();
                    out.write(buffer, 0, read);
                    copied += read;
                    mBytesDone += read;
                    mListener.onProgress(node.name, mBytesDone, mBytesTotal, mFilesDone, mFilesTotal);
                }
            }
            TerminowFs.Node partial = mTarget.find(targetDirectory, partialName);
            if (partial == null) throw new IOException("The copy of " + node.name + " disappeared");
            // Verify by size, where both sizes are known
            if ((partial.size >= 0 && partial.size != copied) || (node.size >= 0 && node.size != copied))
                throw new IOException("The copy of " + node.name + " is incomplete");
            if (replace) {
                TerminowFs.Node old = mTarget.find(targetDirectory, name);
                if (old != null) mTarget.delete(old, targetDirectory, mCancel);
            }
            mTarget.rename(partial, targetDirectory, name);
            done = true;
        } finally {
            if (!done) {
                try {
                    TerminowFs.Node partial = mTarget.find(targetDirectory, partialName);
                    if (partial != null && !partial.directory) mTarget.delete(partial, targetDirectory, new TerminowFs.Cancel());
                } catch (IOException e) {
                    // The partial file stays, with a name that shows what it is
                }
            }
        }
        mFilesDone++;
        mResult.filesCopied++;
        mListener.onProgress(node.name, mBytesDone, mBytesTotal, mFilesDone, mFilesTotal);
        if (mMove) mSource.delete(node, sourceParent, mCancel);
        return true;
    }

    /** "name (2).ext", the first that does not exist. */
    @NonNull
    String uniqueName(@NonNull TerminowFs.Node directory, @NonNull String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            String candidate = base + " (" + i + ")" + extension;
            if (mTarget.find(directory, candidate) == null) return candidate;
        }
        throw new IOException("No free name for " + name);
    }

    @Nullable
    public static String describe(@NonNull Result result) {
        StringBuilder text = new StringBuilder();
        if (result.cancelled) text.append("Cancelled. ");
        text.append(result.filesCopied).append(result.filesCopied == 1 ? " file" : " files").append(" done");
        if (result.skipped > 0) text.append(", ").append(result.skipped).append(" skipped");
        if (!result.skippedLinks.isEmpty())
            text.append(", ").append(result.skippedLinks.size()).append(" links not copied (links are never followed)");
        if (!result.errors.isEmpty()) {
            text.append(".\n\nProblems:");
            for (int i = 0; i < Math.min(10, result.errors.size()); i++) text.append("\n• ").append(result.errors.get(i));
            if (result.errors.size() > 10) text.append("\n• …and ").append(result.errors.size() - 10).append(" more");
        }
        return text.toString();
    }

}
