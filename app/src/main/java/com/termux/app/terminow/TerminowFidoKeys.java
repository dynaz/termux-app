package com.termux.app.terminow;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.yubico.yubikit.android.YubiKitManager;
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration;
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable;
import com.yubico.yubikit.android.transport.usb.DeviceFilter;
import com.yubico.yubikit.android.transport.usb.UsbConfiguration;
import com.yubico.yubikit.core.YubiKeyDevice;
import com.yubico.yubikit.core.fido.CtapException;
import com.yubico.yubikit.core.fido.FidoConnection;
import com.yubico.yubikit.core.smartcard.SmartCardConnection;
import com.yubico.yubikit.fido.Cbor;
import com.yubico.yubikit.fido.ctap.ClientPin;
import com.yubico.yubikit.fido.ctap.Ctap2Session;
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocol;
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1;
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FIDO2 security keys over USB or NFC, like the `sk-ecdsa-sha2-nistp256@openssh.com` keys of
 * `ssh-keygen -t ecdsa-sk`: the private key never leaves the security key, which signs when it is
 * touched or tapped. The app keeps the credential id (the "key handle") and the public key, writes
 * the public key to `~/.ssh/terminow-fido-ID.pub`, and ssh signs through the agent of the app.
 *
 * Talking to the security key is done with YubiKit of Yubico, for any FIDO2 key over USB, not only
 * YubiKeys, and over NFC.
 */
public final class TerminowFidoKeys {

    private static final String LOG_TAG = "TerminowFidoKeys";
    /** The application of the keys, like `ssh-keygen -t ecdsa-sk` makes them. */
    static final String APPLICATION = "ssh:";
    static final String FILE_PREFIX = "terminow-fido-";
    private static final File STORE = new File(TermuxConstants.TERMUX_FILES_DIR_PATH, "terminow-fido-keys.json");
    private static final long DEVICE_TIMEOUT_SECONDS = 120;
    private static final int COSE_ALG_ES256 = -7;

    private TerminowFidoKeys() {}

    /** A credential of a security key, as kept by the app. */
    public static final class Credential {
        public final String id;
        public final String label;
        public final byte[] credentialId;
        public final byte[] publicBlob;
        public final long created;

        Credential(@NonNull String id, @NonNull String label, @NonNull byte[] credentialId, @NonNull byte[] publicBlob, long created) {
            this.id = id;
            this.label = label;
            this.credentialId = credentialId;
            this.publicBlob = publicBlob;
            this.created = created;
        }
    }

    /** What a new credential is made of, from the authenticator data of makeCredential. */
    static final class NewCredential {
        final byte[] credentialId;
        final byte[] x;
        final byte[] y;

