package com.termux.app.terminow;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The vaults end to end: the encryption, and the app against the real vault server logic
 * (whmcs/terminow/vault_lib.php) run by PHP with SQLite, with several phones and accounts, each
 * with its own preferences in memory.
 */
public class TerminowVaultsTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private Process mServer;
    private String mUrl;

    @Before
    public void setUp() {
        TerminowStore.sTestsWithoutKeystore = true;
    }

    @After
    public void tearDown() {
        if (mServer != null) mServer.destroy();
        TerminowStore.sTestsWithoutKeystore = false;
    }

    // -------------------------------------------------------------------------------------------
    // Encryption
    // -------------------------------------------------------------------------------------------

    @Test
    public void pbkdf2MatchesTheJavaOne() throws Exception {
        byte[] salt = "some salt".getBytes(StandardCharsets.UTF_8);
        byte[] ours = TerminowVaultCrypto.pbkdf2("pässword", salt, 1000, 32);
        byte[] java = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(new PBEKeySpec("pässword".toCharArray(), salt, 1000, 256)).getEncoded();
        assertArrayEquals(java, ours);
        // RFC 7914 test vector, first 32 bytes
        byte[] vector = TerminowVaultCrypto.pbkdf2("passwd", "salt".getBytes(StandardCharsets.UTF_8), 1, 32);
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", hex(vector));
    }

    @Test
    public void accountKeysOpenOnlyWithThePassword() throws Exception {
        TerminowVaultCrypto.AccountKeys keys = TerminowVaultCrypto.createAccount("correct horse battery");
        PrivateKey key = TerminowVaultCrypto.unlock(keys.encryptedPrivateKey, keys.salt, keys.iterations, "correct horse battery");
        assertArrayEquals(keys.privateKey.getEncoded(), key.getEncoded());
        try {
            TerminowVaultCrypto.unlock(keys.encryptedPrivateKey, keys.salt, keys.iterations, "wrong horse battery");
            throw new AssertionError("wrong password accepted");
        } catch (TerminowVaultCrypto.DecryptException expected) {
            // Refused
        }
        // A new password keeps the key
        TerminowVaultCrypto.protectPrivateKey(keys, "another long password");
        assertArrayEquals(key.getEncoded(), TerminowVaultCrypto.unlock(keys.encryptedPrivateKey, keys.salt, keys.iterations,
            "another long password").getEncoded());

        byte[] vaultKey = TerminowVaultCrypto.newVaultKey();
        String wrapped = TerminowVaultCrypto.wrap(vaultKey, keys.publicKey);
        assertArrayEquals(vaultKey, TerminowVaultCrypto.unwrap(wrapped, key));
        TerminowVaultCrypto.AccountKeys other = TerminowVaultCrypto.createAccount("someone else entirely");
        try {
            TerminowVaultCrypto.unwrap(wrapped, other.privateKey);
            throw new AssertionError("unwrapped with another key");
        } catch (TerminowVaultCrypto.DecryptException expected) {
            // Refused
        }

        String plain = "{\"password\":\"correct-horse-battery-staple\"}";
        String data = TerminowVaultCrypto.encryptVault(vaultKey, "aaaaaaaaaaaaaaaaaaaaaaaa", plain);
        // Neither the text nor its base64 is in the ciphertext
        assertFalse(data.contains("correct-horse"));
        assertFalse(data.contains(TerminowPpk.encodeBase64(plain.getBytes(StandardCharsets.UTF_8)).substring(0, 16)));
        assertEquals(plain, TerminowVaultCrypto.decryptVault(vaultKey, "aaaaaaaaaaaaaaaaaaaaaaaa", data));
        try {
            // Bound to its vault: the data of one vault can not be passed off as another one's
            TerminowVaultCrypto.decryptVault(vaultKey, "bbbbbbbbbbbbbbbbbbbbbbbb", data);
            throw new AssertionError("decrypted for another vault");
        } catch (TerminowVaultCrypto.DecryptException expected) {
            // Refused
        }
        assertTrue(TerminowVaultCrypto.fingerprint(keys.publicKey).matches("([0-9A-F]{4} ){3}[0-9A-F]{4}"));
        assertTrue(TerminowVaultCrypto.newVaultId().matches("[0-9a-f]{24}"));
    }

    @Test
    public void mergeKeepsBothAndPrefersThisPhone() throws Exception {
        JSONObject remote = TerminowVaultData.empty();
        remote.getJSONArray("hosts").put(host("web", "10.0.0.1", "old"));
        remote.getJSONArray("hosts").put(host("db", "10.0.0.2", "db"));
        remote.getJSONArray("groups").put("prod");
        JSONObject local = TerminowVaultData.empty();
        local.getJSONArray("hosts").put(host("web", "10.0.0.1", "new"));
        local.getJSONArray("hosts").put(host("cache", "10.0.0.3", ""));
        local.getJSONArray("groups").put("prod").put("dev");
        JSONObject merged = TerminowVaultData.merge(remote, local);
        JSONArray hosts = merged.getJSONArray("hosts");
        assertEquals(3, hosts.length());
        assertEquals("new", hosts.getJSONObject(0).getString("password"));
        assertEquals(2, merged.getJSONArray("groups").length());
        try {
            TerminowVaultData.parse("{\"format\":2,\"hosts\":[]}");
            throw new AssertionError("newer format accepted");
        } catch (org.json.JSONException expected) {
            // Refused
        }
    }

    // -------------------------------------------------------------------------------------------
    // The app against the server
    // -------------------------------------------------------------------------------------------

    private void startServer() throws Exception {
        File router = new File("../whmcs/tests/vault_router.php").getCanonicalFile();
        assumeTrue("php is needed", new File("/usr/bin/php").canExecute() && router.exists());
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ProcessBuilder builder = new ProcessBuilder("/usr/bin/php", "-S", "127.0.0.1:" + port, router.getPath())
            .redirectErrorStream(true).redirectOutput(new File(mFolder.getRoot(), "php.log"));
        builder.environment().put("TERMINOW_TEST_DB", new File(mFolder.getRoot(), "vault.db").getPath());
        mServer = builder.start();
        mUrl = "http://127.0.0.1:" + port + "/vault.php";
        for (int i = 0; i < 100; i++) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(mUrl).openConnection();
                connection.getResponseCode();
                return;
            } catch (java.io.IOException e) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("php did not start");
    }

    /** A phone with its own store, signed in with an email. */
    private static class Phone {
        final TerminowStore store;
        final TerminowVaults vaults;

        Phone(@NonNull String name, @NonNull String email, @NonNull String url) {
            store = new TerminowStore(new MemoryPreferences());
            vaults = new TerminowVaults(store, new TerminowVaults.Tokens() {
                @NonNull
                @Override
                public String getIdToken() {
                    return "test-" + email;
                }

                @NonNull
                @Override
                public String getEmail() {
                    return email;
                }
            });
            vaults.setUrl(url);
        }

        void addHost(@NonNull String label, @NonNull String address, @NonNull String password) {
            List<TerminowStore.Host> hosts = store.getHosts();
            TerminowStore.Host host = new TerminowStore.Host();
            host.label = label;
            host.address = address;
            host.username = "root";
            host.password = password;
            hosts.add(host);
            store.setHosts(hosts);
        }

        @NonNull
        List<String> labels() {
            List<String> labels = new ArrayList<>();
            for (TerminowStore.Host host : store.getHosts()) labels.add(host.label);
            Collections.sort(labels);
            return labels;
        }
    }

    @Test
    public void personalVaultSyncsBetweenPhonesEndToEndEncrypted() throws Exception {
        startServer();
        Phone a = new Phone("a", "owner@example.com", mUrl);
        a.addHost("web", "10.0.0.1", "s3cret-pass");
        assertNull(a.vaults.refresh().optJSONObject("account"));
        a.vaults.createAccount("correct horse battery");
        assertTrue(a.vaults.isUnlocked());
        a.vaults.enablePersonalSync();
        assertNotNull(a.vaults.getPersonal());
        assertFalse(a.store.isVaultDirty(""));

        // The server has only ciphertext
        String db = new String(java.nio.file.Files.readAllBytes(new File(mFolder.getRoot(), "vault.db").toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(db.contains("s3cret-pass"));
        assertFalse(db.contains("10.0.0.1"));

        // A second phone of the same account
        Phone a2 = new Phone("a2", "owner@example.com", mUrl);
        try {
            a2.vaults.unlock("wrong password here");
            throw new AssertionError("wrong vault password accepted");
        } catch (TerminowVaults.VaultException expected) {
            assertFalse(a2.vaults.isUnlocked());
        }
        a2.vaults.unlock("correct horse battery");
        a2.vaults.syncAll();
        assertEquals(Collections.singletonList("web"), a2.labels());
        assertEquals("s3cret-pass", a2.store.getHosts().get(0).password);

        // Both change it: the changes are merged
        a2.addHost("db", "10.0.0.2", "");
        a.addHost("cache", "10.0.0.3", "");
        assertTrue(a2.store.isVaultDirty(""));
        a2.vaults.syncAll();
        a.vaults.syncAll();
        a2.vaults.syncAll();
        assertEquals(java.util.Arrays.asList("cache", "db", "web"), a.labels());
        assertEquals(a.labels(), a2.labels());
        assertFalse(a.store.isVaultDirty(""));

        // A new vault password on one phone, the other keeps working with its keys
        a.vaults.changePassword("a new long password");
        Phone a3 = new Phone("a3", "owner@example.com", mUrl);
        a3.vaults.unlock("a new long password");
        a3.vaults.syncAll();
        assertEquals(a.labels(), a3.labels());
    }

    @Test
    public void teamVaultWithMembersAndAKeyChange() throws Exception {
        startServer();
        Phone owner = new Phone("owner", "owner@example.com", mUrl);
        owner.vaults.createAccount("correct horse battery");
        String team = owner.vaults.createTeam("Ops team");
        owner.store.setActiveVault(team);
        owner.addHost("prod", "10.1.0.1", "team-secret");
        owner.store.setActiveVault("");
        assertTrue(owner.labels().isEmpty());
        owner.vaults.syncAll();

        Phone member = new Phone("member", "member@example.com", mUrl);
        member.vaults.createAccount("member password 123");
        owner.vaults.invite(team, "Member@Example.com");
        member.vaults.refresh();
        TerminowVaults.Info seen = member.vaults.get(team);
        assertNotNull(seen);
        assertFalse(seen.hasKey);
        assertEquals("Ops team", seen.name);

        JSONArray members = owner.vaults.getMembers(team);
        JSONObject waiting = null;
        for (int i = 0; i < members.length(); i++)
            if (members.getJSONObject(i).getString("email").equals("member@example.com")) waiting = members.getJSONObject(i);
        assertNotNull(waiting);
        assertEquals("waiting", waiting.getString("status"));
        // The fingerprint the owner sees is the one the member sees on their phone
        assertEquals(member.vaults.getFingerprint(), TerminowVaultCrypto.fingerprint(waiting.getString("publicKey")));
        owner.vaults.grant(team, "member@example.com", waiting.getString("publicKey"));

        assertTrue(member.vaults.syncAll().isEmpty());
        member.store.setActiveVault(team);
        assertEquals(Collections.singletonList("prod"), member.labels());
        assertEquals("team-secret", member.store.getHosts().get(0).password);
        member.addHost("staging", "10.1.0.2", "");
        member.vaults.syncAll();
        owner.vaults.syncAll();
        owner.store.setActiveVault(team);
        assertEquals(java.util.Arrays.asList("prod", "staging"), owner.labels());

        // A second phone of the owner keeps the old key until the key changes
        Phone owner2 = new Phone("owner2", "owner@example.com", mUrl);
        owner2.vaults.unlock("correct horse battery");
        owner2.vaults.syncAll();
        byte[] oldKey = owner2.store.getVaultKey(team);

        owner.vaults.removeMember(team, "member@example.com");
        assertNotEquals(TerminowPpk.encodeBase64(oldKey), TerminowPpk.encodeBase64(owner.store.getVaultKey(team)));
        member.vaults.refresh();
        assertNull(member.vaults.get(team));
        // The team is gone from the removed member's phone, which shows the personal vault again
        assertEquals("", member.store.getActiveVault());
        member.store.setActiveVault(team);
        assertTrue(member.labels().isEmpty());

        // The other phone of the owner notices the new key and keeps syncing
        owner.addHost("new-after-removal", "10.1.0.3", "");
        owner.vaults.syncAll();
        assertTrue(owner2.vaults.syncAll().isEmpty());
        owner2.store.setActiveVault(team);
        assertTrue(owner2.labels().contains("new-after-removal"));
    }

    @Test
    public void syncingNeedsPremium() throws Exception {
        startServer();
        Phone free = new Phone("free", "free-user@example.com", mUrl);
        free.vaults.createAccount("free user password");
        try {
            free.vaults.createTeam("Nope");
            throw new AssertionError("team without Premium");
        } catch (TerminowVaults.VaultException e) {
            assertEquals(402, e.status);
        }
        try {
            free.vaults.enablePersonalSync();
            throw new AssertionError("personal sync without Premium");
        } catch (TerminowVaults.VaultException e) {
            assertEquals(402, e.status);
        }
    }

    // -------------------------------------------------------------------------------------------

    private static JSONObject host(String label, String address, String password) throws Exception {
        return new JSONObject().put("label", label).put("address", address).put("port", 22).put("username", "root").put("password", password);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

}
