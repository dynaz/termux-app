package com.termux.app.terminow;

import android.app.Activity;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Biometric SSH keys: P-256 keys made in the Android keystore (in StrongBox if the phone has it)
 * that can not be exported, and that sign only after a strong biometric for that one signature.
 *
 * ssh uses them through the agent of the app, see {@link TerminowSshAgent}, on a socket in the
 * private files of the app that only this app can open; the public key is in
 * `~/.ssh/terminow-bio-ID.pub`, which hosts are given as their key. The file manager signs with
 * them directly.
 */
public final class TerminowBiometricKeys {

    private static final String LOG_TAG = "TerminowBiometricKeys";
    /** The socket of the agent, given to ssh as `IdentityAgent`. */
    public static final String AGENT_SOCKET = TermuxConstants.TERMUX_FILES_DIR_PATH + "/terminow-agent.sock";
    static final String ALIAS_PREFIX = "terminow-bio-";
    /** The public key file of a key that ssh uses through the agent, a biometric or a FIDO2 key, as a host has it. */
    private static final Pattern KEY_PATH = Pattern.compile("(?:~|" + Pattern.quote(TermuxConstants.TERMUX_HOME_DIR_PATH) +
        ")/\\.ssh/terminow-(bio|fido)-([a-z0-9]{8,40})\\.pub");
    private static final long PROMPT_TIMEOUT_SECONDS = 120;

    @Nullable private static LocalServerSocket sServer;
    private static WeakReference<Activity> sActivity = new WeakReference<>(null);

    private TerminowBiometricKeys() {}

    /** Whether a key path is the public key of a biometric or FIDO2 key, which ssh uses through the agent. */
    public static boolean isAgentKeyPath(@NonNull String keyPath) {
        return KEY_PATH.matcher(keyPath).matches();
    }

    /** The key of the agent for a key path, {@code null} if it is not one or not on this phone. */
    @Nullable
    public static TerminowSshAgent.Key agentKeyFor(@NonNull String keyPath) {
        Matcher matcher = KEY_PATH.matcher(keyPath);
        if (!matcher.matches()) return null;
        if (matcher.group(1).equals("bio")) return keyFor(matcher.group(2));
        TerminowFidoKeys.Credential credential = TerminowFidoKeys.find(matcher.group(2));
        return credential != null ? TerminowFidoKeys.keyFor(credential) : null;
    }

