package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** The profile model, secret handling, the environments and the setup commands. */
public class TerminowMcpModelTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    @Test
    public void namesAndHeaders() {
        assertTrue(TerminowMcpProfile.isValidName("github-tools_2"));
        assertFalse(TerminowMcpProfile.isValidName(""));
        assertFalse(TerminowMcpProfile.isValidName("my server"));
        assertFalse(TerminowMcpProfile.isValidName("a;rm"));
        assertNull(TerminowMcpProfile.validateHeader("X-Api-Key", "abc123"));
        assertNotNull(TerminowMcpProfile.validateHeader("X Api", "abc"));
        assertNotNull(TerminowMcpProfile.validateHeader("X-Api-Key", "abc\r\nInjected: 1"));
        assertNotNull(TerminowMcpProfile.validateHeader("Mcp-Session-Id", "x"));
        assertNotNull(TerminowMcpProfile.validateHeader("Content-Type", "text/plain"));
        assertNotNull(TerminowMcpProfile.validateHeader("X-Api-Key", " "));
    }

    @Test
    public void duplicateNamesAreFound() {
        TerminowMcpProfile a = new TerminowMcpProfile();
        a.name = "GitHub";
        TerminowMcpProfile b = new TerminowMcpProfile();
        b.name = "linear";
        List<TerminowMcpProfile> profiles = Arrays.asList(a, b);
        assertTrue(TerminowMcpProfile.isNameTaken(profiles, "github", null));
        assertTrue(TerminowMcpProfile.isNameTaken(profiles, "LINEAR", a.id));
        // Its own name, when a profile is edited
        assertFalse(TerminowMcpProfile.isNameTaken(profiles, "github", a.id));
        assertFalse(TerminowMcpProfile.isNameTaken(profiles, "sentry", null));
    }

    @Test
    public void jsonHasNoSecrets() throws Exception {
        TerminowMcpProfile profile = new TerminowMcpProfile();
        profile.name = "example";
        profile.url = "https://mcp.example.com/mcp";
        profile.authType = TerminowMcpProfile.AUTH_HEADERS;
        profile.headers.add(new TerminowMcpProfile.Header("X-Api-Key", "header-secret-555"));
        profile.bearerToken = "bearer-secret-444";
        TerminowMcpProfile.Applied applied = new TerminowMcpProfile.Applied();
        applied.scope = "user";
        applied.enabled = true;
        profile.applied.put(profile.getTargetKey(), applied);

        String json = profile.toJson().toString();
        assertFalse(json.contains("header-secret-555"));
        assertFalse(json.contains("bearer-secret-444"));

        TerminowMcpProfile loaded = TerminowMcpProfile.fromJson(new JSONObject(json));
        assertEquals("example", loaded.name);
        assertEquals("X-Api-Key", loaded.headers.get(0).name);
        assertEquals("", loaded.headers.get(0).value);
        assertTrue(loaded.getApplied().enabled);
    }

    @Test
    public void stdioProfilesKeepEnvValuesSecret() throws Exception {
        TerminowMcpProfile profile = new TerminowMcpProfile();
        profile.name = "everything";
        profile.transport = TerminowMcpProfile.TRANSPORT_STDIO;
        profile.command = "npx";
        profile.args.addAll(Arrays.asList("-y", "@modelcontextprotocol/server-everything"));
        profile.env.put("API_KEY", "env-secret-31337");
        profile.env.put("REGION", "eu-west-1");
        assertTrue(profile.isStdio());
        assertFalse(profile.isHttp());
        assertTrue(profile.getSecrets().contains("env-secret-31337"));

        String json = profile.toJson().toString();
        assertFalse(json.contains("env-secret-31337"));
        assertFalse(json.contains("eu-west-1"));
        TerminowMcpProfile loaded = TerminowMcpProfile.fromJson(new JSONObject(json));
        assertEquals(Arrays.asList("API_KEY", "REGION"), new java.util.ArrayList<>(loaded.env.keySet()));
        assertEquals("", loaded.env.get("API_KEY"));

        // The store keeps them encrypted, in the order of the names
        TerminowStore.sTestsWithoutKeystore = true;
        MemoryPreferences preferences = new MemoryPreferences();
        new TerminowStore(preferences).setMcpProfiles(Arrays.asList(profile));
        TerminowMcpProfile stored = new TerminowStore(preferences).getMcpProfiles().get(0);
        assertEquals("env-secret-31337", stored.env.get("API_KEY"));
        assertEquals("eu-west-1", stored.env.get("REGION"));
        assertEquals(Arrays.asList("-y", "@modelcontextprotocol/server-everything"), stored.args);
    }

    @Test
    public void stdioValidation() {
        assertNull(TerminowMcpProfile.validateCommand("npx"));
        assertNull(TerminowMcpProfile.validateCommand("/usr/bin/python3"));
        assertNotNull(TerminowMcpProfile.validateCommand(" "));
        assertNotNull(TerminowMcpProfile.validateCommand("npx\nrm"));
        assertNotNull(TerminowMcpProfile.validateCommand("A=1"));
        assertNull(TerminowMcpProfile.validateEnv("API_KEY", "x y=z"));
        assertNull(TerminowMcpProfile.validateEnv("_private2", ""));
        assertNotNull(TerminowMcpProfile.validateEnv("2FA", "x"));
        assertNotNull(TerminowMcpProfile.validateEnv("MY-KEY", "x"));
        assertNotNull(TerminowMcpProfile.validateEnv("KEY", "a\0b"));
        assertNull(TerminowMcpProfile.validateArgument("--flag=value with spaces"));
        assertNotNull(TerminowMcpProfile.validateArgument("a\0b"));
        assertNull(TerminowMcpProfile.validateWorkingDir(""));
        assertNull(TerminowMcpProfile.validateWorkingDir("/root/project"));
        assertNotNull(TerminowMcpProfile.validateWorkingDir("project"));
    }

    @Test
    public void toolsAreCachedWithoutTheTestOfAnotherServer() throws Exception {
        TerminowMcpProfile profile = new TerminowMcpProfile();
        com.termux.app.terminow.mcp.McpTool tool = com.termux.app.terminow.mcp.McpTool.parse(new JSONObject(
            "{\"name\":\"search\",\"description\":\"Find\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}," +
                "\"annotations\":{\"readOnlyHint\":true}}"));
        profile.setCachedTools(Arrays.asList(tool), 1234);
        TerminowMcpProfile loaded = TerminowMcpProfile.fromJson(profile.toJson());
        assertEquals(1, loaded.cachedTools.size());
        assertEquals("search", loaded.cachedTools.get(0).name);
        assertTrue(loaded.cachedTools.get(0).isReadOnly());
        assertTrue(loaded.cachedTools.get(0).getInputSchema().getJSONObject("properties").has("q"));
        assertEquals(1234, loaded.cachedToolsTime);
        loaded.forgetServer();
        assertTrue(loaded.cachedTools.isEmpty());
    }

    @Test
    public void stdioLaunchInTerminowAndInAContainer() {
        File prefix = new File("/data/data/com.terminow/files/usr");
        TerminowMcpProfile profile = new TerminowMcpProfile();
        profile.transport = TerminowMcpProfile.TRANSPORT_STDIO;
        profile.command = "npx";
        profile.args.addAll(Arrays.asList("-y", "server; rm -rf ~", "my dir"));
        profile.env.put("API_KEY", "k 1");

        TerminowMcpStdio.Launch local = TerminowMcpStdio.plan(prefix, profile);
        assertEquals(Arrays.asList("npx", "-y", "server; rm -rf ~", "my dir"), local.command);
        assertEquals("k 1", local.env.get("API_KEY"));
        assertEquals(null, local.workingDir);

        profile.target = "ubuntu";
        profile.containerUser = "dev";
        profile.workingDir = "/home/dev/project";
        TerminowMcpStdio.Launch ubuntu = TerminowMcpStdio.plan(prefix, profile);
        assertEquals(Arrays.asList("/data/data/com.terminow/files/usr/bin/proot-distro", "login", "ubuntu", "--user", "dev",
            "--work-dir", "/home/dev/project", "--", "/usr/bin/env", "API_KEY=k 1", "npx", "-y", "server; rm -rf ~", "my dir"), ubuntu.command);
        assertTrue(ubuntu.env.isEmpty());

        profile.target = "ubuntu; reboot";
        try {
            TerminowMcpStdio.plan(prefix, profile);
            org.junit.Assert.fail("An invalid container name was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected
        }
    }

    @Test
    public void commandsAreFoundInThePathOfTheTerminal() throws Exception {
        File bin = mFolder.newFolder("usr", "bin");
        File other = mFolder.newFolder("other");
        write(new File(bin, "npx"), "#!/bin/sh\n");
        write(new File(other, "npx"), "#!/bin/sh\n");
        String path = bin.getPath() + "::" + other.getPath();
        assertEquals(new File(bin, "npx").getPath(), TerminowClaudeSetup.resolveExecutable("npx", path, other));
        assertNull(TerminowClaudeSetup.resolveExecutable("uvx", path, other));
        assertNull(TerminowClaudeSetup.resolveExecutable("npx", null, other));
        assertEquals("/usr/bin/python3", TerminowClaudeSetup.resolveExecutable("/usr/bin/python3", path, other));
        assertEquals(new File(other, "bin/server").getPath(), TerminowClaudeSetup.resolveExecutable("bin/server", path, other));
    }

    @Test
    public void requestHeadersByAuthType() {
        TerminowMcpProfile profile = new TerminowMcpProfile();
        profile.bearerToken = "tok-1234";
        profile.headers.add(new TerminowMcpProfile.Header("X-Api-Key", "k-5678"));
        assertTrue(profile.getRequestHeaders().isEmpty());
        profile.authType = TerminowMcpProfile.AUTH_BEARER;
        assertEquals("Bearer tok-1234", profile.getRequestHeaders().get("Authorization"));
        assertEquals(1, profile.getRequestHeaders().size());
        profile.authType = TerminowMcpProfile.AUTH_HEADERS;
        assertEquals("k-5678", profile.getRequestHeaders().get("X-Api-Key"));
        assertNull(profile.getRequestHeaders().get("Authorization"));
    }

    @Test
    public void redaction() {
        TerminowMcpRedactor redactor = new TerminowMcpRedactor(Arrays.asList("s3cr3t-value-XYZ", "ab", null));
        String text = redactor.redact("failed with s3cr3t-value-XYZ, Authorization: Bearer abcdefghijkl and ?api_key=zzz999&x=1 ab");
        assertFalse(text, text.contains("s3cr3t-value-XYZ"));
        assertFalse(text, text.contains("abcdefghijkl"));
        assertFalse(text, text.contains("zzz999"));
        // Short values are not treated as secrets
        assertTrue(text, text.endsWith(" ab"));
        assertTrue(text.contains(TerminowMcpRedactor.REDACTED));
        // Like `claude mcp get` prints headers
        String output = new TerminowMcpRedactor(null).redact("    Headers:\n      Authorization: Bearer live_9f8e7d6c5b4a\n      X-Other: visible");
        assertFalse(output, output.contains("live_9f8e7d6c5b4a"));
        assertTrue(output.contains("X-Other: visible"));
        assertEquals("", TerminowMcpRedactor.mask(""));
        assertFalse(TerminowMcpRedactor.mask("abc").contains("abc"));
    }

    private static void write(File file, String text) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void environmentsAreSeparate() throws Exception {
        File files = mFolder.newFolder("files");
        File prefix = new File(files, "usr");
        File home = new File(files, "home");
        assertTrue(home.mkdirs());
        File containers = new File(prefix, "var/lib/proot-distro/containers");
        write(new File(containers, "ubuntu/rootfs/etc/os-release"), "NAME=\"Ubuntu\"\nID=ubuntu\nPRETTY_NAME=\"Ubuntu 24.04 LTS\"\n");
        write(new File(containers, "ubuntu/rootfs/etc/passwd"), "root:x:0:0:root:/root:/bin/bash\n" +
            "dev:x:1000:1000::/home/dev:/bin/bash\nnobody:x:65534:65534::/nonexistent:/usr/sbin/nologin\n");
        assertTrue(new File(containers, "ubuntu/rootfs/root").mkdirs());
        write(new File(prefix, "var/lib/proot-distro/installed-rootfs/debian/etc/os-release"), "ID=debian\nPRETTY_NAME=\"Debian 12\"\n");
        // A termux-type container has no os-release and is not offered
        assertTrue(new File(containers, "termux/rootfs").mkdirs());
        // An invalid name is skipped
        write(new File(containers, "bad name/rootfs/etc/os-release"), "ID=ubuntu\n");

        TerminowMcpTargets targets = new TerminowMcpTargets(prefix, home);
        List<TerminowMcpTargets.Container> list = targets.getContainers();
        assertEquals(2, list.size());
        assertEquals("ubuntu", list.get(0).name);
        assertTrue(list.get(0).isUbuntu);
        assertEquals("Ubuntu 24.04 LTS", list.get(0).label);
        assertEquals("debian", list.get(1).name);

        TerminowMcpTargets.Container ubuntu = list.get(0);
        assertEquals(Arrays.asList("root", "dev"), TerminowMcpTargets.getUsers(ubuntu));
        assertEquals("/home/dev", TerminowMcpTargets.getGuestHome(ubuntu, "dev"));
        assertEquals("/root", TerminowMcpTargets.getGuestHome(ubuntu, "root"));

        // The home of Ubuntu is inside its rootfs, never the home of TermiNow
        File ubuntuConfig = TerminowMcpTargets.resolveGuestPath(ubuntu, "/root/.claude.json");
        assertNotNull(ubuntuConfig);
        assertTrue(ubuntuConfig.getPath().startsWith(ubuntu.rootfs.getPath()));
        assertFalse(ubuntuConfig.getPath().startsWith(home.getPath()));

        // Paths that leave the rootfs
        assertNull(TerminowMcpTargets.resolveGuestPath(ubuntu, "/root/../../../home/.claude.json"));
        assertNull(TerminowMcpTargets.resolveGuestPath(ubuntu, "relative/path"));
        java.nio.file.Files.createSymbolicLink(new File(ubuntu.rootfs, "escape").toPath(), home.toPath());
        assertNull(TerminowMcpTargets.resolveGuestPath(ubuntu, "/escape/.claude.json"));

        // TermiNow paths must be in the data directory of the app
        assertNotNull(targets.resolveTerminowPath(new File(home, "project").getAbsolutePath()));
        assertNull(targets.resolveTerminowPath("/etc"));
        assertNull(targets.resolveTerminowPath(home.getAbsolutePath() + "/../../x"));
    }

    @Test
    public void setupCommandsKeepArgumentsSeparate() {
        File prefix = new File("/data/data/com.terminow/files/usr");
        List<String> local = TerminowClaudeSetup.buildCommand(prefix, "terminow", "root",
            TerminowClaudeSetup.VERIFY_SCRIPT, "/data/data/com.terminow/files/home/my project", "example");
        assertEquals("/data/data/com.terminow/files/usr/bin/bash", local.get(0));
        assertEquals("-c", local.get(1));
        assertEquals(TerminowClaudeSetup.VERIFY_SCRIPT, local.get(2));
        // The directory with a space and the name are their own arguments, not part of the script
        assertEquals("/data/data/com.terminow/files/home/my project", local.get(4));
        assertEquals("example", local.get(5));

        List<String> ubuntu = TerminowClaudeSetup.buildCommand(prefix, "ubuntu", "dev", TerminowClaudeSetup.DETECT_SCRIPT);
        assertEquals(Arrays.asList("/data/data/com.terminow/files/usr/bin/proot-distro", "login", "ubuntu", "--user", "dev",
            "--", "/bin/sh", "-c", TerminowClaudeSetup.DETECT_SCRIPT, "terminow-setup"), ubuntu);

        try {
            TerminowClaudeSetup.buildCommand(prefix, "ubuntu; rm -rf /", "root", "true");
            org.junit.Assert.fail("An invalid container name was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected
        }
    }

    @Test
    public void detectionOutput() {
        TerminowClaudeSetup.Detection detection = new TerminowClaudeSetup.Detection();
        TerminowClaudeSetup.parseDetection(detection, "welcome banner\nTERMINOW_HOME=/root\nTERMINOW_CONFIG_DIR=\n" +
            "TERMINOW_CLAUDE=/root/.local/bin/claude\nTERMINOW_VERSION=2.1.296 (Claude Code)\nTERMINOW_HTTP=yes\nTERMINOW_SCOPE=yes\n");
        assertTrue(detection.ran);
        assertTrue(detection.isInstalled());
        assertEquals("2.1.296 (Claude Code)", detection.version);
        assertEquals("/root", detection.getUserConfigDir());
        assertTrue(detection.supportsHttp);

        TerminowClaudeSetup.Detection missing = new TerminowClaudeSetup.Detection();
        TerminowClaudeSetup.parseDetection(missing, "TERMINOW_HOME=/home/dev\nTERMINOW_CONFIG_DIR=/home/dev/.cfg\nTERMINOW_CLAUDE=\n");
        assertTrue(missing.ran);
        assertFalse(missing.isInstalled());
        assertEquals("/home/dev/.cfg", missing.getUserConfigDir());
    }

    @Test
    public void runningClaudeIsRecognized() {
        assertTrue(TerminowClaudeSetup.isClaudeCmdline("claude\0--resume\0"));
        assertTrue(TerminowClaudeSetup.isClaudeCmdline("node\0/data/data/com.terminow/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js\0"));
        assertTrue(TerminowClaudeSetup.isClaudeCmdline("/root/.local/share/claude/versions/2.1.296\0"));
        assertFalse(TerminowClaudeSetup.isClaudeCmdline("bash\0-l\0"));
        assertFalse(TerminowClaudeSetup.isClaudeCmdline("vim\0claude-notes.md\0"));
        assertFalse(TerminowClaudeSetup.isClaudeCmdline("/usr/bin/bash\0-c\0script\0terminow-setup\0"));
        assertFalse(TerminowClaudeSetup.isClaudeCmdline(null));
    }

}
