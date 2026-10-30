package com.termux.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Searches the retained scrollback and the current screen of a {@link TerminalBuffer} for a query,
 * returning the matches in order from the top of history to the bottom. It is pure logic over the
 * buffer: it changes nothing, so a search never disturbs the terminal output, the cursor, a running
 * process, or the session. The caller highlights a match by its external coordinates.
 *
 * <p>Matches are found on <i>logical</i> lines — physical rows joined where a row soft-wrapped into
 * the next — so a hit that straddles a wrap boundary is still found and its span can cross rows. Each
 * display cell contributes its base code point and any following combining marks (so Thai and other
 * combining scripts match), and a double-width cell is mapped to the column it starts at. Only the
 * retained transcript is searched; evicted history is gone, which the UI states.</p>
 */
public final class TerminalSearcher {

    /** One match, in external coordinates (rows from {@code -activeTranscriptRows} to screenRows-1). */
    public static final class Match {
        public final int startRow;
        public final int startCol;
        public final int endRow;
        public final int endCol;

        Match(int startRow, int startCol, int endRow, int endCol) {
            this.startRow = startRow;
            this.startCol = startCol;
            this.endRow = endRow;
            this.endCol = endCol;
        }
    }

    /** A soft cap so a pathological query (e.g. a single space) cannot build an unbounded list. */
    public static final int MAX_MATCHES = 10_000;

    private TerminalSearcher() {}

    /**
     * All matches of {@code query} in {@code screen}, top to bottom. An empty or whitespace-free
     * requirement is not imposed — but an empty query yields no matches. Never throws on buffer
     * contents; out-of-range rows are treated as blank.
     */
    public static List<Match> search(TerminalBuffer screen, String query, boolean caseSensitive) {
        List<Match> matches = new ArrayList<>();
        if (screen == null || query == null || query.isEmpty()) return matches;
        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);

        int columns = screen.mColumns;
        int top = -screen.getActiveTranscriptRows();
        int bottom = screen.mScreenRows - 1;

        int row = top;
        while (row <= bottom) {
            // Gather the logical line starting at `row`: this row plus every following row that the
            // previous one wrapped into.
            StringBuilder text = new StringBuilder();
            List<int[]> map = new ArrayList<>(); // per char unit in `text`: {row, column}
            int r = row;
            while (true) {
                TerminalRow line = rowAt(screen, r, columns);
                boolean wrapped = line != null && line.mLineWrap;
                if (line != null) appendRow(line, columns, r, text, map);
                if (wrapped && r < bottom) {
                    r++;
                } else {
                    break;
                }
            }

            String haystack = caseSensitive ? text.toString() : text.toString().toLowerCase(Locale.ROOT);
            int from = 0;
            while (from <= haystack.length() - needle.length()) {
                int at = haystack.indexOf(needle, from);
                if (at < 0) break;
                int endIndex = at + needle.length() - 1;
                int[] start = map.get(at);
                int[] end = map.get(endIndex);
                matches.add(new Match(start[0], start[1], end[0], end[1]));
                if (matches.size() >= MAX_MATCHES) return matches;
                from = at + needle.length(); // non-overlapping
            }

            row = r + 1; // continue past the whole logical line
        }
        return matches;
    }

    private static TerminalRow rowAt(TerminalBuffer screen, int externalRow, int columns) {
        if (externalRow < -screen.getActiveTranscriptRows() || externalRow > screen.mScreenRows - 1) return null;
        int internal = screen.externalToInternalRow(externalRow);
        if (internal < 0 || internal >= screen.mLines.length) return null;
        return screen.mLines[internal];
    }

    /**
     * Append one physical row's display text to {@code text}, recording the (row, column) each char
     * unit sits at in {@code map}. Mirrors the renderer's column walk: a cell is its base code point
     * followed by its combining marks, and a wide cell advances the column by its width.
     */
    private static void appendRow(TerminalRow line, int columns, int externalRow, StringBuilder text, List<int[]> map) {
        char[] chars = line.mText;
        int used = line.getSpaceUsed();
        int charIndex = 0;
        int column = 0;
        while (column < columns && charIndex < used) {
            char c = chars[charIndex];
            boolean high = Character.isHighSurrogate(c);
            int codePoint = high ? Character.toCodePoint(c, chars[charIndex + 1]) : c;
            int width = WcWidth.width(codePoint);
            int startColumn = column;

            text.append(c);
            map.add(new int[]{externalRow, startColumn});
            if (high) {
                text.append(chars[charIndex + 1]);
                map.add(new int[]{externalRow, startColumn});
            }
            charIndex += high ? 2 : 1;

            // Combining marks (width <= 0) belong to the same cell/column as the base.
            while (charIndex < used && WcWidth.width(chars, charIndex) <= 0) {
                char cc = chars[charIndex];
                boolean ccHigh = Character.isHighSurrogate(cc);
                text.append(cc);
                map.add(new int[]{externalRow, startColumn});
                if (ccHigh && charIndex + 1 < used) {
                    text.append(chars[charIndex + 1]);
                    map.add(new int[]{externalRow, startColumn});
                }
                charIndex += ccHigh ? 2 : 1;
            }

            column += Math.max(1, width);
        }
    }
}
