package com.termux.view.textselection;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The http and https links in selected terminal text, exactly as they are written: their path,
 * query, fragment and percent encoding are never changed. Rows that the terminal wrapped are one
 * line. A link that reaches the right edge of a full row and goes on at the start of the next
 * row, like one that a program broke itself, is followed onto that row; other rows are never
 * joined. Nothing here logs or keeps the text.
 */
public final class SelectionUrls {

    private SelectionUrls() {}

    /** A row of the selection, as far as it is selected. */
    public static final class Row {
        final String text;
        /** The terminal wrapped it: it goes on in the next row without a line break. */
        final boolean wrapped;
        /** Its text reaches the last column of the terminal. */
        final boolean full;

        public Row(@NonNull String text, boolean wrapped, boolean full) {
            this.text = text;
            this.wrapped = wrapped;
            this.full = full;
        }
    }

    /** The text of the terminal: a row as far as it is selected, and whether the terminal wrapped it. */
    public interface Screen {
        /** The text of row y from column x1 to x2, both included, without trailing spaces. */
        @NonNull
        String text(int x1, int y, int x2);

        boolean wrapped(int y);
    }

    /** The {@link Screen} of a terminal buffer. */
    @NonNull
    public static Screen screenOf(@NonNull com.termux.terminal.TerminalBuffer buffer) {
        return new Screen() {
            @NonNull
            @Override
            public String text(int x1, int y, int x2) {
                return buffer.getSelectedText(x1, y, x2, y, false);
            }

            @Override
            public boolean wrapped(int y) {
                return buffer.getLineWrap(y);
            }
        };
    }

    /**
     * The rows of a selection of the terminal, from its row y1 column x1 to row y2 column x2, as
     * far as they are selected, with whether the terminal wrapped them and whether they are full.
     * A selection of a whole long scrollback is only looked at for its first 2000 rows.
     */
    @NonNull
    public static List<Row> rowsOf(@NonNull Screen screen, int columns, int x1, int y1, int x2, int y2) {
        List<Row> rows = new ArrayList<>();
        int last = Math.min(y2, y1 + 2000);
        for (int y = y1; y <= last; y++) {
            int from = y == y1 ? x1 : 0;
            int to = y == y2 ? x2 : columns - 1;
            String text = screen.text(from, y, to);
            boolean wrapped = y < y2 && screen.wrapped(y);
            // Full: selected to the last column, with text there
            boolean full = to >= columns - 1 && !screen.text(columns - 1, y, columns - 1).isEmpty();
            rows.add(new Row(text, wrapped, full));
        }
        return rows;
    }

    /** What a link is made of after its scheme; whitespace, quotes and angle brackets end it. */
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://[^\\s<>\"'`\\u0000-\\u001f\\u007f]+");
    private static final Pattern URL_CHARS = Pattern.compile("^[^\\s<>\"'`\\u0000-\\u001f\\u007f]+");
    private static final Pattern STARTS_WITH_URL = Pattern.compile("(?i)^https?://");
    /** A host after the scheme: a link needs one. */
    private static final Pattern HAS_HOST = Pattern.compile("(?i)^https?://[^/?#\\s]+");

    /** The links of the rows, in the order they appear, each once. */
    @NonNull
    public static List<String> find(@NonNull List<Row> rows) {
        // The lines of the text, rows that were wrapped being one line
        List<String> lines = new ArrayList<>();
        List<Boolean> fullEnds = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            line.append(row.text);
            if (row.wrapped && i + 1 < rows.size()) continue;
            lines.add(line.toString());
            fullEnds.add(row.full);
            line.setLength(0);
        }
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = URL.matcher(lines.get(i));
            while (matcher.find()) {
                String url = matcher.group();
                // A link that goes on in the next line: at the very end of a full line, with the
                // next line starting with the rest of it, not with a link of its own
                boolean atEnd = matcher.end() == lines.get(i).length();
                int next = i;
                while (atEnd && fullEnds.get(next) && next + 1 < lines.size()) {
                    String following = lines.get(next + 1);
                    if (STARTS_WITH_URL.matcher(following).find()) break;
                    Matcher rest = URL_CHARS.matcher(following);
                    if (!rest.find()) break;
                    url += rest.group();
                    next++;
                    atEnd = rest.end() == following.length();
                }
                url = trimEnd(url);
                if (HAS_HOST.matcher(url).find() && !urls.contains(url)) urls.add(url);
            }
        }
        return urls;
    }

    /** Find the links of plain text, whose lines are never joined. */
    @NonNull
    public static List<String> find(@NonNull String text) {
        List<Row> rows = new ArrayList<>();
        for (String line : text.split("\n", -1)) rows.add(new Row(line, false, false));
        return find(rows);
    }

    /**
     * Without punctuation that ends the sentence around a link, like "see https://a.b/c." or a
     * link in parentheses; a closing bracket that the link opened itself stays, as in
     * https://en.wikipedia.org/wiki/Bash_(Unix_shell).
     */
    @NonNull
    static String trimEnd(@NonNull String url) {
        int end = url.length();
        while (end > 0) {
            char c = url.charAt(end - 1);
            if (".,;:!?".indexOf(c) >= 0) {
                end--;
                continue;
            }
            char open = c == ')' ? '(' : c == ']' ? '[' : c == '}' ? '{' : 0;
            if (open != 0 && count(url, 0, end, open) < count(url, 0, end, c)) {
                end--;
                continue;
            }
            break;
        }
        return url.substring(0, end);
    }

    private static int count(@NonNull String text, int from, int to, char c) {
        int n = 0;
        for (int i = from; i < to; i++) if (text.charAt(i) == c) n++;
        return n;
    }
}
