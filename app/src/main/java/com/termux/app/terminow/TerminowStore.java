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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        /** The name of the group that the host is in, empty if it is not in a group. */
        public String group = "";

        /** The name shown for the host and used as the name of its sessions. */
        @NonNull
        public String getTitle() {
            return label.isEmpty() ? address : label;
        }

        @NonNull
        private String getTarget() {
            return (username.isEmpty() ? "" : username + "@") + address;
        }

        @NonNull
        private String getSshOptions(int keepAliveInterval, int keepAliveCountMax) {
            return (keyPath.isEmpty() ? "" : " -i " + keyPath) + (port == 22 ? "" : " -p " + port) +
                (keepAliveInterval <= 0 ? "" : " -o ServerAliveInterval=" + keepAliveInterval + " -o ServerAliveCountMax=" + keepAliveCountMax);
        }

        /** The command run in a new session to transfer files to and from the host with sftp. */
        @NonNull
        public String getSftpCommand(int keepAliveInterval, int keepAliveCountMax) {
            // The option for the port of sftp is upper case
            return "sftp" + (keyPath.isEmpty() ? "" : " -i " + keyPath) + (port == 22 ? "" : " -P " + port) +
                (keepAliveInterval <= 0 ? "" : " -o ServerAliveInterval=" + keepAliveInterval + " -o ServerAliveCountMax=" + keepAliveCountMax) +
                " " + getTarget();
        }

        /** The command run in a new session to only forward a port through the host, see {@link Forward#getOption()}. */
        @NonNull
        public String getForwardCommand(@NonNull Forward forward, int keepAliveInterval, int keepAliveCountMax) {
            return "ssh -N " + forward.getOption() + getSshOptions(keepAliveInterval, keepAliveCountMax) + " " + getTarget();
        }

        /**
         * The command run in a new session to connect to the host.
         *
         * @param keepAliveInterval The seconds for `ServerAliveInterval` of ssh, 0 to not set it.
         * @param keepAliveCountMax The count for `ServerAliveCountMax` of ssh.
         */
        @NonNull
        public String getCommand(int keepAliveInterval, int keepAliveCountMax) {
            String target = getTarget();
            String sshOptions = getSshOptions(keepAliveInterval, keepAliveCountMax);
            if (mosh)
                return "mosh" + (sshOptions.isEmpty() ? "" : " --ssh='ssh" + sshOptions + "'") + " " + target;
            else
                return "ssh" + sshOptions + " " + target;
        }
    }

    /** A port forwarded through a host with ssh. */
    public static class Forward {
        public static final String TYPE_LOCAL = "local";
        public static final String TYPE_REMOTE = "remote";
        public static final String TYPE_DYNAMIC = "dynamic";

        public String label = "";
        public String type = TYPE_LOCAL;
        /** The title of the host to forward through, see {@link Host#getTitle()}. */
        public String host = "";
        /** The port that is listened on, on this device or on the host for {@link #TYPE_REMOTE}. */
        public int port;
        /** The address that {@link #port} is listened on, empty for the default of ssh. */
        public String bindAddress = "";
        /** The address and port that connections are forwarded to, not used for {@link #TYPE_DYNAMIC}. */
        public String destinationAddress = "";
        public int destinationPort;

        public boolean isDynamic() {
            return TYPE_DYNAMIC.equals(type);
        }

        /** The ssh option for the forwarding, like `-L 8080:localhost:80`. */
        @NonNull
        public String getOption() {
            String listen = (bindAddress.isEmpty() ? "" : bindAddress + ":") + port;
            if (isDynamic())
                return "-D " + listen;
            return (TYPE_REMOTE.equals(type) ? "-R " : "-L ") + listen + ":" + destinationAddress + ":" + destinationPort;
        }

        @NonNull
        public String getTitle() {
            return label.isEmpty() ? getOption() : label;
        }
    }

    /** A connection that was started from the home screen. */
    public static class Log {
        public long time;
        public String title = "";
        public String command = "";
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
    private static final String KEY_GROUPS = "groups";
    private static final String KEY_FORWARDS = "forwards";
    private static final String KEY_LOGS = "logs";

    /** The number of connections that are kept in the logs. */
    private static final int LOGS_MAX = 200;
    private static final String KEY_KEEP_ALIVE_INTERVAL = "keep_alive_interval";
    private static final String KEY_KEEP_ALIVE_COUNT_MAX = "keep_alive_count_max";
    private static final String KEY_SCREENSHOTS_BLOCKED = "screenshots_blocked";
    private static final String KEY_START_IN_HOME = "start_in_home";

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
                host.group = object.optString("group");
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
                object.put("group", host.group);
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

    /** The seconds after which ssh checks that a host that sent nothing is still connected, 0 to not check. */
    public int getKeepAliveInterval() {
        return Math.max(0, mPreferences.getInt(KEY_KEEP_ALIVE_INTERVAL, 60));
    }

    public void setKeepAliveInterval(int seconds) {
        mPreferences.edit().putInt(KEY_KEEP_ALIVE_INTERVAL, seconds).apply();
    }

    /** The number of unanswered checks after which ssh disconnects from a host. */
    public int getKeepAliveCountMax() {
        return Math.max(1, mPreferences.getInt(KEY_KEEP_ALIVE_COUNT_MAX, 10));
    }

    public void setKeepAliveCountMax(int count) {
        mPreferences.edit().putInt(KEY_KEEP_ALIVE_COUNT_MAX, count).apply();
    }

    /** Whether local terminals are started in the home folder instead of the root folder. */
    public boolean shouldStartInHome() {
        return mPreferences.getBoolean(KEY_START_IN_HOME, false);
    }

    public void setStartInHome(boolean startInHome) {
        mPreferences.edit().putBoolean(KEY_START_IN_HOME, startInHome).apply();
    }

    public boolean areScreenshotsBlocked() {
        return mPreferences.getBoolean(KEY_SCREENSHOTS_BLOCKED, false);
    }

    public void setScreenshotsBlocked(boolean blocked) {
        mPreferences.edit().putBoolean(KEY_SCREENSHOTS_BLOCKED, blocked).apply();
    }

    public static boolean isValidForward(@NonNull Forward forward) {
        if (!isValidPort(forward.port)) return false;
        if (!forward.bindAddress.isEmpty() && !isValidAddress(forward.bindAddress)) return false;
        if (forward.isDynamic()) return true;
        return (Forward.TYPE_LOCAL.equals(forward.type) || Forward.TYPE_REMOTE.equals(forward.type)) &&
            isValidAddress(forward.destinationAddress) && isValidPort(forward.destinationPort);
    }

    @NonNull
    public List<Forward> getForwards() {
        List<Forward> forwards = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(KEY_FORWARDS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Forward forward = new Forward();
                forward.label = object.optString("label");
                forward.type = object.optString("type", Forward.TYPE_LOCAL);
                forward.host = object.optString("host");
                forward.port = object.optInt("port");
                forward.bindAddress = object.optString("bindAddress");
                forward.destinationAddress = object.optString("destinationAddress");
                forward.destinationPort = object.optInt("destinationPort");
                // Never run anything from storage that would not have passed validation when saved
                if (isValidForward(forward))
                    forwards.add(forward);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load forwards", e);
        }
        return forwards;
    }

    public void setForwards(@NonNull List<Forward> forwards) {
        JSONArray array = new JSONArray();
        try {
            for (Forward forward : forwards) {
                JSONObject object = new JSONObject();
                object.put("label", forward.label);
                object.put("type", forward.type);
                object.put("host", forward.host);
                object.put("port", forward.port);
                object.put("bindAddress", forward.bindAddress);
                object.put("destinationAddress", forward.destinationAddress);
                object.put("destinationPort", forward.destinationPort);
                array.put(object);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save forwards", e);
            return;
        }
        mPreferences.edit().putString(KEY_FORWARDS, array.toString()).apply();
    }

    /** Get the logs of the connections that were started, the newest first. */
    @NonNull
    public List<Log> getLogs() {
        List<Log> logs = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(KEY_LOGS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Log log = new Log();
                log.time = object.optLong("time");
                log.title = object.optString("title");
                log.command = object.optString("command");
                logs.add(log);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load logs", e);
        }
        return logs;
    }

    /** Add a connection to the logs. The command must not contain a password. */
    public void addLog(@NonNull String title, @NonNull String command) {
        try {
            JSONArray oldArray = new JSONArray(mPreferences.getString(KEY_LOGS, "[]"));
            JSONArray array = new JSONArray();
            JSONObject object = new JSONObject();
            object.put("time", System.currentTimeMillis());
            object.put("title", title);
            object.put("command", command);
            array.put(object);
            for (int i = 0; i < oldArray.length() && array.length() < LOGS_MAX; i++)
                array.put(oldArray.get(i));
            mPreferences.edit().putString(KEY_LOGS, array.toString()).apply();
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save log", e);
        }
    }

    public void clearLogs() {
        mPreferences.edit().remove(KEY_LOGS).apply();
    }

    /** Get the names of the groups that hosts can be put in. */
    @NonNull
    public List<String> getGroups() {
        List<String> groups = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(KEY_GROUPS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                String group = array.optString(i).trim();
                if (!group.isEmpty() && !groups.contains(group))
                    groups.add(group);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load groups", e);
        }
        return groups;
    }

    public void setGroups(@NonNull List<String> groups) {
        mPreferences.edit().putString(KEY_GROUPS, new JSONArray(groups).toString()).apply();
    }

    /**
     * Read the hosts of an ssh config file, like `~/.ssh/config`. The `Host` entries with a
     * pattern and the options of hosts other than `HostName`, `User`, `Port` and `IdentityFile`
     * are skipped.
     */
    @NonNull
    public static List<Host> readSshConfigHosts(@NonNull File configFile) throws IOException {
        List<Host> hosts = new ArrayList<>();
        List<Host> currentHosts = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // The keyword and its arguments are separated by whitespace or an equals sign
                String[] parts = line.split("\\s*=\\s*|\\s+", 2);
                if (parts.length < 2) continue;
                String keyword = parts[0].toLowerCase(Locale.ROOT);
                String value = parts[1].trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                    value = value.substring(1, value.length() - 1);

                if (keyword.equals("host")) {
                    currentHosts = new ArrayList<>();
                    for (String alias : value.split("\\s+")) {
                        // A pattern does not name a host to connect to
                        if (alias.isEmpty() || alias.contains("*") || alias.contains("?") || alias.startsWith("!")) continue;
                        Host host = new Host();
                        host.label = alias;
                        host.address = alias;
                        currentHosts.add(host);
                        hosts.add(host);
                    }
                } else if (keyword.equals("match")) {
                    currentHosts = new ArrayList<>();
                } else {
                    for (Host host : currentHosts) {
                        // The first value of an option is the one that ssh uses
                        if (keyword.equals("hostname") && host.address.equals(host.label)) {
                            host.address = value;
                        } else if (keyword.equals("user") && host.username.isEmpty()) {
                            host.username = value;
                        } else if (keyword.equals("port") && host.port == 22) {
                            try {
                                host.port = Integer.parseInt(value);
                            } catch (NumberFormatException e) {
                                host.port = -1;
                            }
                        } else if (keyword.equals("identityfile") && host.keyPath.isEmpty()) {
                            host.keyPath = value;
                        }
                    }
                }
            }
        }

        // Only return the hosts that could also have been saved from the host form
        List<Host> validHosts = new ArrayList<>();
        for (Host host : hosts) {
            if (!isValidKeyPath(host.keyPath))
                host.keyPath = "";
            if (isValidAddress(host.address) && isValidUsername(host.username) && isValidPort(host.port))
                validHosts.add(host);
        }
        return validHosts;
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
