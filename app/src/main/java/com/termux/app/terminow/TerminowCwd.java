package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * The working directory of a <b>local</b> process, read from {@code /proc/<pid>/cwd}. This is a
 * reliable native mechanism for local sessions that needs no shell setup and injects nothing into the
 * terminal — it only resolves a symlink the kernel maintains.
 *
 * <p>It is only correct for a local shell: for a remote (SSH) session the local ssh client's working
 * directory is not the remote directory, so the caller must never use this for a remote session (that
 * would show the phone's filesystem for a remote shell).
 */
public final class TerminowCwd {

    private TerminowCwd() {}

    /**
     * The target of {@code <procRoot>/<pid>/cwd}, or {@code null} if the pid is not positive or the
     * link can not be read (the process ended, or it is not this app's own process).
     */
    @Nullable
    public static String readProcCwd(@NonNull File procRoot, int pid) {
        if (pid <= 0) return null;
        File link = new File(procRoot, pid + "/cwd");
        try {
            String target = link.getCanonicalPath();
            // getCanonicalPath returns the path itself when the symlink can not be resolved.
            return target.equals(link.getAbsolutePath()) ? null : target;
        } catch (IOException e) {
            return null;
        }
    }
}
