package com.termux.app.terminow;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the hosts and snippets shown on the {@link TerminowHome} screen.
 */
public class TerminowStore {

    public static class Host {
        public String label = "";
        public String address = "";
        public int port = 22;
        public String username = "";
        public boolean mosh;
        /** The password typed in when the host asks for it, empty to be asked in the terminal. */
        public String password = "";
        /** The path of the private key file to log in with, empty to use the default keys. */
        public String keyPath = "";

        /** The name shown for the host and used as the name of its sessions. */
        @NonNull
        public String getTitle() {
            return label.isEmpty() ? address : label;
        }

        /** The command run in a new session to connect to the host. */
        @NonNull
        public String getCommand() {
            String target = (username.isEmpty() ? "" : username + "@") + address;
            String sshOptions = (keyPath.isEmpty() ? "" : " -i " + keyPath) + (port == 22 ? "" : " -p " + port);
            if (mosh)
                return "mosh" + (sshOptions.isEmpty() ? "" : " --ssh='ssh" + sshOptions + "'") + " " + target;
            else
                return "ssh" + sshOptions + " " + target;
        }
    }

    public static class Snippet {
        public String name = "";
        public String command = "";

        @NonNull
        public String getTitle() {
            return name.isEmpty() ? command : name;
        }
    }

    /** The address and username are typed into a shell, so only allow characters that are safe unquoted. */
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:%-]*");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");
    private static final Pattern KEY_PATH_PATTERN = Pattern.compile("[A-Za-z0-9._~/][A-Za-z0-9._~/+@-]*");

    /** The alias of the key in the Android keystore that the host passwords are encrypted with. */
    private static final String PASSWORD_KEY_ALIAS = "terminow_host_passwords";
    private static final String PASSWORD_CIPHER = "AES/GCM/NoPadding";
    private static final int PASSWORD_IV_LENGTH = 12;

    private static final String PREFERENCES_NAME = "terminow_home";
    private static final String KEY_HOSTS = "hosts";
    private static final String KEY_SNIPPETS = "snippets";

    private static final String LOG_TAG = "TerminowStore";

    private final SharedPreferences mPreferences;

    public TerminowStore(@NonNull Context context) {
        mPreferences = context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isValidAddress(String address) {
        return address != null && ADDRESS_PATTERN.matcher(address).matches();
    }

    public static boolean isValidUsername(String username) {
        return username != null && (username.isEmpty() || USERNAME_PATTERN.matcher(username).matches());
    }

    public static boolean isValidKeyPath(String keyPath) {
        return keyPath != null && (keyPath.isEmpty() || KEY_PATH_PATTERN.matcher(keyPath).matches());
    }

    public static boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }

    @NonNull
    public List<Host> getHosts() {
        List<Host> hosts = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(KEY_HOSTS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Host host = new Host();
                host.label = object.optString("label");
                host.address = object.optString("address");
                host.port = object.optInt("port", 22);
                host.username = object.optString("username");
                host.mosh = object.optBoolean("mosh");
                host.keyPath = object.optString("keyPath");
                host.password = decryptPassword(object.optString("password"));
                // Never run anything from storage that would not have passed validation when saved
                if (isValidAddress(host.address) && isValidUsername(host.username) && isValidPort(host.port) &&
                    isValidKeyPath(host.keyPath))
                    hosts.add(host);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load hosts", e);
        }
        return hosts;
    }

    public void setHosts(@NonNull List<Host> hosts) {
        JSONArray array = new JSONArray();
        try {
            for (Host host : hosts) {
                JSONObject object = new JSONObject();
                object.put("label", host.label);
                object.put("address", host.address);
                object.put("port", host.port);
                object.put("username", host.username);
                object.put("mosh", host.mosh);
                object.put("keyPath", host.keyPath);
                object.put("password", encryptPassword(host.password));
                array.put(object);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save hosts", e);
            return;
        }
        mPreferences.edit().putString(KEY_HOSTS, array.toString()).apply();
    }

    @NonNull
    private static SecretKey getPasswordKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(PASSWORD_KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry)
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        keyGenerator.init(new KeyGenParameterSpec.Builder(PASSWORD_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return keyGenerator.generateKey();
    }

    /** Encrypt a password with a key that never leaves the Android keystore of the device. */
    @NonNull
    private static String encryptPassword(@NonNull String password) {
        if (password.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance(PASSWORD_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, getPasswordKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            byte[] data = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, data, 0, iv.length);
            System.arraycopy(encrypted, 0, data, iv.length, encrypted.length);
            return Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (GeneralSecurityException | IOException e) {
            // Do not fall back to storing the password unencrypted
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to encrypt password, it is not saved", e);
            return "";
        }
    }

    @NonNull
    private static String decryptPassword(@NonNull String encryptedPassword) {
        if (encryptedPassword.isEmpty()) return "";
        try {
            byte[] data = Base64.decode(encryptedPassword, Base64.NO_WRAP);
            if (data.length <= PASSWORD_IV_LENGTH) return "";
            Cipher cipher = Cipher.getInstance(PASSWORD_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, getPasswordKey(), new GCMParameterSpec(128, data, 0, PASSWORD_IV_LENGTH));
            return new String(cipher.doFinal(data, PASSWORD_IV_LENGTH, data.length - PASSWORD_IV_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to decrypt password", e);
            return "";
        }
    }

    @NonNull
    public List<Snippet> getSnippets() {
        List<Snippet> snippets = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(KEY_SNIPPETS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Snippet snippet = new Snippet();
                snippet.name = object.optString("name");
                snippet.command = object.optString("command");
                if (!snippet.command.isEmpty())
                    snippets.add(snippet);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load snippets", e);
        }
        return snippets;
    }

    public void setSnippets(@NonNull List<Snippet> snippets) {
        JSONArray array = new JSONArray();
        try {
            for (Snippet snippet : snippets) {
                JSONObject object = new JSONObject();
                object.put("name", snippet.name);
                object.put("command", snippet.command);
                array.put(object);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save snippets", e);
            return;
        }
        mPreferences.edit().putString(KEY_SNIPPETS, array.toString()).apply();
    }

}
