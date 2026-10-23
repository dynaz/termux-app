package com.termux.shared.android;

import android.system.ErrnoException;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * SELinux contexts through public interfaces only: the procfs attribute files of the kernel and
 * the {@code security.selinux} extended attribute with {@link Os#getxattr(String, String)}.
 */
public class SELinuxUtils {

    private static final String LOG_TAG = "SELinuxUtils";

    /**
     * Gets the security context of the current process.
     *
     * @return Returns a {@link String} representing the security context of the current process.
     * This will be {@code null} if it can not be read.
     */
    @Nullable
    public static String getContext() {
        return readContext("/proc/self/attr/current");
    }

    /**
     * Get the security context of a given process id.
     *
     * @param pid The pid of process.
     * @return Returns a {@link String} representing the security context of the given pid.
     * This will be {@code null} if it can not be read, like for a process of another app.
     */
    @Nullable
    public static String getPidContext(int pid) {
        return readContext("/proc/" + pid + "/attr/current");
    }

    /**
     * Get the security context of a file object.
     *
     * @param path The pathname of the file object.
     * @return Returns a {@link String} representing the security context of the file.
     * This will be {@code null} if it can not be read.
     */
    @Nullable
    public static String getFileContext(@NonNull String path) {
        try {
            return clean(Os.getxattr(path, "security.selinux"), -1);
        } catch (ErrnoException | RuntimeException e) {
            Logger.logError(LOG_TAG, "Failed to get SELinux context of \"" + path + "\": " + e.getMessage());
            return null;
        }
    }

    @Nullable
    private static String readContext(@NonNull String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buffer = new byte[256];
            return clean(buffer, in.read(buffer));
        } catch (IOException | RuntimeException e) {
            Logger.logError(LOG_TAG, "Failed to read SELinux context from \"" + path + "\": " + e.getMessage());
            return null;
        }
    }

    /** The context without its trailing null byte or newline, {@code null} if empty. */
    @Nullable
    private static String clean(@Nullable byte[] bytes, int length) {
        if (bytes == null) return null;
        if (length < 0) length = bytes.length;
        if (length == 0) return null;
        String context = new String(bytes, 0, length, StandardCharsets.UTF_8).replace("\0", "").trim();
        return context.isEmpty() ? null : context;
    }

}
