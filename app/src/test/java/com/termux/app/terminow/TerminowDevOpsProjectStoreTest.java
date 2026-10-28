package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Round-trip for persisted DevOps projects: they survive, dedup by provider+repo (linking a host updates
 * in place), coexist across repos, and are removed by provider+repo. Projects are config, not secrets,
 * so an expired entitlement must not drop them — this verifies the store keeps them regardless.
 */
public class TerminowDevOpsProjectStoreTest {

    private static TerminowStore.DevOpsProject project(String repo, String hostKey) {
        TerminowStore.DevOpsProject p = new TerminowStore.DevOpsProject();
        p.provider = "github";
        p.repo = repo;
        p.hostKey = hostKey;
        return p;
    }

    @Test public void persistsAndReadsBack() {
        MemoryPreferences prefs = new MemoryPreferences();
        new TerminowStore(prefs).addDevOpsProject(project("owner/api", "me@host:22"));

        List<TerminowStore.DevOpsProject> list = new TerminowStore(prefs).getDevOpsProjects();
        assertEquals(1, list.size());
        assertEquals("github", list.get(0).provider);
        assertEquals("owner/api", list.get(0).repo);
        assertEquals("me@host:22", list.get(0).hostKey);
    }

    @Test public void linkingAHostUpdatesInPlace() {
        MemoryPreferences prefs = new MemoryPreferences();
        TerminowStore store = new TerminowStore(prefs);
        store.addDevOpsProject(project("owner/api", ""));     // created, not linked
        store.addDevOpsProject(project("owner/api", "me@host:22")); // link host

        List<TerminowStore.DevOpsProject> list = store.getDevOpsProjects();
        assertEquals(1, list.size());
        assertEquals("me@host:22", list.get(0).hostKey);
    }

    @Test public void differentReposCoexist() {
        MemoryPreferences prefs = new MemoryPreferences();
        TerminowStore store = new TerminowStore(prefs);
        store.addDevOpsProject(project("owner/api", ""));
        store.addDevOpsProject(project("owner/web", ""));
        assertEquals(2, store.getDevOpsProjects().size());
    }

    @Test public void removeTakesOnlyThatProject() {
        MemoryPreferences prefs = new MemoryPreferences();
        TerminowStore store = new TerminowStore(prefs);
        store.addDevOpsProject(project("owner/api", ""));
        store.addDevOpsProject(project("owner/web", ""));
        store.removeDevOpsProject("github", "owner/api");
        List<TerminowStore.DevOpsProject> list = store.getDevOpsProjects();
        assertEquals(1, list.size());
        assertEquals("owner/web", list.get(0).repo);
    }

    @Test public void emptyRepoIgnored() {
        MemoryPreferences prefs = new MemoryPreferences();
        TerminowStore store = new TerminowStore(prefs);
        store.addDevOpsProject(project("", "me@host:22"));
        assertTrue(store.getDevOpsProjects().isEmpty());
    }
}
