package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TerminowKeyGroupsTest {

    @Test
    public void defaultsAreValidAndLabelledLikeTermius() {
        for (List<String> group : TerminowKeyGroups.defaults()) {
            assertTrue(group.size() <= TerminowKeyGroups.MAX_KEYS_PER_GROUP);
            for (String key : group) assertTrue(key, TerminowKeyGroups.isValidKey(key));
        }
        assertEquals("ctrl+B, %", TerminowKeyGroups.label("^B %"));
        assertEquals("ctrl+B, \"", TerminowKeyGroups.label("^B \""));
        assertEquals("^C", TerminowKeyGroups.label("^C"));
        assertEquals("shift+tab", TerminowKeyGroups.label("S-TAB"));
        assertEquals("pgUp", TerminowKeyGroups.label("PGUP"));
        assertEquals("←", TerminowKeyGroups.label("LEFT"));
        assertEquals("F12", TerminowKeyGroups.label("F12"));
        assertEquals("esc", TerminowKeyGroups.label("ESC"));
        assertEquals("ctrl", TerminowKeyGroups.label("CTRL"));
    }

    @Test
    public void controlCodes() {
        assertEquals(3, TerminowKeyGroups.controlCode('C'));
        assertEquals(3, TerminowKeyGroups.controlCode('c'));
        assertEquals(2, TerminowKeyGroups.controlCode('b'));
        assertEquals(27, TerminowKeyGroups.controlCode('['));
        assertEquals(31, TerminowKeyGroups.controlCode('_'));
        assertEquals(127, TerminowKeyGroups.controlCode('?'));
        assertEquals(-1, TerminowKeyGroups.controlCode('1'));
    }

    @Test
    public void validation() {
        assertTrue(TerminowKeyGroups.isValidKey("CTRL"));
        assertTrue(TerminowKeyGroups.isValidKey("CTRL c"));
        assertFalse(TerminowKeyGroups.isValidKey("c CTRL"));
        assertFalse(TerminowKeyGroups.isValidKey("^1"));
        assertFalse(TerminowKeyGroups.isValidKey(""));
        assertFalse(TerminowKeyGroups.isValidKey("a b c d e"));
        assertFalse(TerminowKeyGroups.isValidKey("averyveryverylongliteral"));

        StringBuilder error = new StringBuilder();
        assertEquals(Arrays.asList("^C", "^B %", "ESC"), TerminowKeyGroups.parseGroup("^C\n  ^B   %  \n\nESC\n", error));
        assertEquals(0, error.length());
        assertTrue(TerminowKeyGroups.parseGroup("^C\n^9\n", error).isEmpty());
        assertEquals("^9", error.toString());
    }

    @Test
    public void savedGroupsAreCheckedAgain() throws Exception {
        JSONArray saved = new JSONArray("[[\"^C\",\"^9\",\"ESC\"],[],\"x\",[\"UP\"]]");
        List<List<String>> groups = TerminowKeyGroups.fromJson(saved);
        assertEquals(2, groups.size());
        assertEquals(Arrays.asList("^C", "ESC"), groups.get(0));
        assertEquals(TerminowKeyGroups.defaults(), TerminowKeyGroups.fromJson(null));
        assertEquals(groups, TerminowKeyGroups.fromJson(TerminowKeyGroups.toJson(groups)));
    }

}
