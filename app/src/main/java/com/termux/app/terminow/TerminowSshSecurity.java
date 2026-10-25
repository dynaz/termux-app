package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

/**
 * Detects the OpenSSH advisory that a session "is not using a post-quantum key exchange algorithm"
 * in the raw terminal output of an SSH connection.
 *
 * <p>The advisory is printed by the OpenSSH client to the terminal; there is no programmatic API for
 * it when ssh runs in a PTY, so it is found in the output stream. The detector tolerates ANSI escape
 * codes interleaved with the text and the phrase arriving split across several output chunks.
 *
 * <p>It keeps only a small rolling window of the most recent plain text, never the full output, so no
 * passwords, passphrases or other terminal content are retained. It looks only for a fixed advisory
 * phrase; it never stores or inspects anything else.
 *
 * @see <a href="https://www.openssh.com/pq.html">https://www.openssh.com/pq.html</a>
 */
final class TerminowSshSecurity {

    /** The stable part of the OpenSSH advisory, matched case-insensitively against stripped text. */
    private static final String PQ_PHRASE = "connection is not using a post-quantum key exchange";

    /**
     * Enough recent characters to span the phrase even with ANSI codes between its words. The phrase
     * is ~50 characters; a generous window still cannot hold a whole login transcript.
     */
    private static final int WINDOW = 256;

    /** ANSI CSI (ESC[ ... final) and OSC (ESC] ... BEL/ST) sequences, plus a lone/other ESC code. */
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\].*?(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]");

    /** The most recent plain (ANSI-stripped) text, trimmed to at most {@link #WINDOW} characters. */
    private final StringBuilder recent = new StringBuilder(WINDOW + 64);
    /** A raw tail kept only to rejoin an ANSI escape split across two chunks; bounded and discarded. */
    private final StringBuilder rawTail = new StringBuilder(16);
    private boolean detected;

    /**
     * Feed a chunk of raw terminal output.
     *
     * @return true the first time the advisory becomes visible, false otherwise (including once seen).
     */
    boolean onOutput(@NonNull CharSequence chunk) {
        if (detected || chunk.length() == 0) return false;

        // Rejoin a short raw tail that may hold the start of an ANSI escape cut by the chunk boundary.
        rawTail.append(chunk);
        String stripped = ANSI.matcher(rawTail).replaceAll("");

        // Keep a short raw tail that could still be an unfinished escape (starts at the last ESC).
        int esc = rawTail.lastIndexOf("\u001B");
        if (esc >= 0 && rawTail.length() - esc <= 12) {
            String pending = rawTail.substring(esc);
            // Only text before a still-open escape is final; strip that prefix and keep the escape.
            stripped = ANSI.matcher(rawTail.substring(0, esc)).replaceAll("");
            rawTail.setLength(0);
            rawTail.append(pending);
        } else {
            rawTail.setLength(0);
        }

        if (stripped.isEmpty()) return false;
        recent.append(stripped);
        if (recent.length() > WINDOW) recent.delete(0, recent.length() - WINDOW);

        // Case-insensitive search without allocating a lowercased copy of unrelated output each call.
        if (indexOfIgnoreCase(recent, PQ_PHRASE) >= 0) {
            detected = true;
            recent.setLength(0);
            rawTail.setLength(0);
            return true;
        }
        return false;
    }

    boolean isDetected() {
        return detected;
    }

    private static int indexOfIgnoreCase(@NonNull CharSequence haystack, @NonNull String needle) {
        int max = haystack.length() - needle.length();
        for (int i = 0; i <= max; i++) {
            int j = 0;
            while (j < needle.length()
                    && Character.toLowerCase(haystack.charAt(i + j)) == Character.toLowerCase(needle.charAt(j))) {
                j++;
            }
            if (j == needle.length()) return i;
        }
        return -1;
    }
}
