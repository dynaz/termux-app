package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** The saved tabs, their previous output, and the commands that attach to tmux on a server. */
public class TerminowWorkspaceTest {

    @Test
    public void tabsKeepTheirOrderNamesFoldersAndTheActiveOne() {
        TerminowWorkspace.State state = new TerminowWorkspace.State();
        TerminowWorkspace.Tab local = new TerminowWorkspace.Tab();
        local.id = "aaaaaaaaaaaa";
        local.title = "build";
        local.folder = "/data/data/com.terminow/files/home/project";
        local.localTmux = "local1";
        TerminowWorkspace.Tab host = new TerminowWorkspace.Tab();
        host.id = "bbbbbbbbbbbb";
        host.kind = TerminowWorkspace.KIND_HOST;
        host.hostKey = "root@web.example.com:22";
        host.vault = "team1";
        host.title = "web";
        TerminowWorkspace.Tab linux = new TerminowWorkspace.Tab();
        linux.id = "cccccccccccc";
        linux.kind = TerminowWorkspace.KIND_LINUX;
        linux.environment = "ubuntu";
        linux.user = "dev";
        linux.folder = "/home/dev/app";
        state.tabs.add(local);
        state.tabs.add(host);
        state.tabs.add(linux);
        state.activeId = "bbbbbbbbbbbb";
        state.saved = 1234;

        TerminowWorkspace.State read = TerminowWorkspace.State.fromJson(state.toJson());
        assertEquals(3, read.tabs.size());
        assertEquals("aaaaaaaaaaaa", read.tabs.get(0).id);
        assertEquals("build", read.tabs.get(0).title);
        assertEquals("/data/data/com.terminow/files/home/project", read.tabs.get(0).folder);
        assertEquals("local1", read.tabs.get(0).localTmux);
        assertEquals(TerminowWorkspace.KIND_HOST, read.tabs.get(1).kind);
        assertEquals("root@web.example.com:22", read.tabs.get(1).hostKey);
        assertEquals("team1", read.tabs.get(1).vault);
        assertEquals("terminow-bbbbbbbbbbbb", read.tabs.get(1).remoteTmuxName());
        assertEquals("dev", read.tabs.get(2).user);
        assertEquals("bbbbbbbbbbbb", read.activeId);
        assertEquals(1234, read.saved);
    }

    @Test
    public void damagedOrUnknownDataIsLeftOut() {
        assertTrue(TerminowWorkspace.State.fromJson("not json").tabs.isEmpty());
        assertTrue(TerminowWorkspace.State.fromJson("{\"version\":2,\"tabs\":[{\"id\":\"aaaaaaaaaaaa\"}]}").tabs.isEmpty());
        TerminowWorkspace.State state = TerminowWorkspace.State.fromJson("{\"version\":1,\"tabs\":[" +
            "{\"id\":\"../../etc\"},{\"id\":\"aaaaaaaaaaaa\",\"kind\":\"rocket\"},{\"id\":\"bbbbbbbbbbbb\"},{\"id\":\"bbbbbbbbbbbb\"}]}");
        assertEquals(1, state.tabs.size());
        assertEquals("bbbbbbbbbbbb", state.tabs.get(0).id);
        assertNull(state.find("aaaaaaaaaaaa"));
        assertTrue(TerminowWorkspace.isValidId(TerminowWorkspace.newId()));
        assertFalse(TerminowWorkspace.isValidId("ABC"));
    }

    @Test
    public void previousOutputIsOnlyWhatCameAfterTheLastRestore() {
        String header = TerminowWorkspace.MARK + "── Previous output ──";
        String footer = TerminowWorkspace.MARK + "── End of previous output ──";
        String transcript = header + "\nold line\n" + footer + "\n$ ls\nfile\n\n\n";
        assertEquals("$ ls\nfile", TerminowWorkspace.trimOutput(transcript));
        assertEquals("$ ls\nfile\n\n\n", TerminowWorkspace.afterMark(transcript));
        assertEquals("plain", TerminowWorkspace.afterMark("plain"));
        // An error before a reconnect is not taken as a new one
        String reconnect = "Connection refused\n" + TerminowWorkspace.MARK + "── Reconnecting to web ──\nwelcome";
        assertEquals("welcome", TerminowWorkspace.afterMark(reconnect));
    }

