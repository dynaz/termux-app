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

    /** For S3 uploads: stream the body without pre-hashing it (valid over HTTPS). */
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    /** SHA-256 of the empty string, the payload hash for a request with no body (GET/DELETE/LIST). */
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

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
        return authorizationWithHash(method, service, region, canonicalUri, canonicalQuery, headers,
            sha256Hex(payload), accessKey, secretKey, amzDate, dateStamp);
    }

    /**
     * Like {@link #authorization} but the payload is already hashed (its {@code x-amz-content-sha256}
     * value), so S3 can pass {@link #UNSIGNED_PAYLOAD} or {@link #EMPTY_SHA256} without a raw body.
     * {@code headers} must already contain {@code x-amz-content-sha256} equal to {@code payloadHash}
     * so it is signed.
     */
    @NonNull
    public static String authorizationWithHash(@NonNull String method, @NonNull String service, @NonNull String region,
                                                @NonNull String canonicalUri, @NonNull String canonicalQuery,
                                                @NonNull Map<String, String> headers, @NonNull String payloadHash,
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
            + canonicalHeaders + '\n' + signedHeaders + '\n' + payloadHash;

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

    /** RFC 3986 encoding. When {@code encodeSlash} is false, '/' is kept (for a canonical URI path). */
    @NonNull
    public static String uriEncode(@NonNull String value, boolean encodeSlash) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c);
            } else if (c == '/' && !encodeSlash) {
                out.append('/');
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xf, 16)))
                   .append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return out.toString();
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