        NewCredential(byte[] credentialId, byte[] x, byte[] y) {
            this.credentialId = credentialId;
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Read the attested credential data of makeCredential: the hash of the application, flags,
     * counter, AAGUID, the credential id and its COSE public key, which must be P-256 for ES256.
     */
    @NonNull
    static NewCredential parseAuthenticatorData(@NonNull byte[] data) {
        if (data.length < 55) throw new IllegalArgumentException("Authenticator data too short");
        int flags = data[32] & 0xff;
        if ((flags & 0x40) == 0) throw new IllegalArgumentException("No attested credential data");
        int offset = 37 + 16;
        int length = ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
        offset += 2;
        if (length <= 0 || offset + length > data.length) throw new IllegalArgumentException("Bad credential id length");
        byte[] credentialId = Arrays.copyOfRange(data, offset, offset + length);
        offset += length;
        Object decoded = Cbor.decode(data, offset, data.length - offset);
        if (!(decoded instanceof Map)) throw new IllegalArgumentException("No COSE key");
        Map<?, ?> cose = (Map<?, ?>) decoded;
        // kty EC2 (2), alg ES256 (-7), crv P-256 (1)
        if (!Integer.valueOf(2).equals(cose.get(1)) || !Integer.valueOf(COSE_ALG_ES256).equals(cose.get(3)) ||
            !Integer.valueOf(1).equals(cose.get(-1)))
            throw new IllegalArgumentException("Not a P-256 ES256 key");
        Object x = cose.get(-2);
        Object y = cose.get(-3);
        if (!(x instanceof byte[]) || !(y instanceof byte[]) || ((byte[]) x).length != 32 || ((byte[]) y).length != 32)
            throw new IllegalArgumentException("Bad P-256 point");
        return new NewCredential(credentialId, (byte[]) x, (byte[]) y);
    }

    // ---------------------------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------------------------

    @NonNull
    static File publicFile(@NonNull String id) {
        return new File(TerminowBiometricKeys.sshDir(), FILE_PREFIX + id + ".pub");
    }

    @NonNull
    public static synchronized List<Credential> load() {
        List<Credential> credentials = new ArrayList<>();
        if (!STORE.isFile()) return credentials;
        try {
            JSONArray array = new JSONArray(new String(Files.readAllBytes(STORE.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                String id = object.getString("id");
                if (!id.matches("[a-z0-9]{8,40}")) continue;
                credentials.add(new Credential(id, object.optString("label"), Base64.getDecoder().decode(object.getString("credentialId")),
                    Base64.getDecoder().decode(object.getString("publicBlob")), object.optLong("created")));
            }
        } catch (IOException | JSONException | IllegalArgumentException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load security keys", e);
        }
        return credentials;
    }

    private static synchronized void save(@NonNull List<Credential> credentials) throws IOException {
        JSONArray array = new JSONArray();
        try {
            for (Credential credential : credentials) {
                array.put(new JSONObject().put("id", credential.id).put("label", credential.label)
                    .put("credentialId", Base64.getEncoder().encodeToString(credential.credentialId))
                    .put("publicBlob", Base64.getEncoder().encodeToString(credential.publicBlob)).put("created", credential.created));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File temporary = new File(STORE.getPath() + ".new");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!temporary.renameTo(STORE)) throw new IOException("Could not save " + STORE);
    }

    @Nullable
    static Credential find(@NonNull String id) {
        for (Credential credential : load()) if (credential.id.equals(id)) return credential;
        return null;
    }

    /** Forget a credential and delete its public key file and certificate; the security key keeps it. */
    public static synchronized void delete(@NonNull String id) {
        List<Credential> credentials = load();
        credentials.removeIf(credential -> credential.id.equals(id));
        try {
            save(credentials);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to delete a security key", e);
        }
        File file = publicFile(id);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(file.getPath() + "-cert.pub").delete();
    }

    /** The keys of the agent: the credentials that have their public key file. */
    @NonNull
    static List<TerminowSshAgent.Key> agentKeys() {
        List<TerminowSshAgent.Key> keys = new ArrayList<>();
        for (Credential credential : load()) if (publicFile(credential.id).isFile()) keys.add(keyFor(credential));
        return keys;
    }

    @NonNull
    static TerminowSshAgent.Key keyFor(@NonNull Credential credential) {
        return new TerminowSshAgent.Key() {
            @NonNull
            @Override
            public byte[] publicBlob() {
                return credential.publicBlob;
            }

            @NonNull
            @Override
            public String comment() {
                return credential.label;
            }

            @Nullable
            @Override
            public byte[] sign(@NonNull byte[] data, int flags) throws Exception {
                return TerminowFidoKeys.sign(credential, data);
            }
        };
    }

    // ---------------------------------------------------------------------------------------
    // Making a key
    // ---------------------------------------------------------------------------------------

    public interface Result {
        /** {@code id} of the new key, or {@code error} for the user, both {@code null} if cancelled. */
        void onDone(@Nullable String id, @Nullable String error);
    }

    /** Make a credential on a security key that the user plugs in or taps, then keep it. On the main thread. */
    public static void create(@NonNull Activity activity, @NonNull String label, @NonNull Result result) {
        withSecurityKey(activity, activity.getString(R.string.terminow_key_fido_new),
            activity.getString(R.string.terminow_key_fido_touch_create), session -> {
                byte[] clientDataHash = new byte[32];
                new SecureRandom().nextBytes(clientDataHash);
                byte[] userId = new byte[16];
                new SecureRandom().nextBytes(userId);
                Map<String, Object> rp = new HashMap<>();
                rp.put("id", APPLICATION);
                rp.put("name", APPLICATION);
                Map<String, Object> user = new HashMap<>();
                user.put("id", userId);
                user.put("name", label);
                user.put("displayName", label);
                Map<String, Object> param = new HashMap<>();
                param.put("type", "public-key");
                param.put("alg", COSE_ALG_ES256);

                Ctap2Session.InfoData info = session.getCachedInfo();
                Map<String, ?> options = info.getOptions();
                byte[] pinUvAuthParam = null;
                Integer pinUvAuthProtocol = null;
                // A key with a PIN asks for it to make a credential, unless it says it does not need it
                if (Boolean.TRUE.equals(options.get("clientPin")) && !Boolean.TRUE.equals(options.get("makeCredUvNotRqd"))) {
                    char[] pin = askPin(activity);
                    if (pin == null) return null;
                    PinUvAuthProtocol protocol = protocolOf(info);
                    byte[] token = new ClientPin(session, protocol).getPinToken(pin, ClientPin.PIN_PERMISSION_MC, APPLICATION);
                    Arrays.fill(pin, '\0');
                    pinUvAuthParam = protocol.authenticate(token, clientDataHash);
                    pinUvAuthProtocol = protocol.getVersion();
                }
                Ctap2Session.CredentialData credential = session.makeCredential(clientDataHash, rp, user, Collections.singletonList(param),
                    null, null, null, pinUvAuthParam, pinUvAuthProtocol, null, null);
                NewCredential parsed = parseAuthenticatorData(credential.getAuthenticatorData());

                String id = (Long.toString(System.currentTimeMillis(), 36) + Integer.toString(new SecureRandom().nextInt(46656), 36))
                    .toLowerCase(Locale.ROOT);
                byte[] blob = TerminowSshWire.skEcdsaBlob(parsed.x, parsed.y, APPLICATION);
                List<Credential> credentials = load();
                credentials.add(new Credential(id, label, parsed.credentialId, blob, System.currentTimeMillis()));
                save(credentials);
                File file = publicFile(id);
                File dir = file.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write((TerminowSshWire.publicKeyLine(blob, label) + "\n").getBytes(StandardCharsets.UTF_8));
                }
                return id.getBytes(StandardCharsets.UTF_8);
            }, (value, error) -> result.onDone(value != null ? new String(value, StandardCharsets.UTF_8) : null, error));
    }

    // ---------------------------------------------------------------------------------------
    // Signing
    // ---------------------------------------------------------------------------------------

    /**
     * Sign for ssh with the security key, which the user touches or taps. Blocks the calling
     * thread, never the main thread. {@code null} if cancelled, timed out, or there is no TermiNow
     * screen to ask on.
     */
    @Nullable
    static byte[] sign(@NonNull Credential credential, @NonNull byte[] data) throws Exception {
        Activity activity = TerminowBiometricKeys.currentActivity();
        if (activity == null) return null;
        byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(data);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<byte[]> signature = new AtomicReference<>();
        activity.runOnUiThread(() -> withSecurityKey(activity, credential.label,
            activity.getString(R.string.terminow_key_fido_touch_sign, credential.label), session -> {
                Map<String, Object> allow = new HashMap<>();
                allow.put("type", "public-key");
                allow.put("id", credential.credentialId);
                List<Map<String, ?>> allowList = Collections.singletonList(allow);
                List<Ctap2Session.AssertionData> assertions;
                try {
                    assertions = session.getAssertions(APPLICATION, clientDataHash, allowList, null, null, null, null, null);
                } catch (CtapException e) {
                    if (e.getCtapError() != CtapException.ERR_PUAT_REQUIRED) throw e;
                    // A key that always wants its PIN
                    Ctap2Session.InfoData info = session.getCachedInfo();
                    char[] pin = askPin(activity);
                    if (pin == null) return null;
                    PinUvAuthProtocol protocol = protocolOf(info);
                    byte[] token = new ClientPin(session, protocol).getPinToken(pin, ClientPin.PIN_PERMISSION_GA, APPLICATION);
                    Arrays.fill(pin, '\0');
                    assertions = session.getAssertions(APPLICATION, clientDataHash, allowList, null, null,
                        protocol.authenticate(token, clientDataHash), protocol.getVersion(), null);
                }
                if (assertions.isEmpty()) throw new IOException("The security key made no signature");
                Ctap2Session.AssertionData assertion = assertions.get(0);
                return TerminowSshWire.skEcdsaSignature(assertion.getSignature(), assertion.getAuthenticatorData());
            }, (value, error) -> {
                signature.set(value);
                if (error != null) Logger.logError(LOG_TAG, "Security key signature failed: " + error);
                done.countDown();
            }));
        if (!done.await(DEVICE_TIMEOUT_SECONDS + 30, TimeUnit.SECONDS)) return null;
        return signature.get();
    }

    // ---------------------------------------------------------------------------------------
    // The security key
    // ---------------------------------------------------------------------------------------

    private interface Task {
        /** Runs on a worker thread with a session of the security key; {@code null} for cancelled. */
        @Nullable byte[] run(@NonNull Ctap2Session session) throws Exception;
    }

    private interface Done {
        void onDone(@Nullable byte[] value, @Nullable String error);
    }

    /** Any USB device: the FIDO interface is checked when it is opened, so other keys than YubiKeys work. */
    private static final DeviceFilter ANY_DEVICE = new DeviceFilter() {
        @Override
        public boolean checkVendorProductIds(int vendorId, int productId) {
            return true;
        }
    };

    /**
     * Wait for a security key over USB or NFC with a dialog, run the task with it once, and tell
     * the result on the main thread. On the main thread.
     */
    private static void withSecurityKey(@NonNull Activity activity, @NonNull String title, @NonNull String message,
                                        @NonNull Task task, @NonNull Done done) {
        YubiKitManager manager = new YubiKitManager(activity);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean busy = new AtomicBoolean();
        AlertDialog[] dialog = new AlertDialog[1];
        Runnable stop = () -> {
            manager.stopUsbDiscovery();
            try {
                manager.stopNfcDiscovery(activity);
            } catch (RuntimeException e) {
                // NFC was not started
            }
            if (dialog[0] != null && dialog[0].isShowing()) dialog[0].dismiss();
        };
        Done finish = (value, error) -> activity.runOnUiThread(() -> {
            if (!finished.compareAndSet(false, true)) return;
            stop.run();
            done.onDone(value, error);
        });
        dialog[0] = new AlertDialog.Builder(activity, R.style.TerminowAlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, (d, w) -> finish.onDone(null, null))
            .show();

        com.yubico.yubikit.core.util.Callback<YubiKeyDevice> onDevice = device -> {
            // One key at a time, and only once
            if (finished.get() || !busy.compareAndSet(false, true)) return;
            Thread thread = new Thread(() -> {
                try (Closeable connection = open(device); Ctap2Session session = sessionOf(connection)) {
                    finish.onDone(task.run(session), null);
                } catch (CtapException e) {
                    finish.onDone(null, describe(activity, e));
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Security key failed", e);
                    // A tap that was too short: wait for the next one
                    busy.set(false);
                    activity.runOnUiThread(() -> {
                        if (!finished.get() && dialog[0] != null) dialog[0].setMessage(message + "\n\n" +
                            activity.getString(R.string.terminow_key_fido_retry, String.valueOf(e.getMessage())));
                    });
                }
            }, "TerminowSecurityKey");
            thread.start();
        };
        manager.startUsbDiscovery(new UsbConfiguration().handlePermissions(true).setDeviceFilter(ANY_DEVICE), onDevice::invoke);
        try {
            manager.startNfcDiscovery(new NfcConfiguration().timeout(30000), activity, onDevice::invoke);
        } catch (NfcNotAvailable e) {
            // USB only
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> finish.onDone(null,
            activity.getString(R.string.terminow_key_fido_timeout)), DEVICE_TIMEOUT_SECONDS * 1000);
    }

    @NonNull
    private static Closeable open(@NonNull YubiKeyDevice device) throws IOException {
        if (device.supportsConnection(FidoConnection.class)) return device.openConnection(FidoConnection.class);
        if (device.supportsConnection(SmartCardConnection.class)) return device.openConnection(SmartCardConnection.class);
        throw new IOException("This device is not a FIDO2 security key");
    }

    @NonNull
    private static Ctap2Session sessionOf(@NonNull Closeable connection) throws Exception {
        if (connection instanceof FidoConnection) return new Ctap2Session((FidoConnection) connection);
        return new Ctap2Session((SmartCardConnection) connection);
    }

    @NonNull
    private static PinUvAuthProtocol protocolOf(@NonNull Ctap2Session.InfoData info) {
        return info.getPinUvAuthProtocols().contains(2) ? new PinUvAuthProtocolV2() : new PinUvAuthProtocolV1();
    }

    @NonNull
    private static String describe(@NonNull Activity activity, @NonNull CtapException e) {
        switch (e.getCtapError()) {
            case CtapException.ERR_PIN_INVALID: return activity.getString(R.string.terminow_key_fido_pin_wrong);
            case CtapException.ERR_PIN_BLOCKED:
            case CtapException.ERR_PIN_AUTH_BLOCKED: return activity.getString(R.string.terminow_key_fido_pin_blocked);
            case CtapException.ERR_NO_CREDENTIALS: return activity.getString(R.string.terminow_key_fido_other_key);
            case CtapException.ERR_OPERATION_DENIED:
            case CtapException.ERR_ACTION_TIMEOUT: return activity.getString(R.string.terminow_key_fido_not_touched);
            default: return activity.getString(R.string.terminow_key_fido_error, e.getMessage());
        }
    }

    /** Ask for the PIN of the security key, from a worker thread. {@code null} if cancelled. */
    @Nullable
    private static char[] askPin(@NonNull Activity activity) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<char[]> pin = new AtomicReference<>();
        activity.runOnUiThread(() -> {
            EditText field = new EditText(activity);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setHint(R.string.terminow_key_fido_pin);
            int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
            android.widget.FrameLayout box = new android.widget.FrameLayout(activity);
            box.setPadding(padding, padding / 2, padding, 0);
            box.addView(field);
            new AlertDialog.Builder(activity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_key_fido_pin)
                .setView(box)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    char[] value = new char[field.length()];
                    field.getText().getChars(0, field.length(), value, 0);
                    field.setText("");
                    pin.set(value);
                    done.countDown();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> done.countDown())
                .show();
        });
        done.await();
        return pin.get();
    }

}
