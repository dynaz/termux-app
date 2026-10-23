package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;

import android.app.ApplicationExitInfo;

import com.termux.R;

import org.junit.Test;

public class TerminowWhyClosedTest {

    @Test
    public void whatAndroidDidIsSaid() {
        assertEquals(R.string.terminow_closed_low_memory, TerminowHome.whyClosed(ApplicationExitInfo.REASON_LOW_MEMORY, 0));
        assertEquals(R.string.terminow_closed_killed, TerminowHome.whyClosed(ApplicationExitInfo.REASON_SIGNALED, 9));
        assertEquals(R.string.terminow_closed_crash, TerminowHome.whyClosed(ApplicationExitInfo.REASON_CRASH_NATIVE, 0));
        assertEquals(R.string.terminow_closed_frozen, TerminowHome.whyClosed(ApplicationExitInfo.REASON_FREEZER, 0));
    }

    @Test
    public void anExitOfItsOwnOrByTheUserIsNotSaid() {
        assertEquals(0, TerminowHome.whyClosed(ApplicationExitInfo.REASON_EXIT_SELF, 0));
        assertEquals(0, TerminowHome.whyClosed(ApplicationExitInfo.REASON_USER_REQUESTED, 0));
        assertEquals(0, TerminowHome.whyClosed(ApplicationExitInfo.REASON_SIGNALED, 15));
    }
}
