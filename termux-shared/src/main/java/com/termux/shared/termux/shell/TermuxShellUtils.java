package com.termux.shared.termux.shell;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.file.filesystem.FileTypes;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

import org.apache.commons.io.filefilter.TrueFileFilter;

import android.os.Build;
import android.os.Process;

import com.termux.shared.logger.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TermuxShellUtils {

    private static final String LOG_TAG = "TermuxShellUtils";

    /** The path of the library of the `termux-exec` package that `$LD_PRELOAD` is set to. */
    public static final String TERMUX_EXEC_LD_PRELOAD_LIB_PATH = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libtermux-exec-ld-preload.so";
    /** The variant of {@link #TERMUX_EXEC_LD_PRELOAD_LIB_PATH} that executes files with the system linker. */
    public static final String TERMUX_EXEC_LINKER_LD_PRELOAD_LIB_PATH = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libtermux-exec-linker-ld-preload.so";

    private static Boolean SYSTEM_LINKER_EXEC_REQUIRED;

    /** Get the SeLinux context of the app process, like `u:r:untrusted_app:s0:c1,c2,c512,c768`. */
    @Nullable
    public static String getSeProcessContext() {
        try (FileInputStream in = new FileInputStream("/proc/self/attr/current")) {
            byte[] buffer = new byte[256];
            int bytesRead = in.read(buffer);
            if (bytesRead <= 0) return null;
            String context = new String(buffer, 0, bytesRead).replace("\0", "").trim();
            return context.isEmpty() ? null : context;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Whether files under the app data directory must be executed with the system linker, like
     * `/system/bin/linker64 /path/to/executable`, instead of directly. Android `>= 10` does not
     * allow apps that use `targetSdkVersion` `>= 29` to execute files in their data directory, but
     * the system linker is still allowed to load and run them. Processes started by the app
     * do the same with the `termux-exec` library set in `$LD_PRELOAD`.
     * https://github.com/termux/termux-exec-package/blob/v2.5.0/site/pages/en/projects/docs/usage/index.md#termux_exec__system_linker_exec__mode
     */
    public static synchronized boolean isSystemLinkerExecRequired() {
        if (SYSTEM_LINKER_EXEC_REQUIRED == null) {
            String context = getSeProcessContext();
            SYSTEM_LINKER_EXEC_REQUIRED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                // The contexts for targetSdkVersion <= 28 are exempted
                (context == null || !(context.startsWith("u:r:untrusted_app_25:") || context.startsWith("u:r:untrusted_app_27:")));
        }
        return SYSTEM_LINKER_EXEC_REQUIRED;
    }

    @NonNull
    public static String getSystemLinkerPath() {
        return Process.is64Bit() ? "/system/bin/linker64" : "/system/bin/linker";
    }

    public static boolean isSystemLinkerPath(@Nullable String path) {
        return "/system/bin/linker64".equals(path) || "/system/bin/linker".equals(path);
    }

    private static boolean isPathUnderAppDataDir(@NonNull String path) {
        return path.startsWith(TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH + "/") ||
            path.startsWith("/data/user/");
    }

    /**
     * Make the `termux-exec` library that `$LD_PRELOAD` is set to the variant that executes
     * files with the system linker if {@link #isSystemLinkerExecRequired()}. This is also done
     * by the `termux-exec-ld-preload-lib setup` command when the `termux-exec` package is
     * installed, but that can not be run before the library is set up.
     */
    public static synchronized void setupSystemLinkerExecLdPreloadLib() {
        if (!isSystemLinkerExecRequired()) return;

        File linkerLib = new File(TERMUX_EXEC_LINKER_LD_PRELOAD_LIB_PATH);
        File lib = new File(TERMUX_EXEC_LD_PRELOAD_LIB_PATH);
        if (!linkerLib.isFile()) return;

        try {
            byte[] linkerLibBytes = readFile(linkerLib);
            if (lib.isFile() && Arrays.equals(linkerLibBytes, readFile(lib))) return;

            // Replace the file instead of writing to it since it may be loaded by running processes
            File tmpLib = new File(lib.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmpLib)) {
                out.write(linkerLibBytes);
            }
            if (!tmpLib.renameTo(lib))
                throw new IOException("Failed to rename \"" + tmpLib + "\" to \"" + lib + "\"");
            Logger.logInfo(LOG_TAG, "Set \"" + lib + "\" to the system linker exec variant");
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to setup \"" + lib + "\" for system linker exec", e);
        }
    }

    private static byte[] readFile(@NonNull File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1)
                out.write(buffer, 0, bytesRead);
            return out.toByteArray();
        }
    }

    /**
     * Setup shell command arguments for the execute. The file interpreter may be prefixed to
     * command arguments if needed, and the system linker if {@link #isSystemLinkerExecRequired()}.
     */
    @NonNull
    public static String[] setupShellCommandArguments(@NonNull String executable, @Nullable String[] arguments) {
        // The file to execute may either be:
        // - An elf file, in which we execute it directly.
        // - A script file without shebang, which we execute with our standard shell $PREFIX/bin/sh instead of the
        //   system /system/bin/sh. The system shell may vary and may not work at all due to LD_LIBRARY_PATH.
        // - A file with shebang, which we try to handle with e.g. /bin/foo -> $PREFIX/bin/foo. If system
        //   linker exec is required, then the interpreter of any shebang is used, since the kernel
        //   can not execute a script whose interpreter is under the app data directory.
        boolean systemLinkerExecRequired = isSystemLinkerExecRequired();
        String interpreter = null;
        String interpreterArgument = null;
        try {
            File file = new File(executable);
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[256];
                int bytesRead = in.read(buffer);
                if (bytesRead > 4) {
                    if (buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
                        // Elf file, do nothing.
                    } else if (buffer[0] == '#' && buffer[1] == '!') {
                        // Try to parse shebang.
                        String shebang = new String(buffer, 2, bytesRead - 2);
                        int lineEnd = shebang.indexOf('\n');
                        if (lineEnd >= 0) shebang = shebang.substring(0, lineEnd);
                        String[] shebangParts = shebang.trim().split("[ \t]+", 2);
                        String shebangExecutable = shebangParts[0];
                        if (shebangExecutable.startsWith("/usr") || shebangExecutable.startsWith("/bin")) {
                            String[] parts = shebangExecutable.split("/");
                            String binary = parts[parts.length - 1];
                            interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + binary;
                        } else if (systemLinkerExecRequired && shebangExecutable.startsWith("/")) {
                            interpreter = shebangExecutable;
                        }
                        if (interpreter != null && systemLinkerExecRequired && shebangParts.length > 1 && !shebangParts[1].trim().isEmpty())
                            interpreterArgument = shebangParts[1].trim();
                    } else {
                        // No shebang and no ELF, use standard shell.
                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
                    }
                }
            }
        } catch (IOException e) {
            // Ignore.
        }

        List<String> result = new ArrayList<>();
        // The linker executes the file passed as its first argument
        if (systemLinkerExecRequired && isPathUnderAppDataDir(interpreter != null ? interpreter : executable))
            result.add(getSystemLinkerPath());
        if (interpreter != null) result.add(interpreter);
        if (interpreterArgument != null) result.add(interpreterArgument);
        result.add(executable);
        if (arguments != null) Collections.addAll(result, arguments);
        return result.toArray(new String[0]);
    }

    /** Clear files under {@link TermuxConstants#TERMUX_TMP_PREFIX_DIR_PATH}. */
    public static void clearTermuxTMPDIR(boolean onlyIfExists) {
        // Existence check before clearing may be required since clearDirectory() will automatically
        // re-create empty directory if doesn't exist, which should not be done for things like
        // termux-reset (d6eb5e35). Moreover, TMPDIR must be a directory and not a symlink, this can
        // also allow users who don't want TMPDIR to be cleared automatically on termux exit, since
        // it may remove files still being used by background processes (#1159).
        if(onlyIfExists && !FileUtils.directoryFileExists(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, false))
            return;

        Error error;

        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        int days = properties.getDeleteTMPDIRFilesOlderThanXDaysOnExit();

        // Disable currently until FileUtils.deleteFilesOlderThanXDays() is fixed.
        if (days > 0)
            days = 0;

        if (days < 0) {
            Logger.logInfo(LOG_TAG, "Not clearing termux $TMPDIR");
        } else if (days == 0) {
            error = FileUtils.clearDirectory("$TMPDIR",
                FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null));
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Failed to clear termux $TMPDIR\n" + error);
            }
        } else {
            error = FileUtils.deleteFilesOlderThanXDays("$TMPDIR",
                FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null),
                TrueFileFilter.INSTANCE, days, true, FileTypes.FILE_TYPE_ANY_FLAGS);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Failed to delete files from termux $TMPDIR older than " + days + " days\n" + error);
            }
        }
    }

}
