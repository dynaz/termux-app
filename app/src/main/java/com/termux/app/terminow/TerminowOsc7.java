package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Parses an OSC 7 working-directory report, {@code file://<host>/<path>}, which a shell's integration
 * emits so the terminal can follow the current directory. The terminal emulator reassembles the escape
 * sequence (including across output chunks and BEL/ST terminators) and hands the payload after
 * {@code 7;} here.
 *
 * <p>The path is percent-decoded as UTF-8, so spaces ({@code %20}) and non-ASCII names are handled.
 * The result is <b>data</b>: the host and path are only reported; they are never executed, and the
 * caller validates the host against the session before trusting the path. Malformed input returns
 * {@code null} rather than a guess.
 */
public final class TerminowOsc7 {

    private TerminowOsc7() {}

    /** A reported location: the host that sent it (may be empty) and the decoded absolute path. */
    public static final class Location {
        public final String host;
        public final String path;

        public Location(@NonNull String host, @NonNull String path) {
            this.host = host;
            this.path = path;
        }
    }

    /**
     * Parse an OSC 7 payload (the text after {@code 7;}). Returns {@code null} if it is not a
     * {@code file://} URI or the path is empty.
     */
    @Nullable
    public static Location parse(@Nullable String payload) {
        if (payload == null) return null;
        String s = payload.trim();
        // Only file:// locations; anything else (http, a relative path, junk) is not a working directory.
        if (!startsWithIgnoreCase(s, "file://")) return null;
        String rest = s.substring("file://".length());
        // The host is up to the first '/', then the absolute path begins at that '/'.
        int slash = rest.indexOf('/');
        if (slash < 0) return null;                    // no path part
        String host = decode(rest.substring(0, slash));
        String path = decode(rest.substring(slash));
        if (path.isEmpty()) return null;
        return new Location(host, path);
    }

    private static boolean startsWithIgnoreCase(@NonNull String s, @NonNull String prefix) {
        return s.length() >= prefix.length() && s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * Percent-decode as UTF-8. {@code %XX} becomes its byte; a stray {@code %} that is not followed by
     * two hex digits is kept literally (malformed input is tolerated, not dropped). {@code +} is left
     * as-is — this is a path, not form data.
     */
    @NonNull
    static String decode(@NonNull String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length() && hex(value.charAt(i + 1)) >= 0 && hex(value.charAt(i + 2)) >= 0) {
                out.write((hex(value.charAt(i + 1)) << 4) | hex(value.charAt(i + 2)));
                i += 3;
            } else {
                // Any other character: write its UTF-8 bytes (handles already-literal Unicode too).
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
                i++;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
}
