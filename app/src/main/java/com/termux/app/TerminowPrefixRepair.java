package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Repair the files of an installed prefix that use the data directory of another app package,
 * like `/data/data/com.termux/`, by replacing them with the files of the bootstrap of the app.
 *
 * The bootstrap is only installed if the prefix does not exist, so a prefix that was installed
 * from a bootstrap with such files, like the scripts of termux-tools that were built with the
 * paths of com.termux, keeps them after the app is updated, and `pkg` can not be used to update
 * them since it is one of them. Only the files that use another data directory and that the
 * bootstrap has without it are replaced, everything else, like the home directory, the other
 * files of the prefix and the installed packages, is kept. The replaced files are backed up to
 * `$PREFIX/var/backups/terminow-repair-<time>/` first.
 */
public class TerminowPrefixRepair {

    /** The size of the largest file that is checked, the files to repair are small scripts. */
    private static final int MAX_FILE_SIZE = 256 * 1024;

    private static final String PREFERENCES_NAME = "terminow_prefix_repair";
    private static final String KEY_REPAIRED_BOOTSTRAP = "repaired_bootstrap";
    private static final String KEY_RESULT = "result";

    private static final String LOG_TAG = "TerminowPrefixRepair";

    /**
     * Repair the prefix if it was not done yet for the bootstrap of this version of the app.
     * Returns the number of files that were replaced. Must not be called on the main thread.
     */
    public static synchronized int repairIfNeeded(@NonNull Context context) {
        // Once for each version of the app, since the bootstrap only changes with it
        String bootstrapId = String.valueOf(com.termux.BuildConfig.VERSION_CODE);
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (bootstrapId.equals(preferences.getString(KEY_REPAIRED_BOOTSTRAP, null))) return 0;

        int count;
        try {
            count = repair();
        } catch (Exception e) {
            // Tried again on the next start
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to repair the prefix", e);
            preferences.edit().putString(KEY_RESULT, "Failed, tried again on the next start: " + e.getMessage()).apply();
            return 0;
        }
        preferences.edit()
            .putString(KEY_REPAIRED_BOOTSTRAP, bootstrapId)
            .putString(KEY_RESULT, (count == 0 ? "Checked, no files needed to be replaced" :
                "Replaced " + count + " files, backed up in " + sLastBackupDir) +
                " (version " + bootstrapId + ")")
            .apply();
        return count;
    }

    /** Get the result of the last repair to show, {@code null} if none was done yet. */
    public static String getLastResult(@NonNull Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getString(KEY_RESULT, null);
    }

    private static String sLastBackupDir = "";

    private static int repair() throws IOException {
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        if (!prefix.isDirectory()) return 0;

        String ownDataDir = "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/";
        File backupDir = new File(prefix, "var/backups/terminow-repair-" +
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));
        List<String> repaired = new ArrayList<>();
        sLastBackupDir = "$PREFIX/var/backups/" + backupDir.getName();

        byte[] zipBytes = TermuxInstaller.loadZipBytes();
        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name.equals("SYMLINKS.txt") || name.contains("..")) continue;

                File file = new File(prefix, name);
                // Only files that exist as files, a link is left as it is
                if (!file.isFile() || file.length() > MAX_FILE_SIZE || !file.getCanonicalPath().equals(file.getAbsolutePath())) continue;

                byte[] current = readFile(file, MAX_FILE_SIZE + 1);
                if (current == null || !usesOtherDataDir(current, ownDataDir)) continue;

                byte[] replacement = readEntry(zipInput, MAX_FILE_SIZE + 1);
                // Only if the bootstrap has the file without another data directory
                if (replacement == null || usesOtherDataDir(replacement, ownDataDir)) continue;

                File backupFile = new File(backupDir, name);
                File backupParent = backupFile.getParentFile();
                if (backupParent != null && !backupParent.isDirectory() && !backupParent.mkdirs())
                    throw new IOException("Failed to create \"" + backupParent + "\"");
                writeFile(backupFile, current);

                // Write the new file next to the old one and replace it, so that it is never
                // partly written, and keep its permissions
                int mode;
                try {
                    mode = Os.stat(file.getPath()).st_mode & 07777;
                } catch (Exception e) {
                    mode = 0700;
                }
                File tmpFile = new File(file.getPath() + ".terminow-repair");
                writeFile(tmpFile, replacement);
                try {
                    Os.chmod(tmpFile.getPath(), mode);
                } catch (Exception e) {
                    Logger.logWarn(LOG_TAG, "Failed to set the permissions of \"" + tmpFile + "\": " + e.getMessage());
                }
                if (!tmpFile.renameTo(file))
                    throw new IOException("Failed to replace \"" + file + "\"");
                repaired.add(name);
            }
        }

        if (!repaired.isEmpty()) {
            StringBuilder log = new StringBuilder("Replaced files that used the data directory of another app package with the ones of the bootstrap of TermiNow ")
                .append(com.termux.BuildConfig.VERSION_NAME).append(" (").append(com.termux.BuildConfig.VERSION_CODE).append("):\n");
            for (String name : repaired)
                log.append(name).append("\n");
            writeFile(new File(backupDir, "README.txt"), log.toString().getBytes(StandardCharsets.UTF_8));
            Logger.logInfo(LOG_TAG, log.toString());
        }
        return repaired.size();
    }

    /** Whether data uses a `/data/data/<package>/` directory of a package other than the app. */
    private static boolean usesOtherDataDir(@NonNull byte[] data, @NonNull String ownDataDir) {
        String text = new String(data, StandardCharsets.ISO_8859_1);
        int index = 0;
        while ((index = text.indexOf("/data/data/", index)) >= 0) {
            if (!text.startsWith(ownDataDir, index)) {
                int end = text.indexOf('/', index + "/data/data/".length());
                // A package name followed by a directory of an app data directory
                if (end > 0 && (text.startsWith("/files", end) || text.startsWith("/cache", end)))
                    return true;
            }
            index += "/data/data/".length();
        }
        return false;
    }

    private static byte[] readFile(@NonNull File file, int maxSize) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readStream(in, maxSize);
        }
    }

    /** Read the current entry of a zip, {@code null} if it is larger than the maximum size. */
    private static byte[] readEntry(@NonNull ZipInputStream zipInput, int maxSize) throws IOException {
        return readStream(zipInput, maxSize);
    }

    private static byte[] readStream(@NonNull InputStream in, int maxSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            if (out.size() > maxSize) return null;
        }
        return out.toByteArray();
    }

    private static void writeFile(@NonNull File file, @NonNull byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

}
