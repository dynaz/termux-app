package com.termux.view.textselection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * Whether selected terminal text looks like a single file path, so the selection menu can offer
 * to open it. Only the shape is judged here; whether the path exists and where it is resolved is
 * decided by the app, which knows the environment of the tab. A link (http/https) is not a path:
 * it is opened by "Open in Chrome" instead. Nothing here is logged or kept.
 */
public final class SelectionPaths {

    private SelectionPaths() {}

    private static final Pattern URL = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://");
    /** A bare name with an extension, like "app.apk" or "report.pdf": a path only with a known directory. */
    private static final Pattern NAMED_FILE = Pattern.compile(".*\\.[A-Za-z0-9][A-Za-z0-9]{0,11}$");
    /** Control characters never belong in a path we would open. */
    private static final Pattern CONTROL = Pattern.compile(".*[\\u0000-\\u001f\\u007f].*");

    /**
     * The file path in the selection, or {@code null} if it does not look like one. Spaces and
     * Unicode are allowed, since file names have them; a link, a multi-line selection, or plain
     * prose is not a path.
     */
    @Nullable
    public static String find(@Nullable String selectedText) {
        if (selectedText == null) return null;
        String path = selectedText.trim();
        if (path.isEmpty() || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) return null;
        if (CONTROL.matcher(path).matches()) return null;
        if (URL.matcher(path).find()) return null;
        if (looksLikePath(path)) return path;
        return null;
    }

    static boolean looksLikePath(@NonNull String path) {
        if (path.startsWith("/") || path.startsWith("~/") || path.equals("~")
            || path.startsWith("./") || path.startsWith("../")) return true;
        // A relative path with a directory part, like "src/main.c"
        if (path.indexOf('/') > 0) return true;
        // A bare file name with an extension, resolved only when the working directory is known
        return NAMED_FILE.matcher(path).matches();
    }
}
