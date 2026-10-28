package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Schema + validation tests for the AI install plan (treated as untrusted). */
public class TerminowInstallPlanTest {

    private static final String READY =
        "{\"toolName\":\"demo\",\"description\":\"d\",\"compatibility\":\"ready\"," +
        "\"steps\":[{\"label\":\"Build\",\"command\":\"make\"}],\"verifyCommand\":\"demo --version\"," +
        "\"dependencies\":[\"git\"],\"sources\":[\"README\"],\"limitations\":[]}";

    @Test public void parsesAndIsInstallable() throws Exception {
        TerminowInstallPlan p = TerminowInstallPlan.parse(READY);
        assertEquals("demo", p.toolName);
        assertEquals(TerminowInstallPlan.Compatibility.READY, p.compatibility);
        assertEquals(1, p.steps.size());
        assertEquals("make", p.steps.get(0).command);
        assertEquals("demo --version", p.verifyCommand);
        assertEquals(1, p.dependencies.size());
        assertTrue(p.installable());
    }

    @Test public void extractsFromCodeFenceAndProse() throws Exception {
        TerminowInstallPlan p = TerminowInstallPlan.parse("Here is the plan:\n```json\n" + READY + "\n```\nThanks!");
        assertEquals("demo", p.toolName);
        assertTrue(p.installable());
    }

    @Test public void unsupportedIsNotInstallable() throws Exception {
        TerminowInstallPlan p = TerminowInstallPlan.parse(
            "{\"toolName\":\"x\",\"compatibility\":\"unsupported\",\"compatibilityReason\":\"needs a GUI\",\"steps\":[],\"verifyCommand\":\"\"}");
        assertEquals(TerminowInstallPlan.Compatibility.UNSUPPORTED, p.compatibility);
        assertFalse(p.installable());
    }

    @Test public void missingStepsOrVerifyNotInstallable() throws Exception {
        assertFalse(TerminowInstallPlan.parse(
            "{\"toolName\":\"x\",\"compatibility\":\"ready\",\"steps\":[],\"verifyCommand\":\"x -v\"}").installable());
        assertFalse(TerminowInstallPlan.parse(
            "{\"toolName\":\"x\",\"compatibility\":\"ready\",\"steps\":[{\"command\":\"make\"}],\"verifyCommand\":\"\"}").installable());
    }

    @Test public void emptyCommandStepsAreDropped() throws Exception {
        TerminowInstallPlan p = TerminowInstallPlan.parse(
            "{\"toolName\":\"x\",\"compatibility\":\"ready\",\"steps\":[{\"command\":\"  \"},{\"command\":\"go build\"}],\"verifyCommand\":\"x -v\"}");
        assertEquals(1, p.steps.size());
        assertEquals("go build", p.steps.get(0).command);
        assertEquals("Step 1", p.steps.get(0).label);
    }

    @Test public void rejectsMissingToolName() {
        try {
            TerminowInstallPlan.parse("{\"compatibility\":\"ready\",\"steps\":[{\"command\":\"make\"}],\"verifyCommand\":\"x -v\"}");
            fail("expected PlanException");
        } catch (TerminowInstallPlan.PlanException expected) { /* ok */ }
    }

    @Test public void rejectsNonJson() {
        try {
            TerminowInstallPlan.parse("I cannot help with that.");
            fail("expected PlanException");
        } catch (TerminowInstallPlan.PlanException expected) { /* ok */ }
    }

    @Test public void rejectsNullOrEmpty() {
        for (String s : new String[]{null, "", "   "}) {
            try { TerminowInstallPlan.parse(s); fail("expected PlanException"); }
            catch (TerminowInstallPlan.PlanException expected) { /* ok */ }
        }
    }

    @Test public void analyzeMessagesHasSystemAndUserWithRepo() throws Exception {
        TerminowGithubRepo.Repo repo = new TerminowGithubRepo.Repo("owner", "repo", "main", "abc1234def",
            "a tool", "https://github.com/owner/repo", "readme body", false,
            new java.util.ArrayList<>(), "v1.0", new java.util.ArrayList<>());
        org.json.JSONArray m = TerminowInstallPlan.analyzeMessages("aarch64 Ubuntu", "root", "apt", repo);
        assertEquals(2, m.length());
        assertEquals("system", m.getJSONObject(0).getString("role"));
        assertTrue(m.getJSONObject(0).getString("content").toLowerCase().contains("json"));
        assertEquals("user", m.getJSONObject(1).getString("role"));
        assertTrue(m.getJSONObject(1).getString("content").contains("owner/repo@abc1234"));
    }
}
