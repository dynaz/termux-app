package com.termux.app.terminow.linux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminow.TerminowPremiumGate;
import com.termux.app.terminow.files.TerminowFs;
import com.termux.app.terminow.files.TerminowLocalFs;
import com.termux.app.terminow.files.TerminowTransfer;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Linux environments without a phone: environments are folders like the ones of proot-distro,
 * the file operations use java.nio instead of android.system.Os, and proot-distro is played by
 * small shell commands that do what its install and login do on disk.
 */
public class TerminowLinuxTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    /** The operations of {@link TerminowLinuxFileOps} with java.nio. */
    static class JvmOps implements TerminowLinuxFileOps {
        @Override
        public boolean isLink(@NonNull File file) {
            return Files.isSymbolicLink(file.toPath());
        }

        @NonNull
        @Override
        public String readLink(@NonNull File file) throws IOException {
            return Files.readSymbolicLink(file.toPath()).toString();
        }

        @Nullable
        @Override
        public Stat lstat(@NonNull File file) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Stat stat = new Stat();
                stat.link = attributes.isSymbolicLink();
                stat.directory = attributes.isDirectory();
                stat.regular = attributes.isRegularFile();
                stat.size = attributes.size();
                stat.modified = attributes.lastModifiedTime().toMillis();
                stat.allocated = stat.link ? 0 : attributes.size();
                if (!stat.link) {
                    int mode = 0;
                    for (PosixFilePermission permission : Files.getPosixFilePermissions(file.toPath())) mode |= 1 << (8 - permission.ordinal());
                    stat.mode = mode;
                }
                return stat;
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public void symlink(@NonNull String target, @NonNull File link) throws IOException {
            Files.createSymbolicLink(link.toPath(), new File(target).toPath());
        }

        @Override
        public void chmod(@NonNull File file, int mode) throws IOException {
            Set<PosixFilePermission> permissions = new HashSet<>();
            for (PosixFilePermission permission : PosixFilePermission.values())
                if ((mode & (1 << (8 - permission.ordinal()))) != 0) permissions.add(permission);
            Files.setPosixFilePermissions(file.toPath(), permissions);
        }
    }

    private final JvmOps mOps = new JvmOps();
    private JSONObject mSettings = new JSONObject();

    private final TerminowLinuxRegistry.Settings mSettingsStore = new TerminowLinuxRegistry.Settings() {
        @NonNull
        @Override
        public JSONObject load() {
            try {
                return new JSONObject(mSettings.toString());
            } catch (org.json.JSONException e) {
                return new JSONObject();
            }
        }

        @Override
        public void save(@NonNull JSONObject settings) {
            mSettings = settings;
        }
    };

    private File mPrefix;
    private File mHome;

    private TerminowLinuxRegistry registry() throws IOException {
        if (mPrefix == null) {
            mPrefix = mFolder.newFolder("files", "usr");
            mHome = mFolder.newFolder("files", "home");
        }
        return new TerminowLinuxRegistry(mPrefix, mHome, mSettingsStore, mOps, null);
    }

    private static void write(@NonNull File file, @NonNull String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) assertTrue(parent.mkdirs());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void link(@NonNull File link, @NonNull String target) throws IOException {
        File parent = link.getParentFile();
        if (parent != null && !parent.isDirectory()) assertTrue(parent.mkdirs());
        Files.createSymbolicLink(link.toPath(), new File(target).toPath());
    }

    /** A small aarch64 ELF header, enough for the architecture. */
    private static byte[] elf(int machine) {
        byte[] header = new byte[64];
        header[0] = 0x7f;
        header[1] = 'E';
        header[2] = 'L';
        header[3] = 'F';
        header[4] = 2;
        header[5] = 1;
        header[18] = (byte) machine;
        header[19] = (byte) (machine >> 8);
        return header;
    }

    /** A rootfs like Ubuntu's: os-release is a link into /usr/lib, /bin is a link to usr/bin. */
    private File makeRootfs(@NonNull File rootfs, @NonNull String id, @NonNull String name, @NonNull String version) throws IOException {
        write(new File(rootfs, "usr/lib/os-release"), "NAME=\"" + name + "\"\nVERSION_ID=\"" + version + "\"\nID=" + id +
            "\nPRETTY_NAME=\"" + name + " " + version + "\"\n");
        link(new File(rootfs, "etc/os-release"), "../usr/lib/os-release");
        File sh = new File(rootfs, "usr/bin/sh");
        assertTrue(sh.getParentFile().mkdirs() || sh.getParentFile().isDirectory());
        Files.write(sh.toPath(), elf(183));
        link(new File(rootfs, "bin"), "usr/bin");
        write(new File(rootfs, "etc/passwd"), "root:x:0:0:root:/root:/bin/bash\ndaemon:x:1:1::/usr/sbin:/usr/sbin/nologin\n" +
            "alice:x:1000:1000:,,,:/home/alice:/bin/bash\nnobody:x:65534:65534::/nonexistent:/usr/sbin/nologin\n");
        assertTrue(new File(rootfs, "root").mkdirs());
        assertTrue(new File(rootfs, "home/alice").mkdirs());
        assertTrue(new File(rootfs, "proc").mkdirs());
        return rootfs;
    }

    @NonNull
    private static List<String> tree(@NonNull File dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir.toPath())) {
            return stream.map(p -> {
                try {
                    return p + "|" + (Files.isSymbolicLink(p) ? "L" + Files.readSymbolicLink(p) : Files.size(p) + "|" +
                        Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis());
                } catch (IOException e) {
                    return p.toString();
                }
            }).sorted().collect(Collectors.toList());
        }
    }

    // -------------------------------------------------------------------------------------------
    // The registry
    // -------------------------------------------------------------------------------------------

    @Test
    public void existingUbuntuIsFoundWithoutChangingIt() throws Exception {
        TerminowLinuxRegistry registry = registry();
        File containers = registry.getContainersDir();
        File ubuntu = makeRootfs(new File(containers, "ubuntu/rootfs"), "ubuntu", "Ubuntu", "24.04");
        write(new File(containers, "ubuntu/manifest.json"), "{\"image_ref\":\"ubuntu\",\"arch\":\"aarch64\"}");
        // A legacy installation of older proot-distro
        makeRootfs(new File(registry.getRuntimeDir(), "installed-rootfs/debian"), "debian", "Debian GNU/Linux", "12");
        // A planted link instead of a container, which proot-distro refuses too
        File outside = makeRootfs(mFolder.newFolder("elsewhere"), "alpine", "Alpine", "3.20");
        link(new File(containers, "planted"), outside.getPath());
        // A container that an install left behind without a system
        assertTrue(new File(containers, "broken/rootfs/etc").mkdirs());
        List<String> before = tree(registry.getRuntimeDir());

        List<TerminowLinuxEnv> envs = registry.list();
        assertEquals(before, tree(registry.getRuntimeDir()));
        assertEquals(Arrays.asList("ubuntu", "debian", "broken"), envs.stream().map(e -> e.alias).collect(Collectors.toList()));
        TerminowLinuxEnv env = envs.get(0);
        assertEquals("proot:ubuntu", env.id);
        assertEquals(TerminowLinuxEnv.Status.INSTALLED, env.status);
        assertEquals("ubuntu", env.distroId);
        assertEquals("24.04", env.version);
        assertEquals("Ubuntu 24.04", env.getTitle());
        assertEquals("aarch64", env.arch);
        assertEquals(ubuntu, env.rootfs);
        assertEquals(Arrays.asList("root", "alice"), env.users.stream().map(u -> u.name).collect(Collectors.toList()));
        assertEquals("/home/alice", env.homeOf("alice"));
        assertEquals("root", env.defaultUser);
        assertEquals("apt", env.packageManager());

        TerminowLinuxEnv debian = envs.get(1);
        assertEquals("Debian", debian.getShortName());
        // No manifest: from the ELF header of /bin/sh, through the /bin link
        assertEquals("aarch64", debian.arch);
        assertEquals(TerminowLinuxEnv.Status.INCOMPLETE, envs.get(2).status);
        assertEquals(2, registry.installed().size());
    }

    @Test
    public void settingsAreKeptAndChecked() throws Exception {
        TerminowLinuxRegistry registry = registry();
        makeRootfs(new File(registry.getContainersDir(), "ubuntu/rootfs"), "ubuntu", "Ubuntu", "24.04");
        registry.setDefaultUser("ubuntu", "alice");
        registry.setDefaultFolder("ubuntu", "/home/alice");
        File projects = mFolder.newFolder("Projects");
        registry.setSharedFolders("ubuntu", Collections.singletonList(new TerminowLinuxEnv.SharedFolder(projects.getPath(), "/mnt/projects")));
        TerminowLinuxEnv env = registry.get("ubuntu");
        assertNotNull(env);
        assertEquals("alice", env.defaultUser);
        assertEquals("/home/alice", env.startFolder("alice"));
        assertEquals(Arrays.asList("--bind", projects.getPath() + ":/mnt/projects"), env.bindArguments());
        // A user that is gone falls back to root
        registry.setDefaultUser("ubuntu", "bob");
        assertEquals("root", registry.get("ubuntu").defaultUser);
        assertFalse(TerminowLinuxRegistry.isValidShare(projects.getPath(), "/usr/local"));
        assertFalse(TerminowLinuxRegistry.isValidShare(projects.getPath(), "/proc/x"));
        assertFalse(TerminowLinuxRegistry.isValidShare(projects.getPath(), "/"));
        assertFalse(TerminowLinuxRegistry.isValidShare("relative", "/mnt/x"));
        assertFalse(TerminowLinuxRegistry.isValidShare(projects.getPath() + ":/etc", "/mnt/x"));
        assertTrue(TerminowLinuxRegistry.isValidShare(projects.getPath(), "/mnt/x"));
    }

    @Test
    public void toolsAndStatusAreNeverSharedBetweenEnvironmentsOrUsers() {
        assertFalse(com.termux.app.terminow.TerminowTmux.key("ubuntu", "root").equals(com.termux.app.terminow.TerminowTmux.key("debian", "root")));
        assertFalse(com.termux.app.terminow.TerminowTmux.key("ubuntu", "root").equals(com.termux.app.terminow.TerminowTmux.key("ubuntu", "alice")));
    }

    // -------------------------------------------------------------------------------------------
    // Paths and mounts
    // -------------------------------------------------------------------------------------------

    @Test
    public void pathsAreResolvedLikeInTheEnvironment() throws Exception {
        File rootfs = makeRootfs(mFolder.newFolder("rootfs"), "ubuntu", "Ubuntu", "24.04");
        File phone = mFolder.newFolder("phone");
        assertTrue(new File(phone, "Download").mkdirs());
        File hostSecret = mFolder.newFile("host-secret");
        List<TerminowLinuxPaths.Mount> mounts = Collections.singletonList(new TerminowLinuxPaths.Mount("/sdcard", phone, false));
        // An absolute link means the environment's /etc, never the host's
        link(new File(rootfs, "root/shadow"), "/etc/passwd");
        // A link with ".." can not climb out of "/"
        link(new File(rootfs, "root/escape"), "../../../../../../" + hostSecret.getPath());
        link(new File(rootfs, "root/phone"), "/sdcard/Download");
        link(new File(rootfs, "root/a"), "b");
        link(new File(rootfs, "root/b"), "a");
        link(new File(rootfs, "root/self"), "/proc/self");

        TerminowLinuxPaths.Resolved shadow = TerminowLinuxPaths.resolve(rootfs, mounts, "/root/shadow", true, mOps);
        assertEquals("/etc/passwd", shadow.path);
        assertEquals(new File(rootfs, "etc/passwd"), shadow.file);
        TerminowLinuxPaths.Resolved escape = TerminowLinuxPaths.resolve(rootfs, mounts, "/root/escape", true, mOps);
        assertEquals(TerminowLinuxPaths.Kind.ROOTFS, escape.kind);
        assertTrue(escape.file.getPath().startsWith(rootfs.getPath() + "/"));
        TerminowLinuxPaths.Resolved phoneLink = TerminowLinuxPaths.resolve(rootfs, mounts, "/root/phone", true, mOps);
        assertEquals(TerminowLinuxPaths.Kind.MOUNT, phoneLink.kind);
        assertEquals(new File(phone, "Download"), phoneLink.file);
        // Not following the last link acts on the link itself
        assertEquals(new File(rootfs, "root/phone"), TerminowLinuxPaths.resolve(rootfs, mounts, "/root/phone", false, mOps).file);
        assertEquals(TerminowLinuxPaths.Kind.VIRTUAL, TerminowLinuxPaths.resolve(rootfs, mounts, "/root/self/fd", true, mOps).kind);
        assertEquals(TerminowLinuxPaths.Kind.VIRTUAL, TerminowLinuxPaths.resolve(rootfs, mounts, "/proc/cpuinfo", true, mOps).kind);
        assertEquals(new File(rootfs, "usr/bin/sh"), TerminowLinuxPaths.resolve(rootfs, mounts, "/bin/sh", true, mOps).file);
        assertEquals("/", TerminowLinuxPaths.resolve(rootfs, mounts, "/../../..", true, mOps).path);
        try {
            TerminowLinuxPaths.resolve(rootfs, mounts, "/root/a", true, mOps);
            fail("a loop of links was followed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("symbolic links"));
        }
        try {
            TerminowLinuxPaths.resolve(rootfs, mounts, "relative", true, mOps);
            fail("a relative path was resolved");
        } catch (IOException expected) {
            // Refused
        }
    }

    private TerminowLinuxEnv env(@NonNull String alias) throws IOException {
        TerminowLinuxRegistry registry = registry();
        if (!new File(registry.getContainersDir(), alias).exists())
            makeRootfs(new File(registry.getContainersDir(), alias + "/rootfs"), alias, alias, "1");
        return registry.get(alias);
    }

    @Test
    public void filesShowLinuxPathsLinksAndMounts() throws Exception {
        TerminowLinuxEnv env = env("ubuntu");
        File phone = mFolder.newFolder("phone");
        write(new File(phone, "Download/photo.jpg"), "jpeg");
        List<TerminowLinuxPaths.Mount> mounts = Arrays.asList(new TerminowLinuxPaths.Mount("/sdcard", phone, false));
        TerminowLinuxFs fs = new TerminowLinuxFs(env, mounts, mOps);
        write(new File(env.rootfs, "root/notes.txt"), "hello");
        link(new File(env.rootfs, "root/link-to-etc"), "/etc");

        List<TerminowFs.Node> root = fs.list(fs.getRoot(), new TerminowFs.Cancel());
        Set<String> ids = root.stream().map(n -> n.id).collect(Collectors.toCollection(TreeSet::new));
        assertTrue(ids.toString(), ids.contains("/etc") && ids.contains("/sdcard") && ids.contains("/proc") && ids.contains("/bin"));
        TerminowFs.Node bin = root.stream().filter(n -> n.id.equals("/bin")).findFirst().get();
        assertTrue(bin.link);
        assertTrue(bin.directory);

        List<TerminowFs.Node> crumbs = fs.crumbsOf("/root");
        assertEquals(Arrays.asList("/", "/root"), crumbs.stream().map(n -> n.id).collect(Collectors.toList()));
        List<TerminowFs.Node> home = fs.list(crumbs.get(1), new TerminowFs.Cancel());
        TerminowFs.Node link = home.stream().filter(n -> n.name.equals("link-to-etc")).findFirst().get();
        assertTrue(link.link);
        assertEquals("ubuntu:/root", fs.getDisplayPath(crumbs));
        // Into the bound folder, by its Linux path
        List<TerminowFs.Node> download = fs.list(fs.nodeOf("/sdcard/Download"), new TerminowFs.Cancel());
        assertEquals("/sdcard/Download/photo.jpg", download.get(0).id);
        try {
            fs.list(fs.nodeOf("/proc"), new TerminowFs.Cancel());
            fail("a virtual folder was listed as if it were empty");
        } catch (TerminowLinuxFs.VirtualFolderException expected) {
            assertTrue(expected.getMessage().contains("/proc"));
        }

        // Search and the space analyzer: no bound folders, no virtual ones, no links
        TerminowLinuxFs scan = fs.forScan();
        Set<String> scanned = scan.list(scan.getRoot(), new TerminowFs.Cancel()).stream().map(n -> n.id).collect(Collectors.toSet());
        assertFalse(scanned.contains("/sdcard"));
        assertFalse(scanned.contains("/proc"));
        assertFalse(scanned.contains("/bin"));
        assertTrue(scanned.contains("/etc"));

        // Edit, rename, delete, without following links
        try (OutputStream out = fs.openOutput(crumbs.get(1), "new.txt", false)) {
            out.write("x".getBytes(StandardCharsets.UTF_8));
        }
        TerminowFs.Node created = fs.find(crumbs.get(1), "new.txt");
        TerminowFs.Node renamed = fs.rename(created, crumbs.get(1), "renamed.txt");
        assertEquals("/root/renamed.txt", renamed.id);
        fs.delete(link, crumbs.get(1), new TerminowFs.Cancel());
        assertTrue("deleting the link deleted the target", new File(env.rootfs, "etc/passwd").exists());
        try {
            fs.delete(fs.nodeOf("/sdcard"), fs.getRoot(), new TerminowFs.Cancel());
            fail("a bound folder was deleted");
        } catch (IOException expected) {
            assertTrue(new File(phone, "Download/photo.jpg").exists());
        }
        try {
            fs.openOutput(crumbs.get(1), "link-back", false).close();
            link(new File(env.rootfs, "root/out-link"), "/etc/passwd");
            fs.openOutput(crumbs.get(1), "out-link", true);
            fail("wrote through a link");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("link"));
        }

        long usage = TerminowLinuxFs.diskUsage(env, mounts, mOps, new TerminowFs.Cancel());
        assertTrue(usage > 0);
        // The bound folder is not part of the size
        write(new File(phone, "big.bin"), new String(new char[200000]));
        assertEquals(usage, TerminowLinuxFs.diskUsage(env, mounts, mOps, new TerminowFs.Cancel()));
    }

    @Test
    public void copiesBetweenEnvironmentsKeepLinksAndPermissions() throws Exception {
        TerminowLinuxEnv ubuntu = env("ubuntu");
        TerminowLinuxEnv debian = env("debian");
        File script = new File(ubuntu.rootfs, "root/tool/run.sh");
        write(script, "#!/bin/sh\necho hi\n");
        Files.setPosixFilePermissions(script.toPath(), PosixFilePermissions.fromString("rwxr-x---"));
        link(new File(ubuntu.rootfs, "root/tool/current"), "run.sh");
        TerminowLinuxFs from = new TerminowLinuxFs(ubuntu, Collections.emptyList(), mOps);
        TerminowLinuxFs to = new TerminowLinuxFs(debian, Collections.emptyList(), mOps);
        TerminowTransfer.Result result = transfer(from, from.crumbsOf("/root").get(1), from.nodeOf("/root/tool"), to, to.crumbsOf("/root").get(1));
        assertTrue(result.errors.toString(), result.errors.isEmpty());
        File copied = new File(debian.rootfs, "root/tool/run.sh");
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(copied.toPath())));
        assertTrue(Files.isSymbolicLink(new File(debian.rootfs, "root/tool/current").toPath()));
        assertEquals("run.sh", Files.readSymbolicLink(new File(debian.rootfs, "root/tool/current").toPath()).toString());
        assertEquals(1, result.linksCopied);

        // To a folder that does not keep Linux metadata: reported, not hidden
        File shared = mFolder.newFolder("shared");
        TerminowLocalFs local = new TerminowLocalFs(shared, "Shared");
        TerminowTransfer.Result plain = transfer(from, from.crumbsOf("/root").get(1), from.nodeOf("/root/tool"), local, local.getRoot());
        assertTrue(plain.permissionsNotKept > 0);
        assertEquals(1, plain.skippedLinks.size());
        assertTrue(TerminowTransfer.describe(plain).contains("permissions"));
    }

    private static TerminowTransfer.Result transfer(@NonNull TerminowFs source, @NonNull TerminowFs.Node parent, @NonNull TerminowFs.Node node,
                                                    @NonNull TerminowFs target, @NonNull TerminowFs.Node targetDir) {
        TerminowTransfer transfer = new TerminowTransfer(source, target, false, new TerminowTransfer.Listener() {
            @Override
            public void onProgress(@NonNull String name, long bytesDone, long bytesTotal, int filesDone, int filesTotal) {}

            @NonNull
            @Override
            public TerminowTransfer.Resolution onConflict(@NonNull String name) {
                return TerminowTransfer.Resolution.SKIP;
            }
        }, new TerminowFs.Cancel());
        return transfer.run(parent, Collections.singletonList(node), targetDir);
    }

    // -------------------------------------------------------------------------------------------
    // Installing
    // -------------------------------------------------------------------------------------------

    /** Serves one file over HTTP on this machine, for the download. */
    private static class Server implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0);
        final AtomicBoolean slow = new AtomicBoolean();

        Server(@NonNull byte[] body) throws IOException {
            Thread thread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        InputStream in = client.getInputStream();
                        ByteArrayOutputStream request = new ByteArrayOutputStream();
                        int b;
                        while ((b = in.read()) != -1) {
                            request.write(b);
                            if (request.toString("UTF-8").endsWith("\r\n\r\n")) break;
                        }
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        for (int i = 0; i < body.length; i += 4096) {
                            out.write(body, i, Math.min(4096, body.length - i));
                            if (slow.get()) Thread.sleep(50);
                        }
                        out.flush();
                    } catch (IOException | InterruptedException e) {
                        // Next one
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/rootfs.tar.gz";
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** A .tar.gz rootfs made with tar, optionally with an entry that leaves it. */
    private byte[] archive(@NonNull String id, boolean evil) throws Exception {
        File source = mFolder.newFolder();
        File rootfs = makeRootfs(new File(source, "rootfs"), id, id, "1");
        File out = new File(source, "out.tar.gz");
        List<String> command = new ArrayList<>(Arrays.asList("tar", "czf", out.getPath(), "-C", rootfs.getPath(), "."));
        if (evil) {
            // GNU tar would strip "../" itself, Python's tarfile writes what it is told
            command = Arrays.asList("python3", "-I", "-c", "import tarfile,sys,io\n" +
                "t=tarfile.open(sys.argv[1],'w:gz')\n" +
                "t.add(sys.argv[2],arcname='.')\n" +
                "i=tarfile.TarInfo('../../escape'); d=b'x'; i.size=len(d); t.addfile(i,io.BytesIO(d))\n" +
                "t.close()\n", out.getPath(), rootfs.getPath());
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertEquals(0, process.waitFor());
        return Files.readAllBytes(out.toPath());
    }

    private static String sha256(@NonNull byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    /**
     * Plays proot-distro: `install FILE --name NAME` unpacks into containers/NAME/rootfs, `login
     * NAME -- ...` prints terminow-ok. With slowInstall the unpacking waits, to be cancelled.
     */
    private TerminowLinuxInstaller.Runner runner(@NonNull TerminowLinuxRegistry registry, boolean slowInstall, @NonNull List<String> seen) {
        return new TerminowLinuxInstaller.Runner() {
            @NonNull
            @Override
            public Process start(@NonNull List<String> command) throws IOException {
                seen.add(String.join(" ", command));
                if (command.contains("login")) return new ProcessBuilder("sh", "-c", "echo terminow-ok").start();
                String file = command.get(command.indexOf("install") + 1);
                String alias = command.get(command.indexOf("--name") + 1);
                File container = new File(registry.getContainersDir(), alias);
                String script = "echo TERMINOW_PID=$$; echo '[*] Extracting rootfs from archive...'; mkdir -p '" + container + "/rootfs' && " +
                    (slowInstall ? "trap 'exit 130' INT; sleep 30 >/dev/null 2>&1 & wait $!; " : "") + "tar xzf '" + file + "' -C '" + container + "/rootfs' && echo \"[*] Updating '/etc/resolv.conf'...\"";
                return new ProcessBuilder("sh", "-c", script).start();
            }

            @Override
            public void interrupt(int pid) {
                try {
                    new ProcessBuilder("kill", "-INT", String.valueOf(pid)).start().waitFor();
                } catch (IOException | InterruptedException e) {
                    // Then it just ends later
                }
            }
        };
    }

    private static class MemoryMarker implements TerminowLinuxInstaller.Marker {
        String alias;

        @Nullable
        @Override
        public String getAlias() {
            return alias;
        }

        @Override
        public void set(@Nullable String alias) {
            this.alias = alias;
        }
    }

    private static TerminowLinuxCatalog.Entry entry(@NonNull String id, @NonNull String url, @NonNull String sha, long size) {
        return new TerminowLinuxCatalog.Entry(id, id, "1", "test", "aarch64", url, sha, size, 1024 * 1024, false);
    }

    private static final TerminowLinuxInstaller.Listener QUIET = new TerminowLinuxInstaller.Listener() {
        @Override
        public void onPhase(@NonNull TerminowLinuxInstaller.Phase phase, @NonNull String detail) {}

        @Override
        public void onProgress(long done, long total) {}
    };

    @Test
    public void installVerifiesAndPublishesOnlyAfterValidation() throws Exception {
        TerminowLinuxRegistry registry = registry();
        byte[] archive = archive("alpine", false);
        File downloads = mFolder.newFolder("downloads");
        List<String> seen = new ArrayList<>();
        MemoryMarker marker = new MemoryMarker();
        List<TerminowLinuxInstaller.Phase> phases = new ArrayList<>();
        try (Server server = new Server(archive)) {
            TerminowLinuxInstaller installer = new TerminowLinuxInstaller(registry, downloads, runner(registry, false, seen), marker, mOps);
            assertEquals("alpine", installer.freeAlias("alpine"));
            TerminowLinuxEnv env = installer.install(entry("alpine", server.url(), sha256(archive), archive.length), "alpine", "aarch64",
                Long.MAX_VALUE, new TerminowLinuxInstaller.Listener() {
                    @Override
                    public void onPhase(@NonNull TerminowLinuxInstaller.Phase phase, @NonNull String detail) {
                        phases.add(phase);
                    }

                    @Override
                    public void onProgress(long done, long total) {}
                });
            assertTrue(env.isInstalled());
            assertEquals("alpine", env.distroId);
            assertEquals("alpine2", installer.freeAlias("alpine"));
        }
        assertTrue(phases.containsAll(Arrays.asList(TerminowLinuxInstaller.Phase.DOWNLOADING, TerminowLinuxInstaller.Phase.VERIFYING,
            TerminowLinuxInstaller.Phase.EXTRACTING, TerminowLinuxInstaller.Phase.SETTING_UP, TerminowLinuxInstaller.Phase.VALIDATING,
            TerminowLinuxInstaller.Phase.DONE)));
        // proot-distro got the checked local file, and a login was tried before it counted as installed
        assertTrue(seen.get(0), seen.get(0).contains("proot-distro install " + new File(downloads, "alpine.tar.gz").getAbsolutePath() + " --name alpine"));
        assertTrue(seen.get(1).contains("login alpine -- /bin/sh -c echo terminow-ok"));
        assertNull(marker.alias);
        assertEquals(0, downloads.list().length);
    }

    @Test
    public void aWrongChecksumInstallsNothing() throws Exception {
        TerminowLinuxRegistry registry = registry();
        byte[] archive = archive("alpine", false);
        File downloads = mFolder.newFolder("downloads");
        List<String> seen = new ArrayList<>();
        try (Server server = new Server(archive)) {
            TerminowLinuxInstaller installer = new TerminowLinuxInstaller(registry, downloads, runner(registry, false, seen), new MemoryMarker(), mOps);
            try {
                installer.install(entry("alpine", server.url(), sha256("other".getBytes(StandardCharsets.UTF_8)), archive.length), "alpine",
                    "aarch64", Long.MAX_VALUE, QUIET);
                fail("installed with a wrong checksum");
            } catch (TerminowLinuxInstaller.InstallException expected) {
                assertTrue(expected.getMessage().contains("checksum"));
            }
        }
        assertTrue("proot-distro ran", seen.isEmpty());
        assertFalse(new File(registry.getContainersDir(), "alpine").exists());
        assertEquals(0, downloads.list().length);
    }

    @Test
    public void anArchiveThatLeavesTheRootfsIsRefused() throws Exception {
        TerminowLinuxRegistry registry = registry();
        byte[] archive = archive("alpine", true);
        File downloads = mFolder.newFolder("downloads");
        List<String> seen = new ArrayList<>();
        try (Server server = new Server(archive)) {
            TerminowLinuxInstaller installer = new TerminowLinuxInstaller(registry, downloads, runner(registry, false, seen), new MemoryMarker(), mOps);
            try {
                installer.install(entry("alpine", server.url(), sha256(archive), archive.length), "alpine", "aarch64", Long.MAX_VALUE, QUIET);
                fail("an archive with ../ was installed");
            } catch (TerminowLinuxInstaller.InstallException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("leaves"));
            }
        }
        assertTrue(seen.isEmpty());
        assertFalse(new File(registry.getContainersDir(), "alpine").exists());
    }

    @Test
    public void cancelAndInterruptedInstallsAreCleanedUp() throws Exception {
        TerminowLinuxRegistry registry = registry();
        byte[] archive = archive("debian", false);
        File downloads = mFolder.newFolder("downloads");
        MemoryMarker marker = new MemoryMarker();
        TerminowLinuxInstaller installer = new TerminowLinuxInstaller(registry, downloads, runner(registry, true, new ArrayList<>()), marker, mOps);
        Throwable[] failure = new Throwable[1];
        try (Server server = new Server(archive)) {
            Thread thread = new Thread(() -> {
                try {
                    installer.install(entry("debian", server.url(), sha256(archive), archive.length), "debian", "aarch64", Long.MAX_VALUE, QUIET);
                } catch (Throwable e) {
                    failure[0] = e;
                }
            });
            thread.start();
            // Until proot-distro is running and has made its folder
            long deadline = System.currentTimeMillis() + 20000;
            while (!new File(registry.getContainersDir(), "debian/rootfs").exists() && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertEquals("debian", marker.alias);
            Thread.sleep(300);
            installer.cancel();
            thread.join(20000);
            assertFalse(thread.isAlive());
        }
        assertNotNull(failure[0]);
        assertTrue(String.valueOf(failure[0].getMessage()), String.valueOf(failure[0].getMessage()).contains("Cancelled"));
        assertFalse(new File(registry.getContainersDir(), "debian").exists());
        assertNull(marker.alias);
        assertEquals(0, downloads.list().length);

        // The app was killed during an install: next start, its leftovers go, others stay
        makeRootfs(new File(registry.getContainersDir(), "ubuntu/rootfs"), "ubuntu", "Ubuntu", "24.04");
        assertTrue(new File(registry.getContainersDir(), "half/rootfs/usr").mkdirs());
        write(new File(downloads, "half.download"), "partial");
        marker.alias = "half";
        assertEquals("half", installer.cleanUpInterrupted());
        assertFalse(new File(registry.getContainersDir(), "half").exists());
        assertFalse(new File(downloads, "half.download").exists());
        assertTrue(new File(registry.getContainersDir(), "ubuntu/rootfs/etc/passwd").exists());
        assertNull(installer.cleanUpInterrupted());
    }

    @Test
    public void installRefusesExistingNamesUnsupportedArchesAndFullStorage() throws Exception {
        TerminowLinuxRegistry registry = registry();
        makeRootfs(new File(registry.getContainersDir(), "ubuntu/rootfs"), "ubuntu", "Ubuntu", "24.04");
        List<String> before = tree(registry.getContainersDir());
        TerminowLinuxInstaller installer = new TerminowLinuxInstaller(registry, mFolder.newFolder("d"), runner(registry, false, new ArrayList<>()),
            new MemoryMarker(), mOps);
        TerminowLinuxCatalog.Entry ubuntu = TerminowLinuxCatalog.get("ubuntu");
        for (Object[] attempt : new Object[][]{{"ubuntu", "aarch64", Long.MAX_VALUE}, {"ubuntu2", "x86_64", Long.MAX_VALUE}, {"ubuntu2", "aarch64", 1000L}}) {
            try {
                installer.install(ubuntu, (String) attempt[0], (String) attempt[1], (Long) attempt[2], QUIET);
                fail("installed " + Arrays.toString(attempt));
            } catch (TerminowLinuxInstaller.InstallException expected) {
                // Refused before anything was downloaded
            }
        }
        // The existing installation was not touched
        assertEquals(before, tree(registry.getContainersDir()));
        assertEquals("ubuntu2", installer.freeAlias("ubuntu"));
    }

    // -------------------------------------------------------------------------------------------
    // Catalog and Premium
    // -------------------------------------------------------------------------------------------

    @Test
    public void catalogIsPinnedAndKaliIsOff() {
        for (TerminowLinuxCatalog.Entry entry : TerminowLinuxCatalog.ENTRIES) {
            if (entry.experimental) {
                assertEquals("kali", entry.distroId);
                assertEquals(TerminowLinuxCatalog.Support.EXPERIMENTAL, entry.supportOn("aarch64"));
                continue;
            }
            assertTrue(entry.url, entry.url.startsWith("https://"));
            assertTrue(entry.sha256.matches("[0-9a-f]{64}"));
            assertTrue(entry.downloadBytes > 0);
            assertEquals(TerminowLinuxCatalog.Support.AVAILABLE, entry.supportOn("aarch64"));
            assertEquals(TerminowLinuxCatalog.Support.UNSUPPORTED_ARCH, entry.supportOn("x86_64"));
        }
        assertEquals("aarch64", TerminowLinuxCatalog.archOfAbi("arm64-v8a"));
        assertEquals("arm", TerminowLinuxCatalog.archOfAbi("armeabi-v7a"));
    }

    @Test
    public void premiumGatesTheLinuxFeaturesAndKeepsFreeOnesFree() {
        assertFalse(TerminowPremiumGate.allows(TerminowPremiumGate.Feature.LINUX_FILES, false, 1));
        assertFalse(TerminowPremiumGate.allows(TerminowPremiumGate.Feature.TMUX, false, 1));
        // The first environment is free, a second one is Premium
        assertTrue(TerminowPremiumGate.allows(TerminowPremiumGate.Feature.MULTIPLE_ENVIRONMENTS, false, 0));
        assertFalse(TerminowPremiumGate.allows(TerminowPremiumGate.Feature.MULTIPLE_ENVIRONMENTS, false, 1));
        for (TerminowPremiumGate.Feature feature : TerminowPremiumGate.Feature.values())
            assertTrue(TerminowPremiumGate.allows(feature, true, 5));
    }

}
