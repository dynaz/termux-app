package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Pure-logic tests for the GitHub URL parser used by the From GitHub install flow. */
public class TerminowGithubRepoTest {

    @Test public void parsesHttpsUrl() {
        TerminowGithubRepo.Parsed p = TerminowGithubRepo.parse("https://github.com/owner/repo");
        assertEquals("owner", p.owner);
        assertEquals("repo", p.repo);
        assertNull(p.ref);
    }

    @Test public void stripsDotGitAndTrailingSlash() {
        assertEquals("repo", TerminowGithubRepo.parse("https://github.com/owner/repo.git").repo);
        assertEquals("repo", TerminowGithubRepo.parse("https://github.com/owner/repo/").repo);
    }

    @Test public void readsTreeRef() {
        TerminowGithubRepo.Parsed p = TerminowGithubRepo.parse("https://github.com/owner/repo/tree/dev-branch");
        assertEquals("owner", p.owner);
        assertEquals("repo", p.repo);
        assertEquals("dev-branch", p.ref);
    }

    @Test public void readsCommitRef() {
        assertEquals("abc123", TerminowGithubRepo.parse("https://github.com/o/r/commit/abc123").ref);
    }

    @Test public void acceptsSshForm() {
        TerminowGithubRepo.Parsed p = TerminowGithubRepo.parse("git@github.com:owner/repo.git");
        assertEquals("owner", p.owner);
        assertEquals("repo", p.repo);
    }

    @Test public void acceptsBareOwnerRepo() {
        TerminowGithubRepo.Parsed p = TerminowGithubRepo.parse("owner/repo");
        assertEquals("owner", p.owner);
        assertEquals("repo", p.repo);
    }

    @Test public void ignoresQueryAndFragment() {
        TerminowGithubRepo.Parsed p = TerminowGithubRepo.parse("https://github.com/owner/repo?tab=readme#x");
        assertEquals("repo", p.repo);
    }

    @Test public void rejectsNonGithub() {
        assertNull(TerminowGithubRepo.parse("https://gitlab.com/owner/repo"));
        assertNull(TerminowGithubRepo.parse("https://example.com/owner/repo"));
    }

    @Test public void rejectsIncomplete() {
        assertNull(TerminowGithubRepo.parse("https://github.com/owner"));
        assertNull(TerminowGithubRepo.parse("https://github.com/"));
        assertNull(TerminowGithubRepo.parse(""));
        assertNull(TerminowGithubRepo.parse(null));
    }

    @Test public void rejectsBadNames() {
        assertNull(TerminowGithubRepo.parse("https://github.com/ow ner/repo"));
        assertNull(TerminowGithubRepo.parse("bare-no-slash"));
    }
}
