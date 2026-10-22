package com.termux.view.textselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The links that Open in Chrome of the selection menu finds, exactly as they were written. */
public class SelectionUrlsTest {

    @Test
    public void aLinkIsKeptWithItsPathQueryFragmentAndEncoding() {
        String url = "https://github.com/termux/termux-app/pull/42/files?diff=split&w=1#diff-a%2Fb%3D";
        assertEquals(Collections.singletonList(url), SelectionUrls.find("see " + url + " now"));
        String callback = "https://claude.ai/oauth/authorize?code=true&client_id=9d1c&response_type=code&redirect_uri=https%3A%2F%2Fconsole.anthropic.com%2Foauth%2Fcode%2Fcallback&state=Ab-Cd_e~f";
        assertEquals(Collections.singletonList(callback), SelectionUrls.find(callback));
    }

    @Test
    public void punctuationAroundALinkIsNotPartOfIt() {
        assertEquals(Collections.singletonList("https://example.com/a"), SelectionUrls.find("Open https://example.com/a."));
        assertEquals(Collections.singletonList("https://example.com/a"), SelectionUrls.find("(see https://example.com/a)"));
        assertEquals(Collections.singletonList("https://example.com/a"), SelectionUrls.find("<https://example.com/a>"));
        assertEquals(Collections.singletonList("https://example.com/a"), SelectionUrls.find("\"https://example.com/a\","));
        // A bracket the link opened itself stays
        assertEquals(Collections.singletonList("https://en.wikipedia.org/wiki/Bash_(Unix_shell)"),
            SelectionUrls.find("https://en.wikipedia.org/wiki/Bash_(Unix_shell)."));
    }

    @Test
    public void onlyHttpAndHttpsWithAHostAndEachOnce() {
        assertTrue(SelectionUrls.find("ftp://a.b/c file:///etc/passwd javascript:alert(1) ls -la").isEmpty());
        assertTrue(SelectionUrls.find("https:// nothing").isEmpty());
        assertEquals(Arrays.asList("http://a.example/x", "https://b.example/"),
            SelectionUrls.find("http://a.example/x and https://b.example/ and http://a.example/x"));
    }

    @Test
    public void rowsAreOnlyJoinedWhereALinkGoesOnFromTheRightEdge() {
        // Wrapped by the terminal: one line
        assertEquals(Collections.singletonList("https://example.com/abcdefgh"), SelectionUrls.find(Arrays.asList(
            new SelectionUrls.Row("x https://example.com/ab", true, true), new SelectionUrls.Row("cdefgh", false, false))));
        // Broken by a program at the right edge, and going on
        assertEquals(Collections.singletonList("https://example.com/abcdefgh"), SelectionUrls.find(Arrays.asList(
            new SelectionUrls.Row("x https://example.com/ab", false, true), new SelectionUrls.Row("cdefgh more", false, false))));
        // Not at the right edge: the next row is something else
        assertEquals(Collections.singletonList("https://example.com/ab"), SelectionUrls.find(Arrays.asList(
            new SelectionUrls.Row("x https://example.com/ab", false, false), new SelectionUrls.Row("cdefgh", false, false))));
        // At the edge, but the next row is a link of its own
        assertEquals(Arrays.asList("https://a.example/x", "https://b.example/y"), SelectionUrls.find(Arrays.asList(
            new SelectionUrls.Row("https://a.example/x", false, true), new SelectionUrls.Row("https://b.example/y", false, false))));
        // At the edge, but the next row begins with a space
        assertEquals(Collections.singletonList("https://a.example/x"), SelectionUrls.find(Arrays.asList(
            new SelectionUrls.Row("https://a.example/x", false, true), new SelectionUrls.Row("  next", false, false))));
    }

    /** A terminal of rows that are wrapped or not, as a terminal buffer would give them. */
    private static SelectionUrls.Screen screen(String[] rows, boolean[] wrapped) {
        return new SelectionUrls.Screen() {
            @Override
            public String text(int x1, int y, int x2) {
                String row = rows[y];
                if (x1 >= row.length()) return "";
                return row.substring(x1, Math.min(row.length(), x2 + 1)).replaceAll(" +$", "");
            }

            @Override
            public boolean wrapped(int y) {
                return wrapped[y];
            }
        };
    }

    @Test
    public void aLinkThatTheTerminalWrappedIsFoundWhole() {
        // 20 columns: "$ echo https://githu" wrapped onto the next rows
        String url = "https://github.com/termux/termux-app?tab=readme#install";
        String line = "$ echo " + url;
        // 63 characters: three full rows that the terminal wrapped, and the rest
        String[] rows = {line.substring(0, 20), line.substring(20, 40), line.substring(40, 60), line.substring(60), "next line"};
        List<SelectionUrls.Row> selected = SelectionUrls.rowsOf(screen(rows, new boolean[]{true, true, true, false, false}), 20, 0, 0, 19, 4);
        assertEquals(Collections.singletonList(url), SelectionUrls.find(selected));
    }

    @Test
    public void linesThatWereNotWrappedAreNotJoined() {
        String[] rows = {"https://example.com/one", "https://example.com/two", "plain text"};
        List<SelectionUrls.Row> selected = SelectionUrls.rowsOf(screen(rows, new boolean[]{false, false, false}), 40, 0, 0, 39, 2);
        assertEquals(Arrays.asList("https://example.com/one", "https://example.com/two"), SelectionUrls.find(selected));
    }

    @Test
    public void aLinkThatAProgramBrokeAtTheRightEdgeIsFollowed() {
        // Not wrapped by the terminal, but the first row is full to its last column
        String[] rows = {"https://example.com/abcdefghijklm", "nopq?x=1#y", "> "};
        List<SelectionUrls.Row> selected = SelectionUrls.rowsOf(screen(rows, new boolean[]{false, false, false}), 33, 0, 0, 32, 2);
        assertEquals(Collections.singletonList("https://example.com/abcdefghijklmnopq?x=1#y"), SelectionUrls.find(selected));
    }
}
