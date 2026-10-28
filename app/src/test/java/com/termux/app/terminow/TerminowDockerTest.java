package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class TerminowDockerTest {

    @Test
    public void escapeWrapsAndEscapesQuotes() {
        assertEquals("'web'", TerminowDocker.sqEscape("web"));
        assertEquals("'a b'", TerminowDocker.sqEscape("a b"));
        // a name with a single quote and metacharacters cannot break out of the quoting
        assertEquals("'x'\\''; rm -rf /'", TerminowDocker.sqEscape("x'; rm -rf /"));
    }

    @Test
    public void validRefRejectsMetacharacters() {
        assertTrue(TerminowDocker.isValidRef("my_container-1.2"));
        assertTrue(TerminowDocker.isValidRef("a1b2c3d4"));
        assertFalse(TerminowDocker.isValidRef("has space"));
        assertFalse(TerminowDocker.isValidRef("a;b"));
        assertFalse(TerminowDocker.isValidRef(""));
    }

    @Test
    public void psCommandIncludesAllAndFormat() {
        assertTrue(TerminowDocker.psCommand(false).startsWith("docker ps "));
        assertTrue(TerminowDocker.psCommand(true).contains("docker ps -a"));
        assertTrue(TerminowDocker.psCommand(true).contains("{{.ID}}"));
    }

    @Test
    public void parsePsReadsTabSeparatedRows() {
        String out = "abc123\tweb\tnginx:latest\tUp 3 hours\n"
            + "def456\tdb\tpostgres:16\tExited (0) 2 days ago\n"
            + "\n";   // blank line ignored
        List<TerminowDocker.Container> list = TerminowDocker.parsePs(out);
        assertEquals(2, list.size());
        assertEquals("abc123", list.get(0).id);
        assertEquals("web", list.get(0).name);
        assertEquals("nginx:latest", list.get(0).image);
        assertEquals("Up 3 hours", list.get(0).status);
        assertTrue(list.get(0).running());
        assertFalse(list.get(1).running());
    }

    @Test
    public void actionLogsExecAreEscaped() {
        assertEquals("docker restart 'web'", TerminowDocker.actionCommand("restart", "web"));
        assertEquals("docker stop 'a b'", TerminowDocker.actionCommand("stop", "a b"));
        assertEquals("docker logs --tail 200 'web'", TerminowDocker.logsCommand("web", 200));
        assertEquals("docker logs --tail 1 'web'", TerminowDocker.logsCommand("web", 0));   // clamped
        assertEquals("docker exec -it 'web' '/bin/sh'", TerminowDocker.execCommand("web", "/bin/sh"));
    }
}
