package com.termux.app.terminow;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** When a sideways drag of the terminal that was let go switches the session, or goes back. */
public class TerminowSessionSwipeTest {

    private static final int WIDTH = 1000;
    private static final float DENSITY = 2.5f;

    @Test
    public void farEnoughSwitchesASlowRelease() {
        assertTrue(TerminowSessionSwipe.shouldSwitch(-300, 0, WIDTH, DENSITY));
        assertTrue(TerminowSessionSwipe.shouldSwitch(300, 0, WIDTH, DENSITY));
        assertFalse(TerminowSessionSwipe.shouldSwitch(-150, 0, WIDTH, DENSITY));
        assertFalse(TerminowSessionSwipe.shouldSwitch(0, 5000, WIDTH, DENSITY));
    }

    @Test
    public void aFlickSwitchesAShortDragAndAFlickBackGoesBack() {
        float fast = TerminowSessionSwipe.VELOCITY_DP * DENSITY + 1;
        assertTrue(TerminowSessionSwipe.shouldSwitch(-60, -fast, WIDTH, DENSITY));
        assertTrue(TerminowSessionSwipe.shouldSwitch(60, fast, WIDTH, DENSITY));
        // Dragged far, then flicked back towards where it began
        assertFalse(TerminowSessionSwipe.shouldSwitch(-400, fast, WIDTH, DENSITY));
    }
}
