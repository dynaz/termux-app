package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TerminowPathResolverTest {

    private TerminowPathResolver.Target target(TerminowPathResolver.Env env) {
        TerminowPathResolver.Target t = new TerminowPathResolver.Target();
        t.env = env;
        return t;
    }

    @Test public void termuxPathIsAlreadyAHostPath() {
        TerminowPathResolver.Resolved r = TerminowPathResolver.resolve(target(TerminowPathResolver.Env.TERMUX), "/sdcard/a.pdf");
        assertEquals(TerminowPathResolver.Kind.LOCAL_FILE, r.kind);
        assertEquals("/sdcard/a.pdf", r.localPath);
    }

    @Test public void prootGuestPathMapsThroughTheRootfs() {
        TerminowPathResolver.Target t = target(TerminowPathResolver.Env.PROOT);
        t.rootfs = "/data/data/com.terminow/files/ubuntu/rootfs";
        TerminowPathResolver.Resolved r = TerminowPathResolver.resolve(t, "/root/app.apk");
        assertEquals(TerminowPathResolver.Kind.LOCAL_FILE, r.kind);
        assertEquals("/data/data/com.terminow/files/ubuntu/rootfs/root/app.apk", r.localPath);
    }

    @Test public void aBindTakesPrecedenceOverTheRootfs() {
        TerminowPathResolver.Target t = target(TerminowPathResolver.Env.PROOT);
        t.rootfs = "/data/data/com.terminow/files/ubuntu/rootfs";
        t.binds.add(new TerminowPathResolver.Bind("/storage/emulated/0", "/sdcard"));
        TerminowPathResolver.Resolved r = TerminowPathResolver.resolve(t, "/sdcard/Download/x.pdf");
        assertEquals("/storage/emulated/0/Download/x.pdf", r.localPath);
    }

    @Test public void remoteSshPathIsNeverLocal() {
        TerminowPathResolver.Resolved r = TerminowPathResolver.resolve(target(TerminowPathResolver.Env.SSH), "/root/secret.pem");
        assertEquals(TerminowPathResolver.Kind.REMOTE_SSH, r.kind);
        assertEquals("/root/secret.pem", r.remotePath);
    }

    @Test public void relativeNeedsCwdAndHomeNeedsHome() {
        TerminowPathResolver.Target t = target(TerminowPathResolver.Env.TERMUX);
        assertEquals(TerminowPathResolver.Kind.UNRESOLVED, TerminowPathResolver.resolve(t, "a/b.txt").kind);
        t.cwd = "/home/me/work";
        assertEquals("/home/me/work/a/b.txt", TerminowPathResolver.resolve(t, "a/b.txt").localPath);
        assertEquals(TerminowPathResolver.Kind.UNRESOLVED, TerminowPathResolver.resolve(t, "~/x").kind);
        t.home = "/home/me";
        assertEquals("/home/me/x", TerminowPathResolver.resolve(t, "~/x").localPath);
    }

    @Test public void unknownEnvironmentIsUnresolved() {
        TerminowPathResolver.Resolved r = TerminowPathResolver.resolve(target(TerminowPathResolver.Env.UNKNOWN), "/a/b");
        assertEquals(TerminowPathResolver.Kind.UNRESOLVED, r.kind);
    }

    @Test public void dotSegmentsAreNormalized() {
        assertEquals("/a/c", TerminowPathResolver.normalize("/a/./b/../c"));
        assertEquals("/", TerminowPathResolver.normalize("/x/.."));
    }
}
