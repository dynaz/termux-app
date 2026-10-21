package com.termux.app.terminow;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * A backup to the phone is part of Premium where the job starts, whatever called it: the
 * Backup &amp; Restore screen, or anything else that reaches the job directly.
 */
public class TerminowBackupEntitlementTest {

    private TerminowStore mStore;

    @Before
    public void setUp() {
        TerminowStore.sTestsWithoutKeystore = true;
        mStore = new TerminowStore(new MemoryPreferences());
    }

    private void signIn() {
        TerminowAccount.Session session = new TerminowAccount.Session();
        session.uid = "uid1";
        session.email = "dev@example.com";
        session.emailVerified = true;
        session.refreshToken = "refresh";
        mStore.setAccount(session);
    }

    private void premium(boolean active) {
        TerminowAccount.Premium premium = new TerminowAccount.Premium();
        premium.premium = active;
        premium.plan = active ? "TermiNow Premium" : "";
        premium.source = active ? "accmove" : "";
        mStore.setPremium(premium);
    }

    private TerminowPhoneBackup.Job start(TerminowPhoneBackup.Kind kind) {
        TerminowPhoneBackup.Request request = new TerminowPhoneBackup.Request();
        request.kind = kind;
        request.alias = "ubuntu";
        // Refused before anything is started, so nothing of Android is needed
        return TerminowPhoneBackup.start(null, mStore, null, "/nonexistent", request);
    }

    @Test
    public void aFreeOrSignedOutUserStartsNoBackup() {
        assertFalse(TerminowPhoneBackup.isRunning());
        assertFalse(TerminowPhoneBackup.mayBackUp(mStore));
        assertNull(start(TerminowPhoneBackup.Kind.BACKUP_FULL));
        signIn();
        premium(false);
        assertFalse(TerminowPhoneBackup.mayBackUp(mStore));
        assertNull(start(TerminowPhoneBackup.Kind.BACKUP_FULL));
        assertNull(start(TerminowPhoneBackup.Kind.BACKUP_FOLDERS));
        // Restore is Premium too, also checking a file for it
        assertNull(start(TerminowPhoneBackup.Kind.INSPECT));
        assertNull(start(TerminowPhoneBackup.Kind.RESTORE_FULL));
        assertNull(start(TerminowPhoneBackup.Kind.RESTORE_FOLDERS));
        assertFalse("no job was left behind", TerminowPhoneBackup.isRunning());
    }

    @Test
    public void premiumMayBackUp() {
        signIn();
        premium(true);
        assertTrue(TerminowPhoneBackup.mayBackUp(mStore));
    }

    @org.junit.Rule
    public org.junit.rules.TemporaryFolder temp = new org.junit.rules.TemporaryFolder();

    @Test
    public void onlyAWholeLinuxReplacesTheEnvironmentThatWasThere() throws Exception {
        java.io.File rootfs = temp.newFolder("rootfs");
        assertFalse("empty", TerminowPhoneBackup.isUsableRootfs(rootfs));
        assertTrue(new java.io.File(rootfs, "usr/bin").mkdirs());
        assertTrue(new java.io.File(rootfs, "usr/bin/sh").createNewFile());
        assertFalse("no os-release", TerminowPhoneBackup.isUsableRootfs(rootfs));
        assertTrue(new java.io.File(rootfs, "usr/lib").mkdirs());
        assertTrue(new java.io.File(rootfs, "usr/lib/os-release").createNewFile());
        // Ubuntu has /bin as a link to usr/bin and /etc/os-release as a link to ../usr/lib/os-release
        assertTrue(TerminowPhoneBackup.isUsableRootfs(rootfs));
        assertFalse(TerminowPhoneBackup.isUsableRootfs(new java.io.File(rootfs, "missing")));
    }
}
