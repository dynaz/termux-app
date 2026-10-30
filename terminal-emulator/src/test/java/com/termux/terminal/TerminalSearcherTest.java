package com.termux.terminal;

import java.util.List;

public class TerminalSearcherTest extends TerminalTestCase {

    private List<TerminalSearcher.Match> search(String query, boolean caseSensitive) {
        return TerminalSearcher.search(mTerminal.getScreen(), query, caseSensitive);
    }

    public void testFindsMatchOnTheScreen() {
        withTerminalSized(11, 2).enterString("hello world");
        List<TerminalSearcher.Match> m = search("world", false);
        assertEquals(1, m.size());
        assertEquals(0, m.get(0).startRow);
        assertEquals(6, m.get(0).startCol);
        assertEquals(0, m.get(0).endRow);
        assertEquals(10, m.get(0).endCol);
    }

    public void testEmptyQueryAndNoMatch() {
        withTerminalSized(11, 2).enterString("hello world");
        assertTrue(search("", false).isEmpty());
        assertTrue(search("nope", false).isEmpty());
        assertTrue(search(null, false).isEmpty());
    }

    public void testCaseSensitivity() {
        withTerminalSized(11, 2).enterString("Hello HELLO");
        assertEquals("case-insensitive finds both", 2, search("hello", false).size());
        List<TerminalSearcher.Match> sensitive = search("HELLO", true);
        assertEquals(1, sensitive.size());
        assertEquals(6, sensitive.get(0).startCol);
    }

    public void testMultipleMatchesInTopToBottomOrder() {
        withTerminalSized(5, 3).enterString("ab ab\nxx ab");
        List<TerminalSearcher.Match> m = search("ab", false);
        assertEquals(3, m.size());
        // First row has two, then the third row one; rows are non-decreasing.
        assertTrue(m.get(0).startRow <= m.get(1).startRow);
        assertTrue(m.get(1).startRow <= m.get(2).startRow);
    }

    public void testFindsMatchInRetainedHistory() {
        // 3x2: "111" scrolls into history, screen shows "222"/"333".
        withTerminalSized(3, 2).enterString("111222333");
        List<TerminalSearcher.Match> hist = search("111", false);
        assertEquals(1, hist.size());
        assertEquals("history row is negative", -1, hist.get(0).startRow);
        assertEquals(0, hist.get(0).startCol);
        // And a current-screen match is at a non-negative row.
        assertEquals(0, search("222", false).get(0).startRow);
    }

    public void testMatchSpanningASoftWrap() {
        withTerminalSized(3, 2).enterString("abcdef"); // "abc" wraps into "def"
        List<TerminalSearcher.Match> m = search("cd", false);
        assertEquals(1, m.size());
        assertEquals(0, m.get(0).startRow);
        assertEquals(2, m.get(0).startCol);
        assertEquals(1, m.get(0).endRow);
        assertEquals(0, m.get(0).endCol);
    }

    public void testCloneRowsSnapshotsHistoryAndScreenAndIsDetached() {
        // 3x2: "111" scrolls into history; screen shows "222"/"333".
        withTerminalSized(3, 2).enterString("111222333");
        TerminalBuffer copy = mTerminal.getScreen().cloneRows(-1, 1);
        assertEquals(3, copy.getActiveRows());
        assertEquals("111", copy.getSelectedText(0, 0, 3, 0).trim());
        assertEquals("222", copy.getSelectedText(0, 1, 3, 1).trim());
        assertEquals("333", copy.getSelectedText(0, 2, 3, 2).trim());
        // The snapshot is detached: further output must not change the copy.
        enterString("444");
        assertEquals("111", copy.getSelectedText(0, 0, 3, 0).trim());
    }

    public void testCombiningMarkMatchesWithinOneCell() {
        // A base letter plus a combining accent occupies a single column; searching the composed
        // query (base + combining) must match — the same shape Thai vowels/tones take.
        withTerminalSized(6, 2).enterString("éllo");
        List<TerminalSearcher.Match> m = search("é", false);
        assertEquals(1, m.size());
        assertEquals(0, m.get(0).startCol);
        assertEquals(0, m.get(0).endCol);
        // The following letter stayed at the next column.
        assertEquals(1, search("l", false).get(0).startCol);
    }
}
