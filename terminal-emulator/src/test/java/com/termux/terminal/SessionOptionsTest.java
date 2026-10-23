package com.termux.terminal;

import junit.framework.TestCase;

/** The options of a session for its saved host: its own color theme and the Backspace key. */
public class SessionOptionsTest extends TestCase {

    public void testBackspaceSwapsDelAndCtrlH() {
        assertEquals("\u0008", KeyHandler.swapBackspace("\u007f"));
        assertEquals("\u007f", KeyHandler.swapBackspace("\u0008"));
        // Alt+Backspace keeps its escape prefix
        assertEquals("\033\u0008", KeyHandler.swapBackspace("\033\u007f"));
    }

    public void testOwnSchemeSurvivesAChangeOfTheGlobalOne() {
        int background = TextStyle.COLOR_INDEX_BACKGROUND;
        TerminalColorScheme own = new TerminalColorScheme();
        own.mDefaultColors[background] = 0xff123456;
        TerminalColors colors = new TerminalColors();
        colors.setScheme(own);
        assertEquals(0xff123456, colors.mCurrentColors[background]);

        int saved = TerminalColors.COLOR_SCHEME.mDefaultColors[background];
        try {
            // Like a new theme in the settings, which resets the colors of every session
            TerminalColors.COLOR_SCHEME.mDefaultColors[background] = 0xff654321;
            colors.reset();
            assertEquals(0xff123456, colors.mCurrentColors[background]);
            colors.reset(background);
            assertEquals(0xff123456, colors.mCurrentColors[background]);
            // Without its own theme, a session follows the settings
            colors.setScheme(null);
            assertEquals(0xff654321, colors.mCurrentColors[background]);
        } finally {
            TerminalColors.COLOR_SCHEME.mDefaultColors[background] = saved;
        }
    }

}
