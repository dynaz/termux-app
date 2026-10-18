package com.termux.app.terminow;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Backing up an environment to a host: what is accepted, and the commands, run with stand-ins for proot-distro, ssh and rsync. */
public class TerminowBackupTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void setUp() {
        TerminowStore.sTestsWithoutKeystore = true;
    }

    @After
    public void tearDown() {
        TerminowStore.sTestsWithoutKeystore = false;
    }

    @Test
    public void onlyFoldersThatCanNotRunAsCommands() {
        assertTrue(TerminowBackup.isValidRemoteDir("terminow-backups/ubuntu"));
        assertTrue(TerminowBackup.isValidRemoteDir("~/backups/"));
        assertTrue(TerminowBackup.isValidRemoteDir("/srv/backups"));
        assertFalse(TerminowBackup.isValidRemoteDir("backups; rm -rf ~"));
        assertFalse(TerminowBackup.isValidRemoteDir("my backups"));
        assertFalse(TerminowBackup.isValidRemoteDir("../etc"));
        assertFalse(TerminowBackup.isValidRemoteDir("-rf"));
        assertFalse(TerminowBackup.isValidRemoteDir("$(id)"));
        assertEquals(Arrays.asList("/root", "/home"), TerminowBackup.parseFolders(" /root   /home "));
        assertNull(TerminowBackup.parseFolders("root"));
        assertNull(TerminowBackup.parseFolders("/root/../etc"));
        assertNull(TerminowBackup.parseFolders(""));
        assertEquals("ubuntu-20261018-1930.tar.gz", TerminowBackup.archiveName("ubuntu",
            new java.util.GregorianCalendar(2026, 9, 18, 19, 30).getTimeInMillis()));
    }

    private static TerminowStore.Host server() {
        TerminowStore.Host host = new TerminowStore.Host();
        host.address = "202.129.205.32";
        host.port = 2288;
        host.username = "root";
        host.keyPath = "~/.ssh/Zoom32";
        return host;
    }

    /** A prefix with proot-distro, ssh and rsync that run in folders of the test instead of an environment and a server. */
    private File standIns(File root, File serverHome, File log) throws Exception {
        File prefix = temp.newFolder("usr");
        File bin = new File(prefix, "bin");
        assertTrue(bin.mkdirs());
        // proot-distro login <alias> --user root -- /bin/sh -c SCRIPT ARGS: the script runs in the folder of the "environment"
        write(new File(bin, "proot-distro"), "#!/bin/bash\nwhile [ \"$1\" != -- ]; do shift; done; shift\n" +
            "sh=$1; shift; c=$1; shift; script=$1; shift\n" +
            "script=${script//cd \\/ /cd " + root + " }\n" +
            "PATH=" + bin + ":$PATH exec \"$sh\" \"$c\" \"$script\" \"$@\"\n");
        // ssh OPTIONS user@host REMOTE: the remote command runs in the home of the "server"
        write(new File(bin, "ssh"), "#!/bin/bash\necho \"ssh $*\" >> " + log + "\n" +
            "while [ $# -gt 1 ]; do case \"$1\" in *@*) shift; break;; -o|-i|-p) shift 2;; *) shift;; esac; done\n" +
            "cd " + serverHome + " && exec sh -c \"$1\"\n");
        // rsync: what it was given, one argument per line
        write(new File(bin, "rsync"), "#!/bin/bash\nprintf '%s\\n' \"$@\" > " + log + "\necho \"HOME=$HOME\" >> " + log + "\n");
        return prefix;
    }

    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setExecutable(true));
    }

    private static String run(String command) throws Exception {
        Process process = new ProcessBuilder("bash", "-c", command).redirectErrorStream(true).start();
        String out = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        return out;
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    @Test
    public void theWholeSystemArrivesAsOneArchiveThatImportReads() throws Exception {
        assumeTrue(new File("/bin/bash").exists() && (new File("/bin/tar").exists() || new File("/usr/bin/tar").exists()));
        File root = temp.newFolder("rootfs");
        assertTrue(new File(root, "etc").mkdirs() && new File(root, "bin").mkdirs() && new File(root, "proc").mkdirs());
        Files.write(new File(root, "etc/os-release").toPath(), "NAME=Ubuntu\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "bin/sh").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "proc/skip").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        File serverHome = temp.newFolder("server");
        File log = new File(temp.getRoot(), "log");
        File prefix = standIns(root, serverHome, log);

        String command = TerminowBackup.systemCommand(prefix.getPath(), "ubuntu", server().getTransport(null, "", 60, 10),
            "terminow-backups/ubuntu", "ubuntu-20261018-1930.tar.gz");
        String out = run(command);
        assertTrue(out, out.contains("TermiNow: backup done"));
        File archive = new File(serverHome, "terminow-backups/ubuntu/ubuntu-20261018-1930.tar.gz");
        assertTrue(out, archive.isFile());
        assertFalse(new File(archive.getPath() + ".part").exists());
        // What Import checks: the root of a Linux system at the top, without what is only there while it runs
        String names = run("tar -tzf " + archive);
        assertTrue(names, names.contains("./etc/os-release") && names.contains("./bin/sh"));
        assertFalse(names, names.contains("proc/skip"));
        // The ssh of TermiNow with the key, port and new host keys of the host
        String ssh = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
        // bash makes ~ the home, which is the one of TermiNow where it runs
        assertTrue(ssh, ssh.startsWith("ssh -i " + System.getenv("HOME") + "/.ssh/Zoom32 -p 2288 -o StrictHostKeyChecking=accept-new"));
    }

    @Test
    public void aFailedArchiveIsNotLeftLookingWhole() throws Exception {
        assumeTrue(new File("/bin/bash").exists());
        File serverHome = temp.newFolder("server");
        File log = new File(temp.getRoot(), "log");
        File prefix = standIns(new File(temp.getRoot(), "missing-root"), serverHome, log);
        String out = run(TerminowBackup.systemCommand(prefix.getPath(), "ubuntu", server().getTransport(null, "", 0, 0),
            "b", "ubuntu-x.tar.gz"));
        assertTrue(out, out.contains("TermiNow: backup failed"));
        assertFalse(new File(serverHome, "b/ubuntu-x.tar.gz").exists());
    }

    @Test
    public void rsyncSendsTheFoldersWithTheSshOfTermiNow() throws Exception {
        assumeTrue(new File("/bin/bash").exists());
        File log = new File(temp.getRoot(), "log");
        File prefix = standIns(temp.newFolder("rootfs"), temp.newFolder("server"), log);
        TerminowStore.Host jump = new TerminowStore.Host();
        jump.address = "bastion.example.com";
        jump.username = "admin";
        String[] transport = server().getTransport(jump, "", 0, 0);
        String out = run(TerminowBackup.foldersCommand(prefix.getPath(), "/data/data/com.terminow/files/home", "ubuntu", transport,
            "terminow-backups/ubuntu", Arrays.asList("/root", "/home"), false));
        assertTrue(out, out.contains("TermiNow: backup done"));
        List<String> args = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);
        assertTrue(args.toString(), args.contains("-aH") && args.contains("--relative"));
        // One argument for -e, which rsync splits itself; the jump host goes through the ssh of TermiNow too
        int e = args.indexOf("-e");
        assertTrue(args.get(e + 1), args.get(e + 1).startsWith(prefix + "/bin/ssh -i ~/.ssh/Zoom32 -p 2288 "));
        assertTrue(args.get(e + 1), args.get(e + 1).contains("'ProxyCommand=" + prefix + "/bin/ssh -o StrictHostKeyChecking=accept-new -W %h:%p admin@bastion.example.com'"));
        assertTrue(args.contains("--rsync-path=mkdir -p terminow-backups/ubuntu && rsync"));
        assertEquals(Arrays.asList("/root", "/home", "root@202.129.205.32:terminow-backups/ubuntu/", "HOME=/data/data/com.terminow/files/home"),
            args.subList(args.size() - 4, args.size()));
    }

    @Test
    public void withoutRsyncItSaysSoUnlessItMayInstallIt() throws Exception {
        assumeTrue(new File("/bin/bash").exists());
        File log = new File(temp.getRoot(), "log");
        File prefix = standIns(temp.newFolder("rootfs"), temp.newFolder("server"), log);
        assertTrue(new File(prefix, "bin/rsync").delete());
        String command = TerminowBackup.foldersCommand(prefix.getPath(), "/h", "ubuntu", server().getTransport(null, "", 0, 0),
            "b", Arrays.asList("/root"), false);
        // Only rsync of the environment counts, not one that this machine has
        String out = run("PATH=/nonexistent:" + prefix + "/bin; " + command);
        assertTrue(out, out.contains("TermiNow: rsync is not installed in this environment"));
        assertTrue(out, out.contains("TermiNow: backup failed, exit code 127"));
        assertTrue(TerminowBackup.foldersCommand("/p", "/h", "ubuntu", server().getTransport(null, "", 0, 0), "b",
            Arrays.asList("/root"), true).contains("apt-get install -y rsync"));
    }
}
