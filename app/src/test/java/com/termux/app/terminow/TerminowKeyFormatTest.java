package com.termux.app.terminow;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * What the New Key panel says about a pasted key. The keys were made with ssh-keygen for these
 * tests only; the fingerprints are what `ssh-keygen -l` shows for them.
 */
public class TerminowKeyFormatTest {

    private static String key(String name) throws Exception {
        try (InputStream in = TerminowKeyFormatTest.class.getResourceAsStream("/terminow-keys/" + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void openSshKeysGiveTheirTypeAndFingerprintEvenWithAPassphrase() throws Exception {
        TerminowKeyFormat.Info plain = TerminowKeyFormat.analyze(key("ed"));
        assertEquals(TerminowKeyFormat.Kind.OPENSSH, plain.kind);
        assertEquals("ED25519", plain.type);
        assertFalse(plain.encrypted);
        assertEquals("SHA256:JeYwJBnAGTmz0JHaGqwQqys3zmaO0JnxoR28yuTw5g8", TerminowKeyFormat.fingerprint(plain.publicBlob));
        // The same public key as the .pub file that ssh-keygen wrote
        assertArrayEquals(TerminowSshWire.parseLine(key("ed.pub")).blob, plain.publicBlob);

        TerminowKeyFormat.Info encrypted = TerminowKeyFormat.analyze(key("ed_enc"));
        assertTrue(encrypted.encrypted);
        assertTrue(encrypted.isPrivateKey());
        assertEquals("SHA256:PgjcJ0ksn74bEKfUM13AtX76sCNRo1tFO0vNMp1bVt8", TerminowKeyFormat.fingerprint(encrypted.publicBlob));
    }

    @Test
    public void pemKeys() throws Exception {
        TerminowKeyFormat.Info rsa = TerminowKeyFormat.analyze(key("rsa_pem"));
        assertEquals(TerminowKeyFormat.Kind.PEM, rsa.kind);
        assertEquals("RSA", rsa.type);
        assertFalse(rsa.encrypted);
        // Read from the private key itself, like ssh-keygen -y
        assertEquals("SHA256:flYWhgIkkGZxYh0+aRMr/m5JsmxW/VdkxObcfoBVR9Y", TerminowKeyFormat.fingerprint(rsa.publicBlob));
        assertArrayEquals(TerminowSshWire.parseLine(key("rsa_pem.pub")).blob, rsa.publicBlob);

        TerminowKeyFormat.Info rsaEncrypted = TerminowKeyFormat.analyze(key("rsa_pem_enc"));
        assertTrue(rsaEncrypted.encrypted);
        assertNull(rsaEncrypted.publicBlob);

        TerminowKeyFormat.Info ec = TerminowKeyFormat.analyze(key("ec_pem"));
        assertEquals("ECDSA", ec.type);
        assertTrue(ec.isPrivateKey());

        TerminowKeyFormat.Info pkcs8 = TerminowKeyFormat.analyze(key("p8"));
        assertEquals(TerminowKeyFormat.Kind.PKCS8, pkcs8.kind);
        assertFalse(pkcs8.encrypted);
    }

    @Test
    public void whatIsNotAPrivateKeySaysWhatItIs() throws Exception {
        TerminowKeyFormat.Info pub = TerminowKeyFormat.analyze(key("ed.pub"));
        assertEquals(TerminowKeyFormat.Kind.PUBLIC_KEY, pub.kind);
        assertEquals("ED25519", pub.type);
        assertFalse(pub.isPrivateKey());
        assertEquals(TerminowKeyFormat.Kind.EMPTY, TerminowKeyFormat.analyze("  \n").kind);
        assertEquals(TerminowKeyFormat.Kind.UNKNOWN, TerminowKeyFormat.analyze("hello").kind);
        // A broken OpenSSH key is not taken as one
        assertEquals(TerminowKeyFormat.Kind.UNKNOWN,
            TerminowKeyFormat.analyze("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----").kind);
        // Windows line ends and spaces around it
        assertEquals(TerminowKeyFormat.Kind.OPENSSH, TerminowKeyFormat.analyze("\r\n " + key("ed").replace("\n", "\r\n")).kind);
    }

    @Test
    public void puttyKeys() {
        String ppk = "PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: none\nComment: x\nPublic-Lines: 2\n";
        TerminowKeyFormat.Info info = TerminowKeyFormat.analyze(ppk);
        assertEquals(TerminowKeyFormat.Kind.PPK, info.kind);
        assertEquals("ED25519", info.type);
        assertFalse(info.encrypted);
        assertTrue(TerminowKeyFormat.analyze(ppk.replace("Encryption: none", "Encryption: aes256-cbc")).encrypted);
    }
}
