package com.termux.app.terminow;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class TerminowLaunchChoiceTest {

    private static final TerminowLaunchChoice.Action ASK = TerminowLaunchChoice.Action.ASK;
    private static final TerminowLaunchChoice.Action RESUME = TerminowLaunchChoice.Action.RESUME;
    private static final TerminowLaunchChoice.Action NEW_TAB = TerminowLaunchChoice.Action.NEW_TAB;

    @Test
    public void ubuntuGoesBackOnlyToASessionThatRuns() {
        assertEquals(ASK, TerminowLaunchChoice.ubuntu(TerminowStore.UBUNTU_LAUNCH_ASK, true));
        assertEquals(RESUME, TerminowLaunchChoice.ubuntu(TerminowStore.UBUNTU_LAUNCH_RESUME, true));
        // The session ended: never offered as resumable, a new tab instead
        assertEquals(NEW_TAB, TerminowLaunchChoice.ubuntu(TerminowStore.UBUNTU_LAUNCH_RESUME, false));
        assertEquals(NEW_TAB, TerminowLaunchChoice.ubuntu(TerminowStore.UBUNTU_LAUNCH_NEW, true));
        assertEquals(TerminowLaunchChoice.Action.CURRENT_TAB, TerminowLaunchChoice.ubuntu(TerminowStore.UBUNTU_LAUNCH_CURRENT, false));
        assertEquals(ASK, TerminowLaunchChoice.ubuntu("something else", true));
    }

    @Test
    public void claudeContinuesOnlyWhenTheInstalledVersionCan() {
        assertEquals(ASK, TerminowLaunchChoice.claude(TerminowStore.CLAUDE_LAUNCH_ASK, true, true));
        assertEquals(RESUME, TerminowLaunchChoice.claude(TerminowStore.CLAUDE_LAUNCH_RESUME, true, false));
        assertEquals(NEW_TAB, TerminowLaunchChoice.claude(TerminowStore.CLAUDE_LAUNCH_RESUME, false, false));
        assertEquals(NEW_TAB, TerminowLaunchChoice.claude(TerminowStore.CLAUDE_LAUNCH_NEW, true, true));
        assertEquals(TerminowLaunchChoice.Action.CONTINUE, TerminowLaunchChoice.claude(TerminowStore.CLAUDE_LAUNCH_CONTINUE, false, true));
        // Remembered, but this Claude Code has no --continue: asked again
        assertEquals(ASK, TerminowLaunchChoice.claude(TerminowStore.CLAUDE_LAUNCH_CONTINUE, false, false));
    }

    @Test
    public void aFlagIsOnlyPassedWhenTheInstalledVersionHasIt() {
        assertArrayEquals(new String[]{"--continue"}, TerminowLaunchChoice.claudeArguments(TerminowLaunchChoice.Action.CONTINUE, true, false));
        assertNull(TerminowLaunchChoice.claudeArguments(TerminowLaunchChoice.Action.CONTINUE, false, true));
        assertArrayEquals(new String[]{"--resume"}, TerminowLaunchChoice.claudeArguments(TerminowLaunchChoice.Action.PICK_CONVERSATION, false, true));
        assertNull(TerminowLaunchChoice.claudeArguments(TerminowLaunchChoice.Action.PICK_CONVERSATION, true, false));
        assertArrayEquals(new String[0], TerminowLaunchChoice.claudeArguments(NEW_TAB, false, false));
        // Going back to a tab starts nothing
        assertNull(TerminowLaunchChoice.claudeArguments(RESUME, true, true));
    }

    @Test
    public void whatIsRememberedIsAChoiceThatCanBeMadeAgain() {
        assertEquals(TerminowStore.UBUNTU_LAUNCH_RESUME, TerminowLaunchChoice.ubuntuBehaviorOf(RESUME));
        assertEquals(TerminowStore.CLAUDE_LAUNCH_CONTINUE, TerminowLaunchChoice.claudeBehaviorOf(TerminowLaunchChoice.Action.CONTINUE));
        // Picking a conversation is chosen each time
        assertNull(TerminowLaunchChoice.claudeBehaviorOf(TerminowLaunchChoice.Action.PICK_CONVERSATION));
        assertNull(TerminowLaunchChoice.ubuntuBehaviorOf(ASK));
    }

    @Test
    public void aDoubleTapStartsOneTab() {
        assertFalse(TerminowLaunchChoice.isRepeatTap(0, 5000));
        assertTrue(TerminowLaunchChoice.isRepeatTap(5000, 5400));
        assertFalse(TerminowLaunchChoice.isRepeatTap(5000, 5000 + TerminowLaunchChoice.REPEAT_TAP_MS));
        // A clock that went back is no repeat
        assertFalse(TerminowLaunchChoice.isRepeatTap(5000, 4000));
    }

    @Test
    public void ubuntuAndClaudeRememberApartAndSettingsCanAskAgain() {
        TerminowStore.sTestsWithoutKeystore = true;
        TerminowStore store = new TerminowStore(new MemoryPreferences());
        assertEquals(TerminowStore.UBUNTU_LAUNCH_ASK, store.getUbuntuLaunchBehavior());
        assertEquals(TerminowStore.CLAUDE_LAUNCH_ASK, store.getClaudeLaunchBehavior());
        store.setUbuntuLaunchBehavior(TerminowStore.UBUNTU_LAUNCH_RESUME);
        assertEquals(TerminowStore.CLAUDE_LAUNCH_ASK, store.getClaudeLaunchBehavior());
        store.setClaudeLaunchBehavior(TerminowStore.CLAUDE_LAUNCH_CONTINUE);
        assertEquals(TerminowStore.UBUNTU_LAUNCH_RESUME, store.getUbuntuLaunchBehavior());
        assertEquals(TerminowStore.CLAUDE_LAUNCH_CONTINUE, store.getClaudeLaunchBehavior());
        store.setClaudeLaunchBehavior(TerminowStore.CLAUDE_LAUNCH_ASK);
        assertEquals(TerminowStore.CLAUDE_LAUNCH_ASK, store.getClaudeLaunchBehavior());
        assertEquals(TerminowStore.UBUNTU_LAUNCH_RESUME, store.getUbuntuLaunchBehavior());
    }

    @Test
    public void favoritesAreKeptInTheirOrder() {
        TerminowStore.sTestsWithoutKeystore = true;
        MemoryPreferences preferences = new MemoryPreferences();
        TerminowStore store = new TerminowStore(preferences);
        assertEquals(Collections.emptyList(), store.getClaudeFavorites());
        store.setClaudeFavorites(TerminowKeyBarModel.toggleFavorite(store.getClaudeFavorites(), "/compact"));
        store.setClaudeFavorites(TerminowKeyBarModel.toggleFavorite(store.getClaudeFavorites(), "/model"));
        assertEquals(Arrays.asList("/compact", "/model"), new TerminowStore(preferences).getClaudeFavorites());
        store.setClaudeFavorites(TerminowKeyBarModel.toggleFavorite(store.getClaudeFavorites(), "/compact"));
        assertEquals(Collections.singletonList("/model"), store.getClaudeFavorites());
        // What is no command is not kept
        store.setClaudeFavorites(Arrays.asList("/model", "rm -rf /", "/x\n/y"));
        assertEquals(Collections.singletonList("/model"), store.getClaudeFavorites());
    }
}
