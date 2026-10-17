package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Personal and Team vaults: setting up the vault password, syncing, and the members of a
 * team. All of it runs on a background thread and talks to vault.php on cp.accmove.com, which
 * only ever gets ciphertext, see {@link TerminowVaultCrypto}.
 */
public class TerminowVaults {

    public static final String VAULT_URL = "https://cp.accmove.com/terminow/vault.php";
    private static final int TIMEOUT_MS = 30000;

    /** A failure to show to the user, with the HTTP status of the server if there was one. */
    public static class VaultException extends Exception {
        public final int status;
        public final JSONObject body;

        VaultException(int status, @NonNull String message, @NonNull JSONObject body) {
            super(message);
            this.status = status;
            this.body = body;
        }

        VaultException(@NonNull String message) {
            this(0, message, new JSONObject());
        }
    }

    /** A vault as the server listed it. */
    public static class Info {
        public String id = "";
        public String kind = "";
        public String name = "";
        public String owner = "";
        public String role = "";
        public int version;
        /** Whether the owner gave this account the key of the vault. */
        public boolean hasKey;

        public boolean isPersonal() {
            return "personal".equals(kind);
        }

        public boolean isOwner() {
            return "owner".equals(role);
        }

        /** Where the store keeps the lists of the vault, "" for the personal one. */
        @NonNull
        public String scope() {
            return isPersonal() ? "" : id;
        }

        @NonNull
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("kind", kind).put("name", name).put("owner", owner).put("role", role)
                .put("version", version).put("hasKey", hasKey);
        }

