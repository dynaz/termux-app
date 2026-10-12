package com.termux.terminal;

/**
 * Public access to the pseudo terminal functions of {@link JNI}, for processes that are not shown
 * in a {@link TerminalSession} of this app, like the sessions streamed to another app.
 */
public final class PtyProcess {

    private PtyProcess() {}

    /** See {@link JNI#createSubprocess}. Returns the file descriptor of the pseudo terminal master. */
    public static int create(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int rows, int columns) {
        return JNI.createSubprocess(cmd, cwd, args, envVars, processId, rows, columns, 0, 0);
    }

    public static void setWindowSize(int fd, int rows, int columns) {
        JNI.setPtyWindowSize(fd, rows, columns, 0, 0);
    }

    /** Waits for the process to exit and returns its exit status. */
    public static int waitFor(int processId) {
        return JNI.waitFor(processId);
    }

    public static void close(int fd) {
        JNI.close(fd);
    }
}
