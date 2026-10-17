package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** "Sync keys and identities": the keys and identities in the Personal vault, between two phones. */
public class TerminowKeySyncTest {

    private static final String KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----\n";

    /** The ~/.ssh of one phone. */
    static class Files implements TerminowStore.KeyFiles {
        final Map<String, TerminowStore.SyncedKey> keys = new LinkedHashMap<>();

        void put(String name, String privateKey) {
            TerminowStore.SyncedKey key = new TerminowStore.SyncedKey();
            key.name = name;
            key.privateKey = privateKey;
            key.publicKey = "ssh-ed25519 AAAA " + name;
            keys.put(name, key);
        }

        @Override
        public List<TerminowStore.SyncedKey> list() {
            List<TerminowStore.SyncedKey> list = new ArrayList<>();
            for (TerminowStore.SyncedKey key : keys.values()) if (TerminowStore.isValidSyncedKey(key)) list.add(key);
            return list;
        }

        @Override
        public boolean add(TerminowStore.SyncedKey key) {
            if (keys.containsKey(key.name)) return false;
            keys.put(key.name, key);
            return true;
        }
    }

    private TerminowStore.KeyFiles mDefault;
    private final Files mFilesA = new Files();
    private final Files mFilesB = new Files();

    @Before
    public void setUp() {
        TerminowStore.sTestsWithoutKeystore = true;
        mDefault = TerminowStore.sKeyFiles;
    }

    @After
    public void tearDown() {
        TerminowStore.sTestsWithoutKeystore = false;
        TerminowStore.sKeyFiles = mDefault;
    }

    private JSONObject export(TerminowStore store, Files files) throws Exception {
        TerminowStore.sKeyFiles = files;
        return store.exportVault("");
    }

    private void apply(TerminowStore store, Files files, JSONObject data) throws Exception {
        TerminowStore.sKeyFiles = files;
        store.importVault("", data);
    }

    private static TerminowStore.Identity identity(String id, String username) {
        TerminowStore.Identity identity = new TerminowStore.Identity();
        identity.id = id;
        identity.username = username;
        identity.password = "secret-" + id;
        return identity;
    }

    @Test
    public void offByDefaultAndNothingOfTheKeysInTheVault() throws Exception {
        TerminowStore a = new TerminowStore(new MemoryPreferences());
        mFilesA.put("id_ed25519", KEY);
        a.setIdentities(java.util.Collections.singletonList(identity("work", "root")));
        JSONObject data = export(a, mFilesA);
        assertFalse(a.isSyncKeys());
        assertFalse(data.getBoolean("syncKeys"));
        assertEquals(0, data.getJSONArray("keys").length());
        assertEquals(0, data.getJSONArray("identities").length());
    }

    @Test
    public void turningItOnTakesKeysAndIdentitiesToTheOtherPhone() throws Exception {
        TerminowStore a = new TerminowStore(new MemoryPreferences());
        TerminowStore b = new TerminowStore(new MemoryPreferences());
        mFilesA.put("id_ed25519", KEY);
        // Not a private key: never synced
        mFilesA.put("notes", "hello");
        a.setKeyLabel("id_ed25519", "Work key");
        a.setIdentities(java.util.Collections.singletonList(identity("work", "root")));
        int before = a.getVaultChange("");
        a.setSyncKeys(true);
        assertTrue(a.getVaultChange("") > before);

        JSONObject data = export(a, mFilesA);
        assertEquals(1, data.getJSONArray("keys").length());
        assertEquals(KEY, data.getJSONArray("keys").getJSONObject(0).getString("private"));
        assertEquals("Work key", data.getJSONArray("keys").getJSONObject(0).getString("label"));
        // The password of an identity is in the vault data, which is encrypted as a whole
        assertEquals("secret-work", data.getJSONArray("identities").getJSONObject(0).getString("password"));

        // The other phone has its own identity and a different key with the same name
        mFilesB.put("id_ed25519", KEY.replace("b3Bl", "ZZZZ"));
        b.setIdentities(java.util.Collections.singletonList(identity("home", "pi")));
        apply(b, mFilesB, data);
        assertTrue(b.isSyncKeys());
        // Never replaced
        assertTrue(mFilesB.keys.get("id_ed25519").privateKey.contains("ZZZZ"));
        List<String> ids = new ArrayList<>();
        for (TerminowStore.Identity identity : b.getIdentities()) ids.add(identity.id);
        assertEquals(java.util.Arrays.asList("work", "home"), ids);
        // Its own identity goes to the vault the next time
        assertTrue(b.isVaultDirty(""));
    }

