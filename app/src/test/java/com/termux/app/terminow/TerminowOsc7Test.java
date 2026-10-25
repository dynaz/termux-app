package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.termux.app.terminow.TerminowOsc7.Location;

import org.junit.Test;

/**
 * Parsing of the OSC 7 working-directory report: host and percent-decoded path, with spaces, Unicode
 * and URI-encoding handled, and malformed input returning null instead of a guess.
 */
public class TerminowOsc7Test {

    @Test public void parsesHostAndPath() {
        Location loc = TerminowOsc7.parse("file://myhost/home/user");
        assertEquals("myhost", loc.host);
        assertEquals("/home/user", loc.path);
    }

    @Test public void parsesEmptyHost() {
        Location loc = TerminowOsc7.parse("file:///var/log");
        assertEquals("", loc.host);
        assertEquals("/var/log", loc.path);
    }

    @Test public void decodesSpaces() {
        assertEquals("/home/my project", TerminowOsc7.parse("file:///home/my%20project").path);
    }

    @Test public void decodesUtf8Unicode() {
        // "/โฟลเดอร์" percent-encoded as UTF-8.
        Location loc = TerminowOsc7.parse("file:///%E0%B9%82%E0%B8%9F%E0%B8%A5%E0%B9%80%E0%B8%94%E0%B8%AD%E0%B8%A3%E0%B9%8C");
        assertEquals("/โฟลเดอร์", loc.path);
    }

    @Test public void keepsAlreadyLiteralUnicode() {
        assertEquals("/home/ไทย", TerminowOsc7.parse("file:///home/ไทย").path);
    }

    @Test public void tolersatesStrayPercent() {
        // A '%' not followed by two hex digits is kept literally, not dropped.
        assertEquals("/home/50%done", TerminowOsc7.parse("file:///home/50%done").path);
        assertEquals("/a%", TerminowOsc7.parse("file:///a%").path);
    }

    @Test public void keepsTrailingSlash() {
        assertEquals("/home/user/", TerminowOsc7.parse("file:///home/user/").path);
    }

    @Test public void rejectsNonFileOrMalformed() {
        assertNull(TerminowOsc7.parse(null));
        assertNull(TerminowOsc7.parse(""));
        assertNull(TerminowOsc7.parse("/home/user"));          // no scheme
        assertNull(TerminowOsc7.parse("http://host/path"));    // not file
        assertNull(TerminowOsc7.parse("file://hostonly"));     // no path part
        assertNull(TerminowOsc7.parse("file://"));             // nothing
    }

    @Test public void schemeIsCaseInsensitive() {
        assertEquals("/x", TerminowOsc7.parse("FILE:///x").path);
    }
}
