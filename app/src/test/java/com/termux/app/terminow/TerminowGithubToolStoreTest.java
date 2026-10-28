package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Round-trip for the persisted GitHub-installed tools in {@link TerminowStore}: they survive, dedup by
 * environment+name (a re-install updates the pinned revision), and are scoped per environment and removable.
 */
public class TerminowGithubToolStoreTest {

    private static TerminowStore.GithubTool tool(String env, String name, String repo, String rev, String launch, String verify) {
        TerminowStore.GithubTool t = new TerminowStore.GithubTool();
        t.environmentKey = env;
        t.toolName = name;
        t.repo = repo;
        t.revision = rev;
        t.launchCommand = launch;
        t.verifyCommand = verify;
        return t;
    }

    @Test public void persistsAndReadsBackAllFields() {
        MemoryPreferences prefs = new MemoryPreferences();
        new TerminowStore(prefs).addGithubTool(tool("ubuntu", "ripgrep", "BurntSushi/ripgrep", "BurntSushi/ripgrep@a1b2c3d", "rg --version", "command -v rg"));

        // A brand-new store over the same prefs: this is what a restart looks like.
        List<TerminowStore.GithubTool> list = new TerminowStore(prefs).getGithubTools();
        assertEquals(1, list.size());
        TerminowStore.GithubTool t = list.get(0);
        assertEquals("ubuntu", t.environmentKey);
        assertEquals("ripgrep", t.toolName);
        assertEquals("BurntSushi/ripgrep", t.repo);
        assertEquals("BurntSushi/ripgrep@a1b2c3d", t.revision);
        assertEquals("rg --version", t.launchCommand);
        assertEquals("command -v rg", t.verifyCommand);
    }

    @Test public void reinstallDedupsAndUpdatesRevision() {
        MemoryPreferences prefs = new MemoryPreferences();
        TerminowStore store = new TerminowStore(prefs);
        store.addGithubTool(tool("ubuntu", "ripgrep", "BurntSushi/ripgrep", "BurntSushi/ripgrep@old1234", "rg", "command -v rg"));
        store.addGithubTool(tool("ubuntu", "ripgrep", "BurntSushi/ripgrep", "BurntSushi/ripgrep@new5678", "rg", "command -v rg"));

        List<TerminowStore.GithubTool> list = store.getGithubTools();
        assertEquals(1, list.size());
        assertEquals("BurntSushi/ripgrep@new5678", list.get(0).revision);
    }

    @Test public void sameNameInDifferentEnvironmentsCoexists() {
        MemoryPreferences prefs = new MemoryPreferences();
        TerminowStore store = new TerminowStore(prefs);
        store.addGithubTool(tool("ubuntu", "ripgrep", "BurntSushi/ripgrep", "r@1", "rg", "command -v rg"));
        store.addGithubTool(tool("kali", "ripgrep", "BurntSushi/ripgrep", "r@1", "rg", "command -v rg"));
        assertEquals(2, store.getGithubTools().size());
    }

    @Test public void removeTakesOnlyTheMatchingEnvironmentAndName() {
        MemoryPreferences prefs = new MemoryPreferences();
        TerminowStore store = new TerminowStore(prefs);
        store.addGithubTool(tool("ubuntu", "ripgrep", "BurntSushi/ripgrep", "r@1", "rg", "command -v rg"));
        store.addGithubTool(tool("kali", "ripgrep", "BurntSushi/ripgrep", "r@1", "rg", "command -v rg"));

        store.removeGithubTool("ubuntu", "ripgrep");
        List<TerminowStore.GithubTool> list = store.getGithubTools();
        assertEquals(1, list.size());
        assertEquals("kali", list.get(0).environmentKey);
    }

    @Test public void unknownRemoveIsANoOp() {
        MemoryPreferences prefs = new MemoryPreferences();
        TerminowStore store = new TerminowStore(prefs);
        store.addGithubTool(tool("ubuntu", "ripgrep", "BurntSushi/ripgrep", "r@1", "rg", "command -v rg"));
        store.removeGithubTool("ubuntu", "not-there");
        assertTrue(store.getGithubTools().size() == 1);
    }
}
