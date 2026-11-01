package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * The provider-independent half of a cloud backup: once a verified backup file exists on disk, this
 * uploads it, confirms it landed, and trims old backups. It is written against {@link
 * TerminowBackupProvider} so S3 and every later provider (Drive, Dropbox, OneDrive) reuse it, and it
 * is unit-tested against an in-process fake server. Producing and verifying the archive itself is
 * done by {@link TerminowPhoneBackup} (it needs proot-distro); this is only the remote half.
 */
final class TerminowCloudBackup {

    private TerminowCloudBackup() {}

    /** The remote key a backup of {@code alias} with file name {@code fileName} is stored at. */
    @NonNull
    static String keyFor(@NonNull String alias, @NonNull String fileName) {
        return TerminowBackupProvider.PREFIX + sanitize(alias) + "/" + sanitize(fileName);
    }

    /**
     * Upload {@code local} to {@code key}, verify the stored object is byte-for-byte identical by
     * downloading it back and comparing SHA-256 (size equality alone is not an integrity check), then
     * delete the oldest backups so only the newest {@code retain} remain. Retention runs only after
     * the upload and the content verification both succeed, and only over keys the provider lists
     * under the feature prefix. Returns the keys that were deleted.
     *
     * <p>If verification fails, previous successful backups are preserved (nothing is trimmed) and the
     * just-uploaded, unverified object is removed on a best-effort basis so a corrupt file can never be
     * offered for restore.
     *
     * @throws IOException if the upload fails, or verify cannot find the object, sees a wrong size, or
     *                     the downloaded content does not hash to the same value as what was sent.
     */
    @NonNull
    static List<String> uploadVerifyTrim(@NonNull TerminowBackupProvider provider, @NonNull String key,
                                         @NonNull File local, int retain,
                                         @NonNull TerminowBackupProvider.Progress progress) throws IOException {
        byte[] localHash = sha256(local);
        long expected = local.length();

        provider.upload(key, local, progress);

        List<TerminowBackupProvider.Remote> listed;
        try {
            // Size pre-check: cheap, and catches a truncated/short upload before downloading it.
            TerminowBackupProvider.Remote uploaded = null;
            listed = provider.list();
            for (TerminowBackupProvider.Remote r : listed) {
                if (r.key.equals(key)) { uploaded = r; break; }
            }
            if (uploaded == null)
                throw new IOException("The backup was uploaded but could not be found on the storage afterwards");
            if (uploaded.size != expected)
                throw new IOException("The uploaded backup is " + uploaded.size + " bytes on the storage, not " + expected + " as sent");

            // Content check: download it back and compare SHA-256 to what we sent.
            byte[] remoteHash;
            try (InputStream in = provider.open(key)) {
                remoteHash = sha256(in);
            }
            if (!java.security.MessageDigest.isEqual(localHash, remoteHash))
                throw new IOException("The uploaded backup did not match what was sent (checksum mismatch); it was discarded and earlier backups were kept");
        } catch (IOException verifyFailed) {
            // Preserve earlier good backups; remove the unverified object we just wrote.
            try { provider.delete(key); } catch (IOException ignored) {}
            throw verifyFailed;
        }

        // Retention: only now, and never the one we just made.
        java.util.List<String> toDelete = TerminowBackupRetention.keysToDelete(listed, retain);
        toDelete.remove(key);
        for (String old : toDelete) provider.delete(old);
        return toDelete;
    }

    /** Download {@code key} into {@code dest} so an existing local restore can read it. */
    static void downloadTo(@NonNull TerminowBackupProvider provider, @NonNull String key, @NonNull File dest) throws IOException {
        try (InputStream in = provider.open(key); OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
    }

    @NonNull
    static byte[] sha256(@NonNull File file) throws IOException {
        try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(file))) {
            return sha256(in);
        }
    }

    @NonNull
    static byte[] sha256(@NonNull InputStream in) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable on this device", e);
        }
    }

    @NonNull
    private static String sanitize(@NonNull String name) {
        // Keep keys simple and safe: no path traversal, no backslashes, no leading slashes.
        String s = name.replace('\\', '_').replace("..", "_");
        while (s.startsWith("/")) s = s.substring(1);
        return s.isEmpty() ? "backup" : s;
    }
}
