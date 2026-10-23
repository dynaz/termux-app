package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;

import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TextStyle;

import org.junit.Test;

import java.io.StringReader;
import java.util.Properties;

/** Every theme of the theme dialog is taken by the terminal, with its own background. */
public class TerminowThemesTest {

    @Test
    public void everyThemeLoadsIntoTheTerminal() throws Exception {
        for (TerminowThemes.Theme theme : TerminowThemes.THEMES) {
            Properties properties = new Properties();
            properties.load(new StringReader(theme.getProperties()));
            TerminalColorScheme scheme = new TerminalColorScheme();
            // Throws for anything the terminal does not accept, which would leave the old colors
            scheme.updateWith(properties);
            assertEquals(theme.name, 0xFF000000 | theme.background, scheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]);
            assertEquals(theme.name, 0xFF000000 | theme.foreground, scheme.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND]);
            for (int i = 0; i < theme.colors.length; i++)
                assertEquals(theme.name + " color" + i, 0xFF000000 | theme.colors[i], scheme.mDefaultColors[i]);
        }
    }

}