    @Test
    public void previousOutputKeepsTheLastLinesOnly() {
        StringBuilder transcript = new StringBuilder();
        for (int i = 0; i < TerminowWorkspace.OUTPUT_MAX_LINES + 500; i++) transcript.append("line ").append(i).append('\n');
        String output = TerminowWorkspace.trimOutput(transcript.toString());
        assertEquals(TerminowWorkspace.OUTPUT_MAX_LINES, output.split("\n").length);
        assertTrue(output.endsWith("line " + (TerminowWorkspace.OUTPUT_MAX_LINES + 499)));
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < 1000; i++) wide.append("x".repeat(400)).append('\n');
        assertTrue(TerminowWorkspace.trimOutput(wide.toString()).length() <= TerminowWorkspace.OUTPUT_MAX_CHARS);
    }

    @Test
    public void previousOutputIsLabelledAndCanNotControlTheTerminal() {
        String saved = "ok\n\u001b]52;c;ZXZpbA==\u0007\u001b[2J\u0005done";
        String text = new String(TerminowWorkspace.prelude(saved, "Previous output", "New shell"), StandardCharsets.UTF_8);
        assertTrue(text.contains(TerminowWorkspace.MARK + "── Previous output ──"));
        assertTrue(text.contains(TerminowWorkspace.MARK + "── New shell ──"));
        // Only the escapes of TermiNow itself, for dim and bold: none of the saved text
        String withoutOwn = text.replace("\u001b[2m", "").replace("\u001b[1;2m", "").replace("\u001b[0m", "");
        assertFalse(withoutOwn.contains("\u001b"));
        assertFalse(withoutOwn.contains("\u0007"));
        assertFalse(withoutOwn.contains("\u0005"));
        assertTrue(withoutOwn.contains("ok\r\n"));
        // The markers are lines that later checks of the output start after
        assertEquals("", TerminowWorkspace.afterMark(withoutOwn.replace("\r", "")).trim());
    }

    @Test
    public void hostsAttachToTheTmuxSessionOfTheTabOnlyWhenTheyKeepOne() {
        TerminowStore.Host host = new TerminowStore.Host();
        host.address = "web.example.com";
        host.username = "root";
        String name = "terminow-abcdef123456";
        assertEquals("ssh root@web.example.com", host.getCommand(null, "", 0, 3, name).replaceAll(" +", " ").trim());

        host.remoteTmux = true;
        String ssh = host.getCommand(null, "", 0, 3, name);
        assertTrue(ssh, ssh.startsWith("ssh -t "));
        assertTrue(ssh, ssh.contains("tmux has-session -t =" + name));
        assertTrue(ssh, ssh.contains("exec tmux attach-session -t =" + name));
        assertTrue(ssh, ssh.contains("exec tmux new-session -s " + name));
        // Attaching is tried before creating
        assertTrue(ssh.indexOf("attach-session") < ssh.indexOf("new-session"));
        // Never another name, like one with shell characters
        assertEquals("ssh root@web.example.com", host.getCommand(null, "", 0, 3, "terminow-x;rm -rf ~").replaceAll(" +", " ").trim());

        host.mosh = true;
        String mosh = host.getCommand(null, "", 0, 3, name);
        assertTrue(mosh, mosh.contains(" -- sh -c "));
        host.mosh = false;
        host.telnet = true;
        assertFalse(host.getCommand(null, "", 0, 3, name).contains("tmux"));
    }

    @Test
    public void installingTmuxIsOneCommandForTheShellOfTheServer() {
        String command = TerminowStore.installRemoteTmuxCommand("terminow-abcdef123456");
        assertTrue(command.startsWith("sh -c '"));
        assertTrue(command.contains("apt-get install -y tmux"));
        assertTrue(command.contains("exec tmux new-session -A -s terminow-abcdef123456"));
        assertFalse(TerminowStore.remoteTmuxScript("terminow-abcdef123456").contains("'"));
    }
}
