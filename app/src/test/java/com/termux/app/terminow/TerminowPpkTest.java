package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** The test keys in resources/ppk were made with puttygen 0.81 and have the passphrase "test-passphrase". */
public class TerminowPpkTest {

    private static final String PASSPHRASE = "test-passphrase";

    private static String read(String name) throws IOException {
        try (InputStream in = TerminowPpkTest.class.getClassLoader().getResourceAsStream("ppk/" + name)) {
            if (in == null) throw new IOException("Missing " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void assertConverts(String type, String variant, String passphrase) throws Exception {
        String ppk = read(type + "-" + variant + ".ppk");
        assertTrue(TerminowPpk.isPpk(ppk));
        TerminowPpk.Result result = TerminowPpk.convert(ppk, passphrase);
        assertEquals("Public key of " + type + "-" + variant, read(type + ".pub").trim(), result.publicKey);
        assertTrue(result.privateKey.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"));
        assertTrue(result.privateKey.endsWith("-----END OPENSSH PRIVATE KEY-----\n"));
        assertEquals("test-" + type, result.comment);
    }

    @Test
    public void convertsAllTypesAndVersions() throws Exception {
        for (String type : new String[]{"rsa", "ed25519", "ecdsa"}) {
            assertConverts(type, "v3-enc", PASSPHRASE);
            assertConverts(type, "v3-plain", null);
            assertConverts(type, "v2-enc", PASSPHRASE);
        }
    }

    @Test
    public void detectsEncryption() throws Exception {
        assertTrue(TerminowPpk.isEncrypted(read("ed25519-v3-enc.ppk")));
        assertFalse(TerminowPpk.isEncrypted(read("ed25519-v3-plain.ppk")));
        assertFalse(TerminowPpk.isPpk("-----BEGIN OPENSSH PRIVATE KEY-----"));
    }

    @Test
    public void rejectsWrongOrMissingPassphrase() throws Exception {
        for (String variant : new String[]{"v3-enc", "v2-enc"}) {
            String ppk = read("rsa-" + variant + ".ppk");
            try {
                TerminowPpk.convert(ppk, "wrong");
                fail("Wrong passphrase accepted for " + variant);
            } catch (TerminowPpk.PassphraseException expected) {
                assertEquals("The passphrase is wrong", expected.getMessage());
            }
            try {
                TerminowPpk.convert(ppk, null);
                fail("Missing passphrase accepted for " + variant);
            } catch (TerminowPpk.PassphraseException expected) {
                // Expected
            }
        }
    }

    @Test
    public void rejectsDamagedFiles() {
        try {
            TerminowPpk.convert("PuTTY-User-Key-File-3: ssh-rsa\nEncryption: none\n", null);
            fail();
        } catch (TerminowPpk.PpkException expected) {
            // Expected
        }
    }

    @Test
    public void base64RoundTrip() throws Exception {
        for (int length = 0; length < 10; length++) {
            byte[] data = new byte[length];
            for (int i = 0; i < length; i++) data[i] = (byte) (i * 37 + 200);
            assertEquals(java.util.Arrays.toString(data),
                java.util.Arrays.toString(TerminowPpk.decodeBase64(TerminowPpk.encodeBase64(data))));
        }
    }

}