    @Test
    public void newKeysAreAddedWithTheirLabel() throws Exception {
        TerminowStore a = new TerminowStore(new MemoryPreferences());
        TerminowStore b = new TerminowStore(new MemoryPreferences());
        mFilesA.put("server_key", KEY);
        a.setKeyLabel("server_key", "Server");
        a.setSyncKeys(true);
        apply(b, mFilesB, export(a, mFilesA));
        assertEquals(KEY, mFilesB.keys.get("server_key").privateKey);
        assertEquals("Server", b.getKeyLabel("server_key"));
        // What was just added is not a change of that phone
        TerminowStore.sKeyFiles = mFilesB;
        int change = b.getVaultChange("");
        b.checkKeyFiles();
        assertEquals(change, b.getVaultChange(""));
    }

    @Test
    public void aKeyMadeInATerminalIsAChangeToSync() throws Exception {
        TerminowStore a = new TerminowStore(new MemoryPreferences());
        a.setSyncKeys(true);
        TerminowStore.sKeyFiles = mFilesA;
        a.checkKeyFiles();
        int change = a.getVaultChange("");
        a.checkKeyFiles();
        assertEquals(change, a.getVaultChange(""));
        mFilesA.put("id_new", KEY);
        a.checkKeyFiles();
        assertTrue(a.getVaultChange("") > change);
    }

    @Test
    public void unsafeKeyNamesAreNeverWritten() throws Exception {
        TerminowStore b = new TerminowStore(new MemoryPreferences());
        JSONObject data = TerminowVaultData.empty().put("syncKeys", true).put("syncKeysChanged", 1);
        for (String name : new String[]{"../evil", "config", "authorized_keys", "id.pub", ".hidden", "known_hosts"})
            data.getJSONArray("keys").put(new JSONObject().put("name", name).put("private", KEY));
        data.getJSONArray("keys").put(new JSONObject().put("name", "plain").put("private", "not a key"));
        apply(b, mFilesB, data);
        assertTrue(mFilesB.keys.isEmpty());
    }

    @Test
    public void theLastChoiceWinsAndOffTakesThemOutOfTheVault() throws Exception {
        JSONObject on = TerminowVaultData.empty().put("syncKeys", true).put("syncKeysChanged", 100);
        on.getJSONArray("keys").put(new JSONObject().put("name", "k").put("private", KEY));
        JSONObject off = TerminowVaultData.empty().put("syncKeys", false).put("syncKeysChanged", 200);
        JSONObject merged = TerminowVaultData.merge(on, off);
        assertFalse(merged.getBoolean("syncKeys"));
        assertEquals(0, merged.getJSONArray("keys").length());

        JSONObject other = TerminowVaultData.empty().put("syncKeys", true).put("syncKeysChanged", 100);
        other.getJSONArray("keys").put(new JSONObject().put("name", "k2").put("private", KEY));
        merged = TerminowVaultData.merge(on, other);
        assertTrue(merged.getBoolean("syncKeys"));
        JSONArray keys = merged.getJSONArray("keys");
        assertEquals(2, keys.length());
    }

    @Test
    public void aVaultOfAnOlderTermiNowDoesNotTurnItOff() throws Exception {
        TerminowStore a = new TerminowStore(new MemoryPreferences());
        a.setSyncKeys(true);
        a.setVaultSynced("", a.getVaultChange(""));
        JSONObject old = new JSONObject().put("format", 1).put("hosts", new JSONArray()).put("groups", new JSONArray())
            .put("snippets", new JSONArray()).put("forwards", new JSONArray());
        apply(a, mFilesA, TerminowVaultData.parse(old.toString()));
        assertTrue(a.isSyncKeys());
        // Its keys are put back in the vault
        assertTrue(a.isVaultDirty(""));
    }
}
