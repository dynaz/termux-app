package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminowBrowserTest {

    @Test
    public void onlyWebLinksAreOpened() {
        assertTrue(TerminowBrowser.isWebLink("https://github.com/"));
        assertTrue(TerminowBrowser.isWebLink("HTTP://example.com"));
        assertFalse(TerminowBrowser.isWebLink("intent://scan/#Intent;scheme=zxing;end"));
        assertFalse(TerminowBrowser.isWebLink("file:///sdcard/a"));
        assertEquals("com.android.chrome", TerminowBrowser.CHROME);
    }

    @Test
    public void aLongLinkInThePickerKeepsItsStartAndEnd() {
        String url = "https://example.com/" + "a".repeat(200) + "#end";
        String shown = TerminowBrowser.shorten(url, 60);
        assertEquals(60, shown.length());
        assertTrue(shown.startsWith("https://example.com/"));
        assertTrue(shown.endsWith("#end"));
        assertEquals("https://short", TerminowBrowser.shorten("https://short", 60));
    }
}