        @NonNull
        static Info fromJson(@NonNull JSONObject object) {
            Info info = new Info();
            info.id = object.optString("id");
            info.kind = object.optString("kind");
            info.name = object.optString("name");
            info.owner = object.optString("owner");
            info.role = object.optString("role");
            info.version = object.optInt("version");
            info.hasKey = object.optBoolean("hasKey", !object.isNull("wrappedKey") && object.has("wrappedKey"));
            return info;
        }
    }

    /** Gives the Firebase ID token of the signed-in user, and saves the session when it was refreshed. */
    public interface Tokens {
        @NonNull String getIdToken() throws VaultException;

        @NonNull String getEmail();
    }

    private final TerminowStore mStore;
    private final Tokens mTokens;
    private String mUrl = VAULT_URL;

    public TerminowVaults(@NonNull TerminowStore store, @NonNull Tokens tokens) {
        mStore = store;
        mTokens = tokens;
    }

    /** For the tests, a local server instead of cp.accmove.com. */
    void setUrl(@NonNull String url) {
        mUrl = url;
    }

    // ---------------------------------------------------------------------------------------
    // The server
    // ---------------------------------------------------------------------------------------

    @NonNull
    JSONObject call(@NonNull JSONObject request) throws VaultException {
        String token = mTokens.getIdToken();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(mUrl).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            String text = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            JSONObject body;
            try {
                body = text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (JSONException e) {
                throw new VaultException(status, "The server answered with HTTP " + status + " and no valid data", new JSONObject());
            }
            if (status >= 200 && status < 300) return body;
            throw new VaultException(status, body.optString("error", "HTTP " + status), body);
        } catch (IOException e) {
            throw new VaultException("No connection: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @NonNull
    private static String read(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }

    @NonNull
    private static JSONObject request(@NonNull String action, @NonNull Object... keyValues) throws VaultException {
        try {
            JSONObject object = new JSONObject().put("action", action);
            for (int i = 0; i + 1 < keyValues.length; i += 2) object.put((String) keyValues[i], keyValues[i + 1]);
            return object;
        } catch (JSONException e) {
            throw new VaultException(String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------------------------------
    // The account and the list of vaults
    // ---------------------------------------------------------------------------------------

    /** What the server knows about the account, see vault_lib.php "me". The list of vaults is kept. */
    @NonNull
    public JSONObject refresh() throws VaultException {
        JSONObject me = call(request("me"));
        JSONArray vaults = me.optJSONArray("vaults");
        JSONArray kept = new JSONArray();
        Set<String> ids = new HashSet<>();
        try {
            for (int i = 0; vaults != null && i < vaults.length(); i++) {
                Info info = Info.fromJson(vaults.getJSONObject(i));
                kept.put(info.toJson());
                ids.add(info.id);
            }
        } catch (JSONException e) {
            throw new VaultException(String.valueOf(e.getMessage()));
        }
        // Teams that this account left or was removed from are removed from the phone too
        for (Info old : getVaults())
            if (!old.isPersonal() && !ids.contains(old.id)) mStore.deleteVaultData(old.id);
        mStore.setVaults(kept);
        // Another account signed in on this phone: its vault password is needed again
        JSONObject local = mStore.getVaultAccount();
        JSONObject account = me.optJSONObject("account");
        if (local != null && (!local.optString("email").equalsIgnoreCase(me.optString("email")) || account == null ||
            !local.optString("publicKey").equals(account.optString("publicKey"))))
            mStore.setVaultAccount(null, null, null);
        return me;
    }

    @NonNull
    public List<Info> getVaults() {
        List<Info> list = new ArrayList<>();
        JSONArray array = mStore.getVaults();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object != null) list.add(Info.fromJson(object));
        }
        return list;
    }

    @Nullable
    public Info getPersonal() {
        for (Info info : getVaults()) if (info.isPersonal()) return info;
        return null;
    }

    @Nullable
    public Info get(@NonNull String id) {
        for (Info info : getVaults()) if (info.id.equals(id)) return info;
        return null;
    }

    /** Whether the vault password was entered on this phone for the signed-in account. */
    public boolean isUnlocked() {
        JSONObject account = mStore.getVaultAccount();
        return account != null && account.optString("email").equalsIgnoreCase(mTokens.getEmail());
    }

    @NonNull
    private PrivateKey privateKey() throws VaultException {
        JSONObject account = mStore.getVaultAccount();
        if (account == null) throw new VaultException("Enter your vault password first");
        try {
            return TerminowVaultCrypto.privateKey(TerminowPpk.decodeBase64(account.optString("privateKey")));
        } catch (GeneralSecurityException | TerminowPpk.PpkException e) {
            mStore.setVaultAccount(null, null, null);
            throw new VaultException("Enter your vault password again");
        }
    }

    @NonNull
    private String myPublicKey() throws VaultException {
        JSONObject account = mStore.getVaultAccount();
        if (account == null) throw new VaultException("Enter your vault password first");
        return account.optString("publicKey");
    }

    /** Set up the vault password of an account that has none, and keep its keys on this phone. */
    public void createAccount(@NonNull String password) throws VaultException {
        try {
            TerminowVaultCrypto.AccountKeys keys = TerminowVaultCrypto.createAccount(password);
            call(request("account.create", "publicKey", keys.publicKey, "encryptedPrivateKey", keys.encryptedPrivateKey,
                "salt", keys.salt, "iterations", keys.iterations));
            mStore.setVaultAccount(mTokens.getEmail(), keys.publicKey, keys.privateKey.getEncoded());
        } catch (GeneralSecurityException e) {
            throw new VaultException("Could not make the keys: " + e.getMessage());
        }
    }

    /** Enter the vault password on this phone. */
    public void unlock(@NonNull String password) throws VaultException {
        JSONObject me = refresh();
        JSONObject account = me.optJSONObject("account");
        if (account == null) throw new VaultException("This account has no vault password yet");
        try {
            PrivateKey key = TerminowVaultCrypto.unlock(account.optString("encryptedPrivateKey"), account.optString("salt"),
                account.optInt("iterations"), password);
            mStore.setVaultAccount(mTokens.getEmail(), account.optString("publicKey"), key.getEncoded());
        } catch (TerminowVaultCrypto.DecryptException e) {
            throw new VaultException("Wrong vault password");
        }
    }

    /** A new vault password, with the same keys. */
    public void changePassword(@NonNull String password) throws VaultException {
        try {
            TerminowVaultCrypto.AccountKeys keys = new TerminowVaultCrypto.AccountKeys();
            keys.privateKey = privateKey();
            TerminowVaultCrypto.protectPrivateKey(keys, password);
            call(request("account.password", "encryptedPrivateKey", keys.encryptedPrivateKey, "salt", keys.salt,
                "iterations", keys.iterations));
        } catch (GeneralSecurityException e) {
            throw new VaultException(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Start again after the vault password was forgotten: new keys, the synced personal vault is
     * deleted from the server (this phone keeps its own lists and uploads them again), and team
     * owners must give access again.
     */
    public void resetAccount(@NonNull String password) throws VaultException {
        try {
            TerminowVaultCrypto.AccountKeys keys = TerminowVaultCrypto.createAccount(password);
            call(request("account.reset", "publicKey", keys.publicKey, "encryptedPrivateKey", keys.encryptedPrivateKey,
                "salt", keys.salt, "iterations", keys.iterations));
            mStore.setVaultAccount(null, null, null);
            mStore.setVaultAccount(mTokens.getEmail(), keys.publicKey, keys.privateKey.getEncoded());
            mStore.setVaultUnsynced("");
            refresh();
        } catch (GeneralSecurityException e) {
            throw new VaultException("Could not make the keys: " + e.getMessage());
        }
    }

    /** Forget the keys on this phone. The lists of the vaults stay. */
    public void lock() {
        mStore.setVaultAccount(null, null, null);
    }

    @NonNull
    public String getFingerprint() {
        JSONObject account = mStore.getVaultAccount();
        return account == null ? "" : TerminowVaultCrypto.fingerprint(account.optString("publicKey"));
    }

    // ---------------------------------------------------------------------------------------
    // Syncing
    // ---------------------------------------------------------------------------------------

    /** Upload the personal lists of this phone as the personal vault, or merge them into the synced one. */
    public void enablePersonalSync() throws VaultException {
        refresh();
        Info personal = getPersonal();
        if (personal != null) {
            // Everything of this phone counts as changed, so it is merged in, not replaced
            mStore.setVaultUnsynced("");
            mStore.setVaultBase(personal.id, 0);
            sync(personal);
            return;
        }
        createVault("personal", "Personal");
    }

    /** Create a vault on the server with the current lists of its scope, an empty one for a new team. */
    @NonNull
    private String createVault(@NonNull String kind, @NonNull String name) throws VaultException {
        String id = TerminowVaultCrypto.newVaultId();
        byte[] key = TerminowVaultCrypto.newVaultKey();
        try {
            boolean personal = "personal".equals(kind);
            int change = mStore.getVaultChange(personal ? "" : id);
            JSONObject data = personal ? mStore.exportVault("") : TerminowVaultData.empty();
            JSONObject result = call(request("vault.create", "id", id, "kind", kind, "name", name,
                "wrappedKey", TerminowVaultCrypto.wrap(key, myPublicKey()),
                "data", TerminowVaultCrypto.encryptVault(key, id, data.toString())));
            mStore.setVaultKey(id, key);
            mStore.setVaultBase(id, result.optInt("version", 1));
            mStore.setVaultSynced(personal ? "" : id, change);
            refresh();
            return id;
        } catch (GeneralSecurityException | JSONException | TerminowVaultCrypto.DecryptException e) {
            throw new VaultException(String.valueOf(e.getMessage()));
        }
    }

    /** The key of a vault: the one kept on this phone, or the one the server has wrapped for this account. */
    @NonNull
    private byte[] keyFor(@NonNull String id, @Nullable String wrapped, boolean fresh) throws VaultException {
        byte[] key = fresh ? null : mStore.getVaultKey(id);
        if (key != null) return key;
        if (wrapped == null || wrapped.isEmpty()) throw new VaultException("The owner has not given you access to this vault yet");
        try {
            key = TerminowVaultCrypto.unwrap(wrapped, privateKey());
        } catch (TerminowVaultCrypto.DecryptException e) {
            throw new VaultException("This vault was given to another key of your account. Ask the owner to give access again.");
        }
        mStore.setVaultKey(id, key);
        return key;
    }

    /**
     * Sync a vault: upload the changes of this phone, take the changes of the others, or merge
     * both when both changed. Retries when someone else saved at the same time.
     *
     * @return Whether something changed on this phone.
     */
    public boolean sync(@NonNull Info info) throws VaultException {
        String scope = info.scope();
        if (scope.isEmpty()) mStore.checkKeyFiles();
        for (int attempt = 0; attempt < 3; attempt++) {
            JSONObject remote = call(request("vault.get", "id", info.id));
            String wrapped = remote.isNull("wrappedKey") ? null : remote.optString("wrappedKey");
            int remoteVersion = remote.optInt("version");
            int base = mStore.getVaultBase(info.id);
            int change = mStore.getVaultChange(scope);
            boolean dirty = mStore.isVaultDirty(scope);
            if (remoteVersion == base && !dirty) return false;
            try {
                byte[] key = keyFor(info.id, wrapped, false);
                JSONObject result;
                if (remoteVersion == base) {
                    result = mStore.exportVault(scope);
                } else {
                    String plain;
                    try {
                        plain = TerminowVaultCrypto.decryptVault(key, info.id, remote.optString("data"));
                    } catch (TerminowVaultCrypto.DecryptException e) {
                        // The key was rotated after a member was removed
                        key = keyFor(info.id, wrapped, true);
                        plain = TerminowVaultCrypto.decryptVault(key, info.id, remote.optString("data"));
                    }
                    JSONObject remoteData = TerminowVaultData.parse(plain);
                    if (!dirty) {
                        mStore.importVault(scope, remoteData);
                        mStore.setVaultBase(info.id, remoteVersion);
                        mStore.setVaultSynced(scope, change);
                        return true;
                    }
                    result = TerminowVaultData.merge(remoteData, mStore.exportVault(scope));
                }
                try {
                    JSONObject put = call(request("vault.put", "id", info.id, "baseVersion", remoteVersion,
                        "data", TerminowVaultCrypto.encryptVault(key, info.id, result.toString())));
                    mStore.importVault(scope, result);
                    mStore.setVaultBase(info.id, put.optInt("version", remoteVersion + 1));
                    mStore.setVaultSynced(scope, change);
                    return remoteVersion != base;
                } catch (VaultException e) {
                    if (e.status != 409) throw e;
                    // Someone saved in between: try again with their version
                }
            } catch (TerminowVaultCrypto.DecryptException e) {
                throw new VaultException("Could not decrypt the vault: " + e.getMessage());
            } catch (JSONException e) {
                throw new VaultException(String.valueOf(e.getMessage()));
            } catch (GeneralSecurityException e) {
                throw new VaultException(String.valueOf(e.getMessage()));
            }
        }
        throw new VaultException("The vault keeps changing, try again");
    }

    /** Sync every vault that this account has the key of, returning the errors by vault name. */
    @NonNull
    public List<String> syncAll() throws VaultException {
        refresh();
        List<String> errors = new ArrayList<>();
        for (Info info : getVaults()) {
            if (!info.hasKey) continue;
            try {
                sync(info);
            } catch (VaultException e) {
                errors.add(info.name + ": " + e.getMessage());
            }
        }
        return errors;
    }

    // ---------------------------------------------------------------------------------------
    // Teams
    // ---------------------------------------------------------------------------------------

    @NonNull
    public String createTeam(@NonNull String name) throws VaultException {
        return createVault("team", name);
    }

    /** The members of a team, see vault_lib.php "team.members". */
    @NonNull
    public JSONArray getMembers(@NonNull String id) throws VaultException {
        JSONArray members = call(request("team.members", "id", id)).optJSONArray("members");
        return members != null ? members : new JSONArray();
    }

    public void invite(@NonNull String id, @NonNull String email) throws VaultException {
        call(request("team.invite", "id", id, "email", email.trim()));
    }

    /** Give a member that set up a vault password the key of the team, wrapped for the public key that was shown. */
    public void grant(@NonNull String id, @NonNull String email, @NonNull String publicKey) throws VaultException {
        Info info = get(id);
        if (info == null) throw new VaultException("No such vault");
        JSONObject remote = call(request("vault.get", "id", id));
        byte[] key = keyFor(id, remote.isNull("wrappedKey") ? null : remote.optString("wrappedKey"), false);
        try {
            call(request("team.grant", "id", id, "email", email, "publicKey", publicKey,
                "wrappedKey", TerminowVaultCrypto.wrap(key, publicKey)));
        } catch (GeneralSecurityException | TerminowVaultCrypto.DecryptException e) {
            throw new VaultException("The member's key is not valid: " + e.getMessage());
        }
    }

    /**
     * Remove a member, then encrypt the team with a new key that only the remaining members
     * get, so that the removed member can not read what is added later.
     */
    public void removeMember(@NonNull String id, @NonNull String email) throws VaultException {
        Info info = get(id);
        if (info == null) throw new VaultException("No such vault");
        sync(info);
        call(request("team.remove", "id", id, "email", email));
        for (int attempt = 0; attempt < 3; attempt++) {
            JSONObject remote = call(request("vault.get", "id", id));
            int version = remote.optInt("version");
            byte[] oldKey = keyFor(id, remote.isNull("wrappedKey") ? null : remote.optString("wrappedKey"), false);
            byte[] newKey = TerminowVaultCrypto.newVaultKey();
            try {
                JSONObject data = TerminowVaultData.parse(TerminowVaultCrypto.decryptVault(oldKey, id, remote.optString("data")));
                JSONObject wrappedKeys = new JSONObject();
                JSONArray members = getMembers(id);
                for (int i = 0; i < members.length(); i++) {
                    JSONObject member = members.getJSONObject(i);
                    if (!"active".equals(member.optString("status")) || member.isNull("publicKey")) continue;
                    wrappedKeys.put(member.getString("email"), TerminowVaultCrypto.wrap(newKey, member.getString("publicKey")));
                }
                JSONObject result = call(request("team.rotate", "id", id, "baseVersion", version,
                    "data", TerminowVaultCrypto.encryptVault(newKey, id, data.toString()), "wrappedKeys", wrappedKeys));
                mStore.setVaultKey(id, newKey);
                mStore.importVault(id, data);
                mStore.setVaultBase(id, result.optInt("version", version + 1));
                return;
            } catch (VaultException e) {
                if (e.status != 409) throw e;
            } catch (GeneralSecurityException | JSONException | TerminowVaultCrypto.DecryptException e) {
                throw new VaultException(String.valueOf(e.getMessage()));
            }
        }
        throw new VaultException("The vault keeps changing, the member was removed but the key was not changed. Try again.");
    }

    /** Leave a team, or delete it when this account owns it. The copy on this phone is removed. */
    public void leaveOrDelete(@NonNull Info info) throws VaultException {
        if (info.isOwner()) call(request("vault.delete", "id", info.id));
        else call(request("team.remove", "id", info.id, "email", mTokens.getEmail()));
        mStore.deleteVaultData(info.id);
        refresh();
    }

}
