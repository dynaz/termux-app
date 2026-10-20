package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TerminowWhatsNewTest {

    private final SharedPreferences mPrefs = new MemoryPreferences();

    @Test
    public void onlyTheFeaturesAfterTheVersionThatWasThereAreNew() {
        assertEquals(TerminowWhatsNew.Feature.values().length, TerminowWhatsNew.since(155, 196).size());
        assertTrue(TerminowWhatsNew.since(196, 196).isEmpty());
        assertTrue("not before the version that has them", TerminowWhatsNew.since(155, 195).isEmpty());
    }

    @Test
    public void aFreshInstallShowsNothingAndMarksNothing() {
        assertTrue(TerminowWhatsNew.onStart(mPrefs, 0, 196).isEmpty());
        for (TerminowWhatsNew.Feature feature : TerminowWhatsNew.Feature.values())
            assertFalse(feature.name(), TerminowWhatsNew.isNew(mPrefs, feature));
        assertTrue(TerminowWhatsNew.onStart(mPrefs, 196, 196).isEmpty());
        assertFalse("no window waits", mPrefs.contains("pending_from"));
    }

    @Test
    public void anUpdateAnnouncesTheFeaturesAndMarksThemUntilOpened() {
        List<TerminowWhatsNew.Feature> features = TerminowWhatsNew.onStart(mPrefs, 155, 196);
        assertEquals(Arrays.asList(TerminowWhatsNew.Feature.values()), features);
        assertEquals("the window waits for the home screen", 155, mPrefs.getInt("pending_from", 0));
        assertTrue(TerminowWhatsNew.isNew(mPrefs, TerminowWhatsNew.Feature.BACKUP));
        TerminowWhatsNew.seen(mPrefs, TerminowWhatsNew.Feature.BACKUP);
        assertFalse(TerminowWhatsNew.isNew(mPrefs, TerminowWhatsNew.Feature.BACKUP));
        assertTrue(TerminowWhatsNew.isNew(mPrefs, TerminowWhatsNew.Feature.DEBUG));
        // The mark stays through another update, from the version the user had first
        TerminowWhatsNew.onStart(mPrefs, 196, 197);
        assertTrue(TerminowWhatsNew.isNew(mPrefs, TerminowWhatsNew.Feature.DEBUG));
    }

    @Test
    public void dontShowAgainKeepsTheWindowAwayButNotTheMarks() {
        mPrefs.edit().putBoolean("off", true).apply();
        assertEquals(TerminowWhatsNew.Feature.values().length, TerminowWhatsNew.onStart(mPrefs, 155, 196).size());
        assertFalse(mPrefs.contains("pending_from"));
        assertTrue(TerminowWhatsNew.isNew(mPrefs, TerminowWhatsNew.Feature.FILES));
    }
}
