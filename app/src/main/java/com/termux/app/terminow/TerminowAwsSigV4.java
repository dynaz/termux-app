package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 signing, used to call the EC2 API with the person's own credentials. No
 * application-wide credentials are embedded; the access key, secret key and optional session token
 * come from the person for a single import. The algorithm follows the AWS specification and is tested
 * against AWS's published example, so the signature can be trusted without a live call.
 *
 * <p>Only HMAC-SHA256 and SHA-256 are used, both from the platform. Nothing here logs a credential.
 */
public final class TerminowAwsSigV4 {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String TERMINATOR = "aws4_request";

    private TerminowAwsSigV4() {}

    /**
     * Build the {@code Authorization} header for a request. {@code headers} must include {@code host}
     * and {@code x-amz-date} (and {@code x-amz-security-token} when a session token is used); its keys
     * are treated case-insensitively and signed in order.
     */
    @NonNull
    public static String authorization(@NonNull String method, @NonNull String service, @NonNull String region,
                                       @NonNull String canonicalUri, @NonNull String canonicalQuery,
                                       @NonNull Map<String, String> headers, @NonNull String payload,
                                       @NonNull String accessKey, @NonNull String secretKey,
                                       @NonNull String amzDate, @NonNull String dateStamp) {
        // Canonical headers, sorted by lower-cased name.
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : headers.entrySet())
            sorted.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue().trim());

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':').append(e.getValue()).append('\n');
            if (signedHeaders.length() > 0) signedHeaders.append(';');
            signedHeaders.append(e.getKey());
        }

        String canonicalRequest = method + '\n' + canonicalUri + '\n' + canonicalQuery + '\n'
            + canonicalHeaders + '\n' + signedHeaders + '\n' + sha256Hex(payload);

        String credentialScope = dateStamp + '/' + region + '/' + service + '/' + TERMINATOR;
        String stringToSign = ALGORITHM + '\n' + amzDate + '\n' + credentialScope + '\n' + sha256Hex(canonicalRequest);

        byte[] signingKey = signingKey(secretKey, dateStamp, region, service);
        String signature = hex(hmac(signingKey, stringToSign));

        return ALGORITHM + " Credential=" + accessKey + '/' + credentialScope
            + ", SignedHeaders=" + signedHeaders
            + ", Signature=" + signature;
    }

    static byte[] signingKey(@NonNull String secretKey, @NonNull String dateStamp, @NonNull String region, @NonNull String service) {
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        return hmac(kService, TERMINATOR);
    }

    static byte[] hmac(@NonNull byte[] key, @NonNull String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    @NonNull
    static String sha256Hex(@NonNull String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @NonNull
    static String hex(@NonNull byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
