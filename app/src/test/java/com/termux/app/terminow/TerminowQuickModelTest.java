package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** What Quick Launch shows, in which order, and how what was saved before it is taken over. */
public class TerminowQuickModelTest {

    private static TerminowQuickModel.Recent recent(String key, String name, long used, TerminowQuickModel.State state) {
        return new TerminowQuickModel.Recent(key, name, "ubuntu", "Ubuntu", state, used, name);
    }

    @Test
    public void idsAreStableAndNeverNames() {
        assertEquals(TerminowQuickModel.LOCAL, TerminowQuickModel.envId(TerminowMcpTargets.TARGET_TERMINOW));
        assertEquals("env:ubuntu", TerminowQuickModel.envId("ubuntu"));
        assertEquals("ubuntu", TerminowQuickModel.environmentKeyOf("env:ubuntu"));
        assertEquals(TerminowMcpTargets.TARGET_TERMINOW, TerminowQuickModel.environmentKeyOf(TerminowQuickModel.LOCAL));
        assertNull(TerminowQuickModel.environmentKeyOf("profile:1234"));
        assertEquals("1234", TerminowQuickModel.profileUuidOf("profile:1234"));
    }

    @Test
    public void pinnedFirstThenEnvironmentsAndOnlyPinnedProfiles() {
        List<String> envs = Arrays.asList("env:local", "env:ubuntu", "env:debian");
        List<String> profiles = Arrays.asList("profile:a", "profile:b");
        List<String> pins = Arrays.asList("profile:b", "env:debian", "env:gone");
        assertEquals(Arrays.asList("profile:b", "env:debian", "env:local", "env:ubuntu"),
            TerminowQuickModel.cards(envs, profiles, pins));
        // Without pins: every installed environment, no profile
        assertEquals(envs, TerminowQuickModel.cards(envs, profiles, Collections.emptyList()));
        List<String> toggled = TerminowQuickModel.togglePin(pins, "env:debian");
        assertFalse(toggled.contains("env:debian"));
        assertTrue(TerminowQuickModel.togglePin(toggled, "env:debian").contains("env:debian"));
        // A pin of an environment that is gone is kept for when it comes back
        assertTrue(toggled.contains("env:gone"));
    }

    @Test
    public void profilesOfBeforeQuickLaunchStayOneTapAway() {
        List<String> profiles = Arrays.asList("profile:a", "profile:b");
        assertEquals(profiles, TerminowQuickModel.migratePins(null, profiles));
        // Pins that were saved are not changed by a migration
        assertEquals(Collections.singletonList("env:ubuntu"),
            TerminowQuickModel.migratePins(Collections.singletonList("env:ubuntu"), profiles));
    }

    @Test
    public void theDefaultOfTheSettingsIsACard() {
        assertEquals(TerminowQuickModel.LOCAL, TerminowQuickModel.idOfDefault("local"));
        assertEquals("env:ubuntu", TerminowQuickModel.idOfDefault("ubuntu:ubuntu"));
        assertEquals("profile:uuid-1", TerminowQuickModel.idOfDefault("uuid-1"));
        for (String value : new String[]{"local", "ubuntu:debian2", "uuid-1"})
            assertEquals(value, TerminowQuickModel.defaultOfId(TerminowQuickModel.idOfDefault(value)));
    }

    @Test
    public void recentSessionsShowTwoRowsMostRecentFirst() {
        List<TerminowQuickModel.Recent> none = TerminowQuickModel.merge(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        assertTrue(TerminowQuickModel.visible(none).isEmpty());
        assertFalse(TerminowQuickModel.hasMore(none));

        List<TerminowQuickModel.Recent> one = TerminowQuickModel.merge(
            Collections.singletonList(recent("tab:1", "main", 10, TerminowQuickModel.State.OPEN)), new ArrayList<>(), new ArrayList<>());
        // One row, no empty placeholder row and no View all
        assertEquals(1, TerminowQuickModel.visible(one).size());
        assertFalse(TerminowQuickModel.hasMore(one));

        List<TerminowQuickModel.Recent> tabs = Arrays.asList(recent("tab:1", "old", 100, TerminowQuickModel.State.OPEN),
            recent("tab:2", "new", 300, TerminowQuickModel.State.OPEN));
        List<TerminowQuickModel.Recent> tmux = Arrays.asList(recent("tmux:a", "dev", 200, TerminowQuickModel.State.DETACHED),
            recent("tmux:b", "never", 0, TerminowQuickModel.State.DETACHED));
        List<TerminowQuickModel.Recent> all = TerminowQuickModel.merge(tabs, new ArrayList<>(), tmux);
        List<String> names = new ArrayList<>();
        for (TerminowQuickModel.Recent session : all) names.add(session.name);
        assertEquals(Arrays.asList("new", "dev", "old", "never"), names);
        assertEquals(2, TerminowQuickModel.visible(all).size());
        assertEquals("new", TerminowQuickModel.visible(all).get(0).name);
        assertTrue(TerminowQuickModel.hasMore(all));
    }

    @Test
    public void aTmuxSessionThatATabShowsIsThatTab() {
        List<TerminowQuickModel.Recent> tabs = Collections.singletonList(recent("tab:1", "Ubuntu · tmux dev", 100, TerminowQuickModel.State.OPEN));
        List<TerminowQuickModel.Recent> tmux = Arrays.asList(recent("tmux:dev", "dev", 50, TerminowQuickModel.State.ATTACHED),
            recent("tmux:other", "other", 40, TerminowQuickModel.State.DETACHED));
        List<TerminowQuickModel.Recent> all = TerminowQuickModel.merge(tabs, Collections.singletonList("ubuntu|dev"), tmux);
        assertEquals(2, all.size());
        assertEquals("tab:1", all.get(0).key);
        assertEquals("tmux:other", all.get(1).key);
    }

    @Test
    public void columnsComeFromTheWidth() {
        assertEquals(1, TerminowQuickModel.columns(360));
        assertEquals(1, TerminowQuickModel.columns(559));
        // A foldable open, or a phone in split screen next to it, by width only
        assertEquals(2, TerminowQuickModel.columns(673));
        assertEquals(3, TerminowQuickModel.columns(1200));
    }
}