    /** Whether the phone can make biometric keys: Android 11 and a strong biometric that is set up. */
    public static boolean isSupported(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        BiometricManager manager = context.getSystemService(BiometricManager.class);
        return manager != null && manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    @NonNull
    static File sshDir() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".ssh");
    }

    @NonNull
    static File publicFile(@NonNull String id) {
        return new File(sshDir(), ALIAS_PREFIX + id + ".pub");
    }

    @NonNull
    private static KeyStore keyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return keyStore;
    }

    /** Make a key and its public key file. Returns its id. */
    @RequiresApi(api = Build.VERSION_CODES.R)
    @NonNull
    public static String create(@NonNull String comment) throws Exception {
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 46656), 36);
        id = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String alias = ALIAS_PREFIX + id;
        try {
            generate(alias, true);
        } catch (StrongBoxUnavailableException e) {
            generate(alias, false);
        }
        ECPublicKey publicKey = (ECPublicKey) keyStore().getCertificate(alias).getPublicKey();
        File file = publicFile(id);
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write((TerminowSshWire.publicKeyLine(TerminowSshWire.ecdsaP256Blob(publicKey), comment) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return id;
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private static void generate(@NonNull String alias, boolean strongBox) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // A biometric for every signature, never a time window
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongBox)
            .build());
        generator.generateKeyPair();
    }

    /** The ids of the keys in the keystore. */
    @NonNull
    public static List<String> ids() {
        List<String> ids = new ArrayList<>();
        try {
            Enumeration<String> aliases = keyStore().aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (alias.startsWith(ALIAS_PREFIX)) ids.add(alias.substring(ALIAS_PREFIX.length()));
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to list biometric keys", e);
        }
        Collections.sort(ids);
        return ids;
    }

    /** The id of a key file of a biometric key, {@code null} if it is not one. */
    @Nullable
    public static String idOf(@NonNull String keyPath) {
        Matcher matcher = KEY_PATH.matcher(keyPath);
        return matcher.matches() && matcher.group(1).equals("bio") ? matcher.group(2) : null;
    }

    /** Delete a key from the keystore, and its public key file and certificate. */
    public static void delete(@NonNull String id) {
        try {
            keyStore().deleteEntry(ALIAS_PREFIX + id);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to delete a biometric key", e);
        }
        File file = publicFile(id);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(file.getPath() + "-cert.pub").delete();
    }

    /** The keys of the agent: the biometric keys that have their public key file, and the FIDO2 keys. */
    @NonNull
    static List<TerminowSshAgent.Key> agentKeys() {
        List<TerminowSshAgent.Key> keys = new ArrayList<>();
        for (String id : ids()) {
            TerminowSshAgent.Key key = publicFile(id).isFile() ? keyFor(id) : null;
            if (key != null) keys.add(key);
        }
        keys.addAll(TerminowFidoKeys.agentKeys());
        return keys;
    }

    /** The key with the id, {@code null} if it is not in the keystore. */
    @Nullable
    public static TerminowSshAgent.Key keyFor(@NonNull String id) {
        String alias = ALIAS_PREFIX + id;
        byte[] blob;
        try {
            Certificate certificate = keyStore().getCertificate(alias);
            if (certificate == null) return null;
            blob = TerminowSshWire.ecdsaP256Blob((ECPublicKey) certificate.getPublicKey());
        } catch (Exception e) {
            return null;
        }
        String comment = commentOf(id);
        return new TerminowSshAgent.Key() {
            @NonNull
            @Override
            public byte[] publicBlob() {
                return blob;
            }

            @NonNull
            @Override
            public String comment() {
                return comment;
            }

            @Nullable
            @Override
            public byte[] sign(@NonNull byte[] data, int flags) throws Exception {
                return signWithBiometric(alias, comment, data);
            }
        };
    }

    /** The comment of the public key file, which is the name of the key. */
    @NonNull
    static String commentOf(@NonNull String id) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(publicFile(id).toPath()), StandardCharsets.UTF_8);
            TerminowSshWire.PublicKeyLine line = TerminowSshWire.parseLine(text);
            if (line != null && !line.comment.isEmpty()) return line.comment;
        } catch (IOException | RuntimeException e) {
            // The id then
        }
        return ALIAS_PREFIX + id;
    }

    // ---------------------------------------------------------------------------------------
    // Signing
    // ---------------------------------------------------------------------------------------

    /** The activity that the prompts are shown over, set while TermiNow is shown. */
    public static void setActivity(@NonNull Activity activity) {
        sActivity = new WeakReference<>(activity);
    }

    /** The TermiNow screen to ask the user on, {@code null} if it is not shown. */
    @Nullable
    static Activity currentActivity() {
        Activity activity = sActivity.get();
        return activity == null || activity.isFinishing() || activity.isDestroyed() ? null : activity;
    }

    /**
     * Sign after a biometric: the keystore only lets this one signature be made with the key that
     * the biometric unlocked. Blocks the calling thread, never the main thread. {@code null} if
     * the user cancelled, there is no TermiNow screen to ask on, or it timed out.
     */
    @Nullable
    static byte[] signWithBiometric(@NonNull String alias, @NonNull String name, @NonNull byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        Activity activity = currentActivity();
        if (activity == null) {
            Logger.logInfo(LOG_TAG, "A biometric key was asked to sign while TermiNow is not shown");
            return null;
        }
        PrivateKey key = (PrivateKey) keyStore().getKey(alias, null);
        if (key == null) return null;
        Signature signature = Signature.getInstance("SHA256withECDSA");
        // Throws KeyPermanentlyInvalidatedException after fingerprints were added or removed
        signature.initSign(key);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<byte[]> result = new AtomicReference<>();
        CancellationSignal cancel = new CancellationSignal();
        activity.runOnUiThread(() -> {
            try {
                BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                    .setTitle(activity.getString(R.string.terminow_biometric_sign_title, name))
                    .setSubtitle(activity.getString(R.string.terminow_biometric_sign_subtitle))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButton(activity.getString(android.R.string.cancel), activity.getMainExecutor(), (dialog, which) -> done.countDown())
                    .build();
                prompt.authenticate(new BiometricPrompt.CryptoObject(signature), cancel, activity.getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult authentication) {
                            try {
                                Signature unlocked = authentication.getCryptoObject().getSignature();
                                unlocked.update(data);
                                result.set(TerminowSshWire.ecdsaSignatureFromDer(unlocked.sign()));
                            } catch (Exception e) {
                                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to sign with a biometric key", e);
                            }
                            done.countDown();
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            done.countDown();
                        }
                    });
            } catch (RuntimeException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to show the biometric prompt", e);
                done.countDown();
            }
        });
        if (!done.await(PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) cancel.cancel();
        return result.get();
    }

    // ---------------------------------------------------------------------------------------
    // The agent
    // ---------------------------------------------------------------------------------------

    /** Start the agent once for the process, for the biometric and FIDO2 keys. */
    public static synchronized void startAgent() {
        if (sServer != null) return;
        try {
            File file = new File(AGENT_SOCKET);
            // A socket of an earlier process of the app
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            LocalSocket socket = new LocalSocket();
            socket.bind(new LocalSocketAddress(AGENT_SOCKET, LocalSocketAddress.Namespace.FILESYSTEM));
            LocalServerSocket server = new LocalServerSocket(socket.getFileDescriptor());
            sServer = server;
            Thread thread = new Thread(() -> accept(server), "TerminowSshAgent");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start the ssh agent", e);
        }
    }

    private static void accept(@NonNull LocalServerSocket server) {
        while (true) {
            LocalSocket connection;
            try {
                connection = server.accept();
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "The ssh agent stopped", e);
                return;
            }
            Thread thread = new Thread(() -> {
                try (LocalSocket client = connection; InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream()) {
                    // Only the programs of this app, like ssh in its terminal
                    if (client.getPeerCredentials().getUid() != android.os.Process.myUid()) return;
                    TerminowSshAgent.serve(in, out, TerminowBiometricKeys::agentKeys);
                } catch (IOException e) {
                    // The client went away
                }
            }, "TerminowSshAgentClient");
            thread.setDaemon(true);
            thread.start();
        }
    }

}
