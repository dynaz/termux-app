package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Identities, certificates and the agent of the keychain, the SSH parts with the OpenSSH of this machine. */
public class TerminowKeychainTest {

    private File mDir;
    private KeyPair mKeyPair;
    private byte[] mBlob;
    private final AtomicInteger mPrompts = new AtomicInteger();
    private volatile boolean mAllow = true;
    @Nullable private ServerSocketChannel mServer;

    @Before
    public void setUp() throws Exception {
        TerminowStore.sTestsWithoutKeystore = true;
        mDir = Files.createTempDirectory("terminow-keychain").toFile();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        mKeyPair = generator.generateKeyPair();
        mBlob = TerminowSshWire.ecdsaP256Blob((ECPublicKey) mKeyPair.getPublic());
    }

    @After
    public void tearDown() throws IOException {
        if (mServer != null) mServer.close();
    }

    /** The key as the biometric keys are: the Android keystore signs with SHA256withECDSA in DER. */
    @NonNull
    private TerminowSshAgent.Key softwareKey() {
        return new TerminowSshAgent.Key() {
            @NonNull
            @Override
            public byte[] publicBlob() {
                return mBlob;
            }

            @NonNull
            @Override
            public String comment() {
                return "phone fingerprint key";
            }

            @Nullable
            @Override
            public byte[] sign(@NonNull byte[] data, int flags) throws Exception {
                mPrompts.incrementAndGet();
                if (!mAllow) return null;
                Signature signature = Signature.getInstance("SHA256withECDSA");
                signature.initSign(mKeyPair.getPrivate());
                signature.update(data);
                return TerminowSshWire.ecdsaSignatureFromDer(signature.sign());
            }
        };
    }

    private static boolean has(@NonNull String program) {
        return new File("/usr/bin/" + program).canExecute();
    }

    @NonNull
    private static String run(@Nullable File socket, @Nullable File input, @NonNull String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (socket != null) builder.environment().put("SSH_AUTH_SOCK", socket.getPath());
        if (input != null) builder.redirectInput(input);
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        return "exit " + process.exitValue() + "\n" + out;
    }

