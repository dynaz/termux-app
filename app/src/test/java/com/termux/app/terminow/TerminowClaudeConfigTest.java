package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

public class TerminowClaudeConfigTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private static final String EXISTING = "{\n" +
        "  \"numStartups\": 12,\n" +
        "  \"oauthAccount\": {\"emailAddress\": \"someone@example.com\"},\n" +
        "  \"mcpServers\": {\n" +
        "    \"github\": {\"type\": \"http\", \"url\": \"https://api.githubcopilot.com/mcp/\"},\n" +
        "    \"local-tool\": {\"type\": \"stdio\", \"command\": \"npx\", \"args\": [\"-y\", \"some-server\"]}\n" +
        "  },\n" +
        "  \"projects\": {\"/root/app\": {\"allowedTools\": [\"Bash\"], \"mcpServers\": {\"db\": {\"type\": \"http\", \"url\": \"https://db.example/mcp\"}}}}\n" +
        "}\n";

    private static JSONObject entry(String url, String token) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        if (token != null) headers.put("Authorization", "Bearer " + token);
        return TerminowClaudeConfig.buildHttpEntry(url, headers);
    }

    @Test
    public void addKeepsEverythingElse() throws Exception {
        JSONObject e = entry("https://mcp.example.com/mcp", null);
        TerminowClaudeConfig.Plan plan = TerminowClaudeConfig.planAdd(EXISTING, "user", "", "example", e, null, false);
        assertEquals(TerminowClaudeConfig.Action.ADD, plan.action);
        assertEquals(java.util.Arrays.asList("github", "local-tool"), plan.otherServers);
        JSONObject root = new JSONObject(plan.newText);
        assertEquals(12, root.getInt("numStartups"));
        assertEquals("someone@example.com", root.getJSONObject("oauthAccount").getString("emailAddress"));
        JSONObject servers = root.getJSONObject("mcpServers");
        assertEquals("https://api.githubcopilot.com/mcp/", servers.getJSONObject("github").getString("url"));
        assertEquals("npx", servers.getJSONObject("local-tool").getString("command"));
        assertEquals("http", servers.getJSONObject("example").getString("type"));
        // The local servers of a project are not touched by the user scope
        assertEquals("https://db.example/mcp", root.getJSONObject("projects").getJSONObject("/root/app")
            .getJSONObject("mcpServers").getJSONObject("db").getString("url"));
        // URLs are written without escaped slashes
        assertTrue(plan.newText.contains("\"https://mcp.example.com/mcp\""));
    }

    @Test
    public void addToEmptyOrMissingFile() throws Exception {
        TerminowClaudeConfig.Plan plan = TerminowClaudeConfig.planAdd(null, "user", "", "example",
            entry("https://mcp.example.com/mcp", null), null, false);
        assertEquals(TerminowClaudeConfig.Action.ADD, plan.action);
        assertEquals("https://mcp.example.com/mcp", new JSONObject(plan.newText).getJSONObject("mcpServers")
            .getJSONObject("example").getString("url"));
    }

    @Test
    public void localScopeGoesUnderTheProject() throws Exception {
        TerminowClaudeConfig.Plan plan = TerminowClaudeConfig.planAdd(EXISTING, "local", "/root/app", "example",
            entry("https://mcp.example.com/mcp", null), null, false);
        assertEquals(TerminowClaudeConfig.Action.ADD, plan.action);
        JSONObject project = new JSONObject(plan.newText).getJSONObject("projects").getJSONObject("/root/app");
        assertTrue(project.getJSONObject("mcpServers").has("example"));
        assertTrue(project.getJSONObject("mcpServers").has("db"));
        assertEquals("Bash", project.getJSONArray("allowedTools").getString(0));
        assertFalse(new JSONObject(plan.newText).getJSONObject("mcpServers").has("example"));
        try {
            TerminowClaudeConfig.planAdd(EXISTING, "local", "", "example", entry("https://a.example/mcp", null), null, false);
            fail("The local scope needs a project");
        } catch (TerminowClaudeConfig.ConfigException expected) {
            // Expected
        }
    }

    @Test
    public void conflictsAreNeverReplacedSilently() throws Exception {
        JSONObject ours = entry("https://mcp.example.com/mcp", null);
        // An entry named github that TermiNow did not write
        TerminowClaudeConfig.Plan plan = TerminowClaudeConfig.planAdd(EXISTING, "user", "", "github", ours, null, false);
        assertEquals(TerminowClaudeConfig.Action.CONFLICT, plan.action);
        assertNull(plan.newText);
        assertNotNull(plan.existingEntry);
        // Only when the user confirms
        plan = TerminowClaudeConfig.planAdd(EXISTING, "user", "", "github", ours, null, true);
        assertEquals(TerminowClaudeConfig.Action.UPDATE, plan.action);
        assertEquals("https://mcp.example.com/mcp", new JSONObject(plan.newText).getJSONObject("mcpServers")
            .getJSONObject("github").getString("url"));
    }

    @Test
    public void updatesWhatTermiNowWroteAndDetectsOutsideChanges() throws Exception {
        JSONObject first = entry("https://mcp.example.com/mcp", "token-one-111");
        String text = TerminowClaudeConfig.planAdd(EXISTING, "user", "", "example", first, null, false).newText;
        String fingerprint = TerminowClaudeConfig.fingerprint(first);

        // The same entry again changes nothing
        assertEquals(TerminowClaudeConfig.Action.UNCHANGED,
            TerminowClaudeConfig.planAdd(text, "user", "", "example", first, fingerprint, false).action);

        // A new token for an entry that TermiNow wrote is an update
        JSONObject second = entry("https://mcp.example.com/mcp", "token-two-222");
        TerminowClaudeConfig.Plan plan = TerminowClaudeConfig.planAdd(text, "user", "", "example", second, fingerprint, false);
        assertEquals(TerminowClaudeConfig.Action.UPDATE, plan.action);

        // Someone else changed the entry since: a conflict
        String edited = text.replace("https://mcp.example.com/mcp", "https://mcp.example.com/other");
        assertEquals(TerminowClaudeConfig.Action.CONFLICT,
            TerminowClaudeConfig.planAdd(edited, "user", "", "example", second, fingerprint, false).action);
    }

    @Test
    public void fingerprintIgnoresKeyOrder() throws Exception {
        JSONObject a = new JSONObject("{\"type\":\"http\",\"url\":\"https://x.example/mcp\",\"headers\":{\"A\":\"1\",\"B\":\"2\"}}");
        JSONObject b = new JSONObject("{\"headers\":{\"B\":\"2\",\"A\":\"1\"},\"url\":\"https://x.example/mcp\",\"type\":\"http\"}");
        assertEquals(TerminowClaudeConfig.fingerprint(a), TerminowClaudeConfig.fingerprint(b));
        assertFalse(TerminowClaudeConfig.fingerprint(a).equals(TerminowClaudeConfig.fingerprint(
            new JSONObject("{\"type\":\"http\",\"url\":\"https://x.example/mcp\"}"))));
    }

    @Test
    public void removeOnlyOurEntry() throws Exception {
        JSONObject ours = entry("https://mcp.example.com/mcp", null);
        String text = TerminowClaudeConfig.planAdd(EXISTING, "user", "", "example", ours, null, false).newText;
        TerminowClaudeConfig.Plan plan = TerminowClaudeConfig.planRemove(text, "user", "", "example",
            TerminowClaudeConfig.fingerprint(ours), false);
        assertEquals(TerminowClaudeConfig.Action.REMOVE, plan.action);
        JSONObject servers = new JSONObject(plan.newText).getJSONObject("mcpServers");
        assertFalse(servers.has("example"));
        assertTrue(servers.has("github"));
        assertTrue(servers.has("local-tool"));

        // Not ours: a conflict, unless confirmed
        assertEquals(TerminowClaudeConfig.Action.CONFLICT,
            TerminowClaudeConfig.planRemove(EXISTING, "user", "", "github", TerminowClaudeConfig.fingerprint(ours), false).action);
        assertEquals(TerminowClaudeConfig.Action.NOT_PRESENT,
            TerminowClaudeConfig.planRemove(EXISTING, "user", "", "missing", null, false).action);
    }

    @Test
    public void invalidFilesAreLeftAlone() {
        for (String bad : new String[]{"{not json", "[1,2]", "{\"mcpServers\": 5}"}) {
            try {
                TerminowClaudeConfig.planAdd(bad, "user", "", "example", entry("https://a.example/mcp", null), null, false);
                fail("Accepted " + bad);
            } catch (Exception expected) {
                assertTrue(expected instanceof TerminowClaudeConfig.ConfigException);
            }
        }
    }

    @Test
    public void backupAndAtomicWrite() throws Exception {
        File dir = mFolder.newFolder("home");
        File config = new File(dir, ".claude.json");
        try (FileOutputStream out = new FileOutputStream(config)) {
            out.write(EXISTING.getBytes(StandardCharsets.UTF_8));
        }
        File backup = TerminowClaudeConfig.backup(config, new Date());
        assertNotNull(backup);
        assertEquals(EXISTING, TerminowClaudeConfig.read(backup));
        assertFalse(backup.canExecute());

        String text = TerminowClaudeConfig.planAdd(TerminowClaudeConfig.read(config), "user", "", "example",
            entry("https://mcp.example.com/mcp", "secret-token-777"), null, false).newText;
        TerminowClaudeConfig.writeAtomically(config, text);
        assertEquals(text, TerminowClaudeConfig.read(config));
        // Only the file itself and the backup, no temporary files are left
        String[] names = dir.list();
        assertNotNull(names);
        assertEquals(2, names.length);
        // Owner only, since the file may have a token in it
        java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
            java.nio.file.Files.getPosixFilePermissions(config.toPath());
        assertEquals(java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), permissions);

        // Only the newest backups are kept
        for (int i = 0; i < 8; i++) TerminowClaudeConfig.backup(config, new Date());
        File[] backups = dir.listFiles((d, n) -> n.contains(".terminow-backup-"));
        assertNotNull(backups);
        assertEquals(5, backups.length);
    }

    @Test
    public void symlinksAreRefused() throws Exception {
        File dir = mFolder.newFolder("home2");
        File real = new File(dir, "real.json");
        try (FileOutputStream out = new FileOutputStream(real)) {
            out.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        File link = new File(dir, ".claude.json");
        java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath());
        try {
            TerminowClaudeConfig.writeAtomically(link, "{}");
            fail("Wrote through a symlink");
        } catch (TerminowClaudeConfig.ConfigException expected) {
            assertTrue(expected.getMessage().contains("symlink"));
        }
    }

    @Test
    public void entryHasTheDocumentedShape() throws Exception {
        JSONObject e = entry("https://mcp.example.com/mcp", "abc12345");
        assertEquals("http", e.getString("type"));
        assertEquals("https://mcp.example.com/mcp", e.getString("url"));
        assertEquals("Bearer abc12345", e.getJSONObject("headers").getString("Authorization"));
        assertFalse(TerminowClaudeConfig.buildHttpEntry("https://a.example/mcp", Collections.emptyMap()).has("headers"));
    }

}
