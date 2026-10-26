package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * QuickCut storage logic, apart from Android: the JSON round-trips (Thai and multiline content kept),
 * a duplicate name is detected rather than silently overwritten, Pinned/Recent/All order correctly,
 * search matches name and content, and content with a line break is flagged for confirmation.
 */
public class TerminowQuickCutsTest {

    private static TerminowQuickCut cut(String id, String name, String content) {
        return new TerminowQuickCut(id, name, content);
    }

    @Test
    public void jsonRoundTripKeepsThaiAndMultilineAndFields() {
        TerminowQuickCut a = cut("1", "สวัสดี", "echo \"สวัสดีชาวโลก\"\nls -la");
        a.pinned = true;
        a.usageCount = 5;
        a.lastUsedAt = 123456789L;
        String json = TerminowQuickCuts.toJson(Arrays.asList(a));
        List<TerminowQuickCut> back = TerminowQuickCuts.fromJson(json);
        assertEquals(1, back.size());
        TerminowQuickCut b = back.get(0);
        assertEquals("1", b.id);
        assertEquals("สวัสดี", b.name);
        assertEquals("echo \"สวัสดีชาวโลก\"\nls -la", b.content);
        assertTrue(b.pinned);
        assertEquals(5, b.usageCount);
        assertEquals(123456789L, b.lastUsedAt);
    }

    @Test
    public void emptyContentIsDropped() {
        assertTrue(TerminowQuickCuts.fromJson("[{\"id\":\"x\",\"name\":\"n\",\"content\":\"\"}]").isEmpty());
        assertTrue(TerminowQuickCuts.fromJson("not json").isEmpty());
    }

    @Test
    public void duplicateNameIsFoundNotOverwritten() {
        List<TerminowQuickCut> list = Arrays.asList(cut("1", "Deploy", "a"), cut("2", "Logs", "b"));
        assertNotNull(TerminowQuickCuts.findByName(list, "deploy"));
        assertEquals("1", TerminowQuickCuts.findByName(list, "DEPLOY").id);
        assertNull(TerminowQuickCuts.findByName(list, "Other"));
        assertNull("an empty name never collides", TerminowQuickCuts.findByName(list, "  "));
    }

    @Test
    public void pinnedAndRecentAndOrdered() {
        TerminowQuickCut p = cut("1", "Pinned", "p");
        p.pinned = true;
        p.lastUsedAt = 10;
        TerminowQuickCut used = cut("2", "Used", "u");
        used.lastUsedAt = 50;
        TerminowQuickCut old = cut("3", "Old", "o");
        old.lastUsedAt = 20;
        TerminowQuickCut never = cut("4", "Never", "n");
        List<TerminowQuickCut> list = Arrays.asList(p, used, old, never);

        List<TerminowQuickCut> pinned = TerminowQuickCuts.pinned(list);
        assertEquals(1, pinned.size());
        assertEquals("1", pinned.get(0).id);

        List<TerminowQuickCut> recent = TerminowQuickCuts.recent(list, 10);
        assertEquals("newest used first, pinned and never-used excluded", "2", recent.get(0).id);
        assertEquals("3", recent.get(1).id);
        assertEquals(2, recent.size());

        List<TerminowQuickCut> ordered = TerminowQuickCuts.ordered(list);
        assertEquals("pinned first", "1", ordered.get(0).id);
    }

    @Test
    public void searchMatchesNameAndContentCaseInsensitive() {
        List<TerminowQuickCut> list = Arrays.asList(
            cut("1", "Deploy", "kubectl apply -f prod.yaml"),
            cut("2", "เซิร์ฟเวอร์", "ssh root@host"));
        assertEquals(1, TerminowQuickCuts.search(list, "KUBECTL").size());
        assertEquals("1", TerminowQuickCuts.search(list, "deploy").get(0).id);
        assertEquals("2", TerminowQuickCuts.search(list, "เซิร์ฟ").get(0).id);
        assertEquals("empty query returns all", 2, TerminowQuickCuts.search(list, "").size());
    }

    @Test
    public void needsConfirmationForNewlinesAndControlCharsOnly() {
        assertFalse(TerminowQuickCuts.needsConfirmation("ls -la"));
        assertFalse("a tab is allowed without a prompt", TerminowQuickCuts.needsConfirmation("a\tb"));
        assertTrue(TerminowQuickCuts.needsConfirmation("line1\nline2"));
        assertTrue(TerminowQuickCuts.needsConfirmation("line1\r\nline2"));
        assertTrue(TerminowQuickCuts.needsConfirmation("bell\u0007here"));
    }

    @Test
    public void previewFlattensNewlinesAndTruncates() {
        assertEquals("a⏎b", TerminowQuickCuts.preview("a\nb", 40));
        String preview = TerminowQuickCuts.preview("0123456789", 5);
        assertEquals("01234…", preview);
    }

    @Test
    public void toJsonHandlesEmptyList() {
        assertEquals("[]", TerminowQuickCuts.toJson(new ArrayList<>()));
    }
}