    /** The agent on a Unix socket, with the API of Java 16 that android.jar does not have. */
    @NonNull
    private File startAgent(@NonNull TerminowSshAgent.Keys keys) throws Exception {
        File socket = new File(mDir, "agent.sock");
        Path path = socket.toPath();
        SocketAddress address = (SocketAddress) Class.forName("java.net.UnixDomainSocketAddress").getMethod("of", Path.class).invoke(null, path);
        ProtocolFamily unix = Enum.valueOf(StandardProtocolFamily.class, "UNIX");
        ServerSocketChannel server = (ServerSocketChannel) ServerSocketChannel.class.getMethod("open", ProtocolFamily.class).invoke(null, unix);
        server.bind(address);
        mServer = server;
        Thread thread = new Thread(() -> {
            while (server.isOpen()) {
                try {
                    SocketChannel channel = server.accept();
                    new Thread(() -> {
                        try (InputStream in = Channels.newInputStream(channel); OutputStream out = Channels.newOutputStream(channel)) {
                            TerminowSshAgent.serve(in, out, keys);
                        } catch (IOException e) {
                            // The client went away
                        }
                    }).start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return socket;
    }

    // ---------------------------------------------------------------------------------------
    // The agent with the real ssh-add and ssh-keygen
    // ---------------------------------------------------------------------------------------

    @Test
    public void sshLists() throws Exception {
        Assume.assumeTrue(has("ssh-add"));
        File socket = startAgent(() -> Collections.singletonList(softwareKey()));
        String out = run(socket, null, "/usr/bin/ssh-add", "-L");
        assertTrue(out, out.startsWith("exit 0"));
        assertTrue(out, out.contains(TerminowSshWire.publicKeyLine(mBlob, "phone fingerprint key")));
        assertEquals("listing asks nobody", 0, mPrompts.get());
    }

    @Test
    public void signaturesAreAcceptedByOpenSsh() throws Exception {
        Assume.assumeTrue(has("ssh-keygen"));
        File socket = startAgent(() -> Collections.singletonList(softwareKey()));
        File pub = new File(mDir, "phone.pub");
        Files.write(pub.toPath(), (TerminowSshWire.publicKeyLine(mBlob, "phone") + "\n").getBytes(StandardCharsets.UTF_8));
        File data = new File(mDir, "data.txt");
        Files.write(data.toPath(), "sign me".getBytes(StandardCharsets.UTF_8));
        // Only the public key is given, so ssh-keygen signs with the agent, like ssh does
        String signed = run(socket, null, "/usr/bin/ssh-keygen", "-Y", "sign", "-f", pub.getPath(), "-n", "file", data.getPath());
        assertTrue(signed, signed.startsWith("exit 0"));
        assertEquals(1, mPrompts.get());
        String checked = run(null, data, "/usr/bin/ssh-keygen", "-Y", "check-novalidate", "-n", "file", "-f", pub.getPath(),
            "-s", data.getPath() + ".sig");
        assertTrue(checked, checked.startsWith("exit 0"));
        assertTrue(checked, checked.contains("Good"));
    }

    @Test
    public void aSignatureThatTheUserRefusesFails() throws Exception {
        Assume.assumeTrue(has("ssh-keygen"));
        mAllow = false;
        File socket = startAgent(() -> Collections.singletonList(softwareKey()));
        File pub = new File(mDir, "phone.pub");
        Files.write(pub.toPath(), (TerminowSshWire.publicKeyLine(mBlob, "phone") + "\n").getBytes(StandardCharsets.UTF_8));
        File data = new File(mDir, "data.txt");
        Files.write(data.toPath(), "sign me".getBytes(StandardCharsets.UTF_8));
        String signed = run(socket, null, "/usr/bin/ssh-keygen", "-Y", "sign", "-f", pub.getPath(), "-n", "file", data.getPath());
        assertFalse(signed, signed.startsWith("exit 0"));
        assertFalse(new File(data.getPath() + ".sig").exists());
    }

    @Test
    public void theAgentOnlyListsAndSigns() {
        TerminowSshAgent.Keys keys = () -> Collections.singletonList(softwareKey());
        // Add an identity (17), remove all (19), lock (22): refused
        for (int type : new int[]{17, 18, 19, 22, 23, 25}) {
            byte[] reply = TerminowSshAgent.handle(new byte[]{(byte) type, 0, 0, 0, 0}, keys);
            assertArrayEquals(new byte[]{TerminowSshAgent.SSH_AGENT_FAILURE}, reply);
        }
        // A key that it does not have
        byte[] other = new TerminowSshWire.Writer().text("ssh-ed25519").string(new byte[32]).toBytes();
        byte[] request = new TerminowSshWire.Writer().byte1(TerminowSshAgent.SSH2_AGENTC_SIGN_REQUEST).string(other)
            .string("x".getBytes(StandardCharsets.UTF_8)).uint32(0).toBytes();
        assertArrayEquals(new byte[]{TerminowSshAgent.SSH_AGENT_FAILURE}, TerminowSshAgent.handle(request, keys));
        assertEquals(0, mPrompts.get());
        // Garbage
        assertArrayEquals(new byte[]{TerminowSshAgent.SSH_AGENT_FAILURE}, TerminowSshAgent.handle(new byte[]{13, 0, 0}, keys));
    }

    // ---------------------------------------------------------------------------------------
    // Certificates, made by the real ssh-keygen
    // ---------------------------------------------------------------------------------------

    @Test
    public void aCertificateIsReadAndMatchedToItsKey() throws Exception {
        Assume.assumeTrue(has("ssh-keygen"));
        File ca = new File(mDir, "ca");
        assertTrue(run(null, null, "/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", ca.getPath()).startsWith("exit 0"));
        File user = new File(mDir, "user");
        assertTrue(run(null, null, "/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", user.getPath()).startsWith("exit 0"));
        String signed = run(null, null, "/usr/bin/ssh-keygen", "-q", "-s", ca.getPath(), "-I", "alice@laptop", "-n", "alice,deploy",
            "-V", "+52w", "-z", "42", user.getPath() + ".pub");
        assertTrue(signed, signed.startsWith("exit 0"));
        String certText = new String(Files.readAllBytes(new File(mDir, "user-cert.pub").toPath()), StandardCharsets.UTF_8);
        String pubText = new String(Files.readAllBytes(new File(mDir, "user.pub").toPath()), StandardCharsets.UTF_8);

        TerminowSshWire.PublicKeyLine certLine = TerminowSshWire.parseLine(certText);
        assertNotNull(certLine);
        TerminowSshWire.Certificate cert = TerminowSshWire.parseCertificate(certLine.blob);
        assertNotNull(cert);
        assertEquals("ssh-ed25519", cert.keyType);
        assertEquals("alice@laptop", cert.keyId);
        assertEquals(Arrays.asList("alice", "deploy"), cert.principals);
        assertEquals(42, cert.serial);
        assertEquals(1, cert.certType);
        assertTrue(cert.expires());
        assertTrue(cert.isValidAt(System.currentTimeMillis() / 1000));
        assertFalse(cert.isValidAt(System.currentTimeMillis() / 1000 + 60L * 60 * 24 * 400));
        // It certifies this key, and no other
        assertArrayEquals(TerminowSshWire.parseLine(pubText).blob, cert.keyBlob);
        assertFalse(Arrays.equals(TerminowSshWire.parseLine(new String(Files.readAllBytes(new File(mDir, "ca.pub").toPath()),
            StandardCharsets.UTF_8)).blob, cert.keyBlob));
        // A plain public key is not a certificate
        assertNull(TerminowSshWire.parseCertificate(TerminowSshWire.parseLine(pubText).blob));
    }

    @Test
    public void theAgentSignsForACertificateOfItsKey() throws Exception {
        Assume.assumeTrue(has("ssh-keygen"));
        File ca = new File(mDir, "ca");
        assertTrue(run(null, null, "/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", ca.getPath()).startsWith("exit 0"));
        File pub = new File(mDir, "phone.pub");
        Files.write(pub.toPath(), (TerminowSshWire.publicKeyLine(mBlob, "phone") + "\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(run(null, null, "/usr/bin/ssh-keygen", "-q", "-s", ca.getPath(), "-I", "phone", "-n", "alice", pub.getPath()).startsWith("exit 0"));
        byte[] certBlob = TerminowSshWire.parseLine(new String(Files.readAllBytes(new File(mDir, "phone-cert.pub").toPath()),
            StandardCharsets.UTF_8)).blob;
        TerminowSshAgent.Key key = softwareKey();
        assertSame(key, TerminowSshAgent.find(Collections.singletonList(key), certBlob));
        assertSame(key, TerminowSshAgent.find(Collections.singletonList(key), mBlob));
    }

    @Test
    public void notAKeyLine() {
        assertNull(TerminowSshWire.parseLine("hello world"));
        assertNull(TerminowSshWire.parseLine("ssh-ed25519 !!!notbase64"));
        // The type in the blob must be the type of the line
        String line = TerminowSshWire.publicKeyLine(mBlob, "");
        assertNull(TerminowSshWire.parseLine(line.replace("ecdsa-sha2-nistp256 ", "ssh-ed25519 ")));
        assertNotNull(TerminowSshWire.parseLine("# comment\n" + line));
    }

    // ---------------------------------------------------------------------------------------
    // Identities
    // ---------------------------------------------------------------------------------------

    @Test
    public void identitiesAreKeptWithTheirPasswordEncrypted() {
        MemoryPreferences preferences = new MemoryPreferences();
        TerminowStore store = new TerminowStore(preferences);
        TerminowStore.Identity identity = new TerminowStore.Identity();
        identity.id = "abc123";
        identity.label = "Deploy";
        identity.username = "deploy";
        identity.password = "s3cret";
        identity.keyPath = "~/.ssh/id_ed25519";
        identity.created = 7;
        store.setIdentities(Collections.singletonList(identity));

        List<TerminowStore.Identity> loaded = new TerminowStore(preferences).getIdentities();
        assertEquals(1, loaded.size());
        assertEquals("Deploy", loaded.get(0).getTitle());
        assertEquals("deploy", loaded.get(0).username);
        assertEquals("s3cret", loaded.get(0).password);
        assertEquals("~/.ssh/id_ed25519", loaded.get(0).keyPath);
        assertEquals(7, loaded.get(0).created);
        // Never in clear in storage (the tests mark it instead of encrypting)
        assertFalse(preferences.getAll().toString().contains("\"password\":\"s3cret\""));
    }

    @Test
    public void anIdentityThatWouldEndUpInACommandBadlyIsNotLoaded() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.edit().putString("identities", "[{\"id\":\"a1\",\"username\":\"x; rm -rf ~\"},{\"id\":\"a2\",\"keyPath\":\"$(id)\"}," +
            "{\"id\":\"../x\",\"username\":\"ok\"},{\"id\":\"a3\",\"username\":\"ok\"}]").apply();
        List<TerminowStore.Identity> loaded = new TerminowStore(preferences).getIdentities();
        assertEquals(1, loaded.size());
        assertEquals("a3", loaded.get(0).id);
    }

    @Test
    public void aHostLogsInWithItsIdentity() {
        TerminowStore.Host host = new TerminowStore.Host();
        host.label = "web";
        host.address = "web.example.com";
        host.username = "old";
        host.password = "hostpw";
        host.keyPath = "~/.ssh/host_key";
        host.identity = "id1";
        TerminowStore.Identity identity = new TerminowStore.Identity();
        identity.id = "id1";
        identity.username = "deploy";
        identity.keyPath = "~/.ssh/deploy";
        List<TerminowStore.Identity> identities = new ArrayList<>(Collections.singletonList(identity));

        TerminowStore.Host login = TerminowStore.resolve(host, identities);
        assertEquals("deploy", login.username);
        assertEquals("~/.ssh/deploy", login.keyPath);
        // The identity has no password: the host's is kept
        assertEquals("hostpw", login.password);
        assertEquals("ssh -i ~/.ssh/deploy -o StrictHostKeyChecking=accept-new deploy@web.example.com", login.getCommand(0, 0));
        // The saved host is not changed, and still known by its own key
        assertEquals("old", host.username);
        assertEquals("old@web.example.com:22", host.getKey());

        identity.password = "idpw";
        assertEquals("idpw", TerminowStore.resolve(host, identities).password);
        // An identity that was deleted: the host's own
        identities.clear();
        assertSame(host, TerminowStore.resolve(host, identities));
    }

    @Test
    public void aHostWithAnIdentityIsStored() throws Exception {
        TerminowStore.Host host = new TerminowStore.Host();
        host.address = "h";
        host.identity = "id1";
        TerminowStore.Host loaded = TerminowStore.hostFromJson(TerminowStore.hostToJson(host));
        assertEquals("id1", loaded.identity);
        assertTrue(TerminowStore.isRunnable(loaded));
        loaded.identity = "../../x";
        assertFalse(TerminowStore.isRunnable(loaded));
    }


    // ---------------------------------------------------------------------------------------
    // FIDO2 security keys: the SSH format with a software authenticator and the real OpenSSH
    // ---------------------------------------------------------------------------------------

    private static byte[] unsigned32(@NonNull java.math.BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] out = new byte[32];
        int start = Math.max(0, bytes.length - 32);
        System.arraycopy(bytes, start, out, 32 - (bytes.length - start), bytes.length - start);
        return out;
    }

    @Test
    public void securityKeySignaturesAreAcceptedByOpenSsh() throws Exception {
        Assume.assumeTrue(has("ssh-keygen"));
        ECPublicKey publicKey = (ECPublicKey) mKeyPair.getPublic();
        byte[] skBlob = TerminowSshWire.skEcdsaBlob(unsigned32(publicKey.getW().getAffineX()), unsigned32(publicKey.getW().getAffineY()), "ssh:");
        AtomicInteger counter = new AtomicInteger(41);
        TerminowSshAgent.Key key = new TerminowSshAgent.Key() {
            @NonNull
            @Override
            public byte[] publicBlob() {
                return skBlob;
            }

            @NonNull
            @Override
            public String comment() {
                return "yubikey";
            }

            @Nullable
            @Override
            public byte[] sign(@NonNull byte[] data, int flags) throws Exception {
                // What a security key does for getAssertion: sign authenticatorData || clientDataHash
                java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
                byte[] rpIdHash = sha.digest("ssh:".getBytes(StandardCharsets.UTF_8));
                int count = counter.incrementAndGet();
                byte[] authData = new byte[37];
                System.arraycopy(rpIdHash, 0, authData, 0, 32);
                authData[32] = 0x01; // user present
                authData[33] = (byte) (count >>> 24);
                authData[34] = (byte) (count >>> 16);
                authData[35] = (byte) (count >>> 8);
                authData[36] = (byte) count;
                byte[] clientDataHash = sha.digest(data);
                Signature signature = Signature.getInstance("SHA256withECDSA");
                signature.initSign(mKeyPair.getPrivate());
                signature.update(authData);
                signature.update(clientDataHash);
                return TerminowSshWire.skEcdsaSignature(signature.sign(), authData);
            }
        };
        File socket = startAgent(() -> Collections.singletonList(key));
        File pub = new File(mDir, "sk.pub");
        Files.write(pub.toPath(), (TerminowSshWire.publicKeyLine(skBlob, "yubikey") + "\n").getBytes(StandardCharsets.UTF_8));
        // OpenSSH reads it as a security key
        String fingerprint = run(null, null, "/usr/bin/ssh-keygen", "-l", "-f", pub.getPath());
        assertTrue(fingerprint, fingerprint.contains("ECDSA-SK"));
        File data = new File(mDir, "data.txt");
        Files.write(data.toPath(), "sign me".getBytes(StandardCharsets.UTF_8));
        String signed = run(socket, null, "/usr/bin/ssh-keygen", "-Y", "sign", "-f", pub.getPath(), "-n", "file", data.getPath());
        assertTrue(signed, signed.startsWith("exit 0"));
        String checked = run(null, data, "/usr/bin/ssh-keygen", "-Y", "check-novalidate", "-n", "file", "-f", pub.getPath(),
            "-s", data.getPath() + ".sig");
        assertTrue(checked, checked.startsWith("exit 0"));
        assertTrue(checked, checked.contains("Good"));
    }

    @Test
    public void theCredentialOfMakeCredentialIsRead() throws Exception {
        ECPublicKey publicKey = (ECPublicKey) mKeyPair.getPublic();
        byte[] x = unsigned32(publicKey.getW().getAffineX());
        byte[] y = unsigned32(publicKey.getW().getAffineY());
        java.util.Map<Integer, Object> cose = new java.util.LinkedHashMap<>();
        cose.put(1, 2);
        cose.put(3, -7);
        cose.put(-1, 1);
        cose.put(-2, x);
        cose.put(-3, y);
        byte[] coseKey = com.yubico.yubikit.fido.Cbor.encode(cose);
        byte[] credentialId = new byte[64];
        new java.util.Random(1).nextBytes(credentialId);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(new byte[32]); // rpIdHash
        out.write(0x41); // user present, attested credential data
        out.write(new byte[]{0, 0, 0, 1});
        out.write(new byte[16]); // AAGUID
        out.write(0);
        out.write(credentialId.length);
        out.write(credentialId);
        out.write(coseKey);
        TerminowFidoKeys.NewCredential parsed = TerminowFidoKeys.parseAuthenticatorData(out.toByteArray());
        assertArrayEquals(credentialId, parsed.credentialId);
        assertArrayEquals(x, parsed.x);
        assertArrayEquals(y, parsed.y);
        // An RSA or Ed25519 key is refused, ssh-keygen -t ecdsa-sk keys are P-256
        cose.put(3, -8);
        byte[] other = out.toByteArray();
        byte[] eddsa = com.yubico.yubikit.fido.Cbor.encode(cose);
        byte[] wrong = Arrays.copyOf(other, other.length - coseKey.length + eddsa.length);
        System.arraycopy(eddsa, 0, wrong, other.length - coseKey.length, eddsa.length);
        try {
            TerminowFidoKeys.parseAuthenticatorData(wrong);
            throw new AssertionError("accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("P-256"));
        }
    }


    // ---------------------------------------------------------------------------------------
    // Real logins with ssh and sshd of this machine, through the agent
    // ---------------------------------------------------------------------------------------

    @NonNull
    private TerminowSshAgent.Key skKey(@NonNull byte[] skBlob) {
        return new TerminowSshAgent.Key() {
            @NonNull
            @Override
            public byte[] publicBlob() {
                return skBlob;
            }

            @NonNull
            @Override
            public String comment() {
                return "security key";
            }

            @Nullable
            @Override
            public byte[] sign(@NonNull byte[] data, int flags) throws Exception {
                java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
                byte[] authData = Arrays.copyOf(sha.digest("ssh:".getBytes(StandardCharsets.UTF_8)), 37);
                authData[32] = 0x01;
                authData[36] = 7;
                Signature signature = Signature.getInstance("SHA256withECDSA");
                signature.initSign(mKeyPair.getPrivate());
                signature.update(authData);
                signature.update(sha.digest(data));
                return TerminowSshWire.skEcdsaSignature(signature.sign(), authData);
            }
        };
    }

    @Test
    public void realLoginsThroughTheAgent() throws Exception {
        Assume.assumeTrue(new File("/usr/sbin/sshd").canExecute() && has("ssh") && has("ssh-keygen"));
        // As TermiNow writes the command of a host with a biometric key
        TerminowStore.Host host = new TerminowStore.Host();
        host.address = "server";
        host.username = "root";
        host.keyPath = "~/.ssh/terminow-bio-abcdefgh12.pub";
        String command = host.getCommand(0, 0);
        assertEquals("ssh -i ~/.ssh/terminow-bio-abcdefgh12.pub -o IdentitiesOnly=yes -o IdentityAgent=" +
            TerminowBiometricKeys.AGENT_SOCKET + " -o StrictHostKeyChecking=accept-new root@server", command);
        host.keyPath = "~/.ssh/terminow-fido-abcdefgh12.pub";
        assertTrue(host.getCommand(0, 0).contains("-o IdentityAgent="));
        host.keyPath = "~/.ssh/id_ed25519";
        assertEquals("ssh -i ~/.ssh/id_ed25519 -o StrictHostKeyChecking=accept-new root@server", host.getCommand(0, 0));

        ECPublicKey publicKey = (ECPublicKey) mKeyPair.getPublic();
        byte[] skBlob = TerminowSshWire.skEcdsaBlob(unsigned32(publicKey.getW().getAffineX()), unsigned32(publicKey.getW().getAffineY()), "ssh:");
        File socket = startAgent(() -> Arrays.asList(softwareKey(), skKey(skBlob)));

        File hostKey = new File(mDir, "hostkey");
        File ca = new File(mDir, "ca");
        assertTrue(run(null, null, "/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", hostKey.getPath()).startsWith("exit 0"));
        assertTrue(run(null, null, "/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", ca.getPath()).startsWith("exit 0"));
        File phonePub = new File(mDir, "terminow-bio-abcdefgh12.pub");
        Files.write(phonePub.toPath(), (TerminowSshWire.publicKeyLine(mBlob, "phone") + "\n").getBytes(StandardCharsets.UTF_8));
        File skPub = new File(mDir, "terminow-fido-abcdefgh12.pub");
        Files.write(skPub.toPath(), (TerminowSshWire.publicKeyLine(skBlob, "security key") + "\n").getBytes(StandardCharsets.UTF_8));
        File authorized = new File(mDir, "authorized");

        int port = 23000 + (int) (Math.random() * 2000);
        File config = new File(mDir, "sshd_config");
        Files.write(config.toPath(), ("Port " + port + "\nListenAddress 127.0.0.1\nHostKey " + hostKey + "\nAuthorizedKeysFile " + authorized +
            "\nTrustedUserCAKeys " + ca + ".pub\nPidFile none\nUsePAM no\nStrictModes no\nPasswordAuthentication no\n" +
            "KbdInteractiveAuthentication no\n").getBytes(StandardCharsets.UTF_8));
        new File("/run/sshd").mkdirs();
        Process sshd = new ProcessBuilder("/usr/sbin/sshd", "-D", "-e", "-f", config.getPath()).redirectErrorStream(true).start();
        try {
            Thread.sleep(1500);
            Assume.assumeTrue("sshd runs here", sshd.isAlive());
            String[] ssh = {"/usr/bin/ssh", "-F", "/dev/null", "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=" + new File(mDir, "kh"),
                "-o", "BatchMode=yes", "-p", String.valueOf(port), "-o", "IdentitiesOnly=yes", "-o", "IdentityAgent=" + socket};

            // 1. The biometric key, by its public key file, through the agent
            Files.write(authorized.toPath(), Files.readAllBytes(phonePub.toPath()));
            String out = run(null, null, concat(ssh, "-i", phonePub.getPath(), "root@127.0.0.1", "echo LOGIN_OK"));
            assertTrue(out, out.contains("LOGIN_OK"));

            // 2. Not authorized itself, but with a certificate of the CA, where TermiNow keeps it for the key
            Files.write(authorized.toPath(), new byte[0]);
            out = run(null, null, concat(ssh, "-i", phonePub.getPath(), "root@127.0.0.1", "echo LOGIN_OK"));
            assertFalse(out, out.contains("LOGIN_OK"));
            assertTrue(run(null, null, "/usr/bin/ssh-keygen", "-q", "-s", ca.getPath(), "-I", "phone", "-n", "root", "-V", "+1h",
                phonePub.getPath()).startsWith("exit 0"));
            File certificate = new File(phonePub.getPath().replace(".pub", "-cert.pub"));
            // ssh-keygen names it name-cert.pub; TermiNow keeps the certificate of a key file at file + "-cert.pub"
            assertTrue(certificate.renameTo(new File(phonePub.getPath() + "-cert.pub")));
            out = run(null, null, concat(ssh, "-i", phonePub.getPath(), "root@127.0.0.1", "echo LOGIN_OK"));
            assertTrue(out, out.contains("LOGIN_OK"));

            // 3. A FIDO2 security key
            Files.write(authorized.toPath(), Files.readAllBytes(skPub.toPath()));
            out = run(null, null, concat(ssh, "-i", skPub.getPath(), "root@127.0.0.1", "echo LOGIN_OK"));
            assertTrue(out, out.contains("LOGIN_OK"));

            // The user says no: no login
            mAllow = false;
            Files.write(authorized.toPath(), Files.readAllBytes(phonePub.toPath()));
            //noinspection ResultOfMethodCallIgnored
            new File(phonePub.getPath() + "-cert.pub").delete();
            out = run(null, null, concat(ssh, "-i", phonePub.getPath(), "root@127.0.0.1", "echo LOGIN_OK"));
            assertFalse(out, out.contains("LOGIN_OK"));
        } finally {
            sshd.destroy();
            sshd.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @NonNull
    private static String[] concat(@NonNull String[] first, @NonNull String... rest) {
        String[] all = Arrays.copyOf(first, first.length + rest.length);
        System.arraycopy(rest, 0, all, first.length, rest.length);
        return all;
    }

}
