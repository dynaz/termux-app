package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * A remote store for backups, used the same way by manual and scheduled backups. A provider only
 * ever sees keys under the feature's own prefix ({@link #PREFIX}); {@link #list()} returns just
 * those, and retention deletes only those, so TermiNow never touches a user's other files.
 *
 * <p>Implementations: {@link TerminowS3Provider} (S3 / S3-compatible) is first. Google Drive,
 * Dropbox and OneDrive are planned behind this same interface and are not implemented yet — a UI
 * must not offer them as working until each has a real end-to-end test.</p>
 */
interface TerminowBackupProvider {

    /** The key prefix this feature owns. Every object a provider lists, writes or deletes is under it. */
    String PREFIX = "terminow-backups/";

    /** A stable id, e.g. "s3". */
    @NonNull String id();

    /** A human name, e.g. "S3-compatible storage". */
    @NonNull String displayName();

    /** List the backups under {@link #PREFIX}. Most recent first is not required; callers sort. */
    @NonNull List<Remote> list() throws IOException;

    /**
     * Upload {@code file} to {@code key} (which must start with {@link #PREFIX}). May be retried
     * internally on transient failures; reports progress and honours cancellation through {@code p}.
     */
    void upload(@NonNull String key, @NonNull File file, @NonNull Progress p) throws IOException;

    /** Open {@code key} for reading, for inspect/restore. The caller closes the stream. */
    @NonNull InputStream open(@NonNull String key) throws IOException;

    /** Delete {@code key} (which must start with {@link #PREFIX}). */
    void delete(@NonNull String key) throws IOException;

    /** A listed backup object. */
    final class Remote {
        public final String key;
        public final long size;
        public final long modifiedMillis;

        Remote(@NonNull String key, long size, long modifiedMillis) {
            this.key = key;
            this.size = size;
            this.modifiedMillis = modifiedMillis;
        }
    }

    /** Upload progress and cancellation. {@link #cancelled()} is checked between chunks. */
    interface Progress {
        void onProgress(long sent, long total);
        boolean cancelled();

        Progress NONE = new Progress() {
            @Override public void onProgress(long sent, long total) {}
            @Override public boolean cancelled() { return false; }
        };
    }
}
