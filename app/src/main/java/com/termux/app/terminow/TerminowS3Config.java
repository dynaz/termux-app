package com.termux.app.terminow;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The connection settings for an S3 / S3-compatible bucket, as the user enters them. A pure data
 * holder: {@link #toJson()}/{@link #fromJson} round-trip every field (so it is unit-tested without a
 * keystore), while encrypting the whole thing at rest is the store's job (it wraps the JSON with
 * {@link TerminowSecret} before saving, so the secret key is never stored in the clear).
 */
final class TerminowS3Config {

    String endpoint = "";       // no scheme, e.g. s3.amazonaws.com or minio.example.com:9000
    boolean tls = true;
    String region = "us-east-1";
    String bucket = "";
    String accessKey = "";
    String secretKey = "";
    boolean pathStyle = true;   // on by default: works with MinIO/B2/Wasabi, and with AWS too
    /** Optional: when set, scheduled backups to this destination are encrypted with this password. */
    String encryptionPassword = "";

    boolean isComplete() {
        return !endpoint.trim().isEmpty() && !region.trim().isEmpty() && !bucket.trim().isEmpty()
            && !accessKey.trim().isEmpty() && !secretKey.isEmpty();
    }

    @NonNull
    TerminowS3Provider.Config toProviderConfig() {
        return new TerminowS3Provider.Config(endpoint.trim(), tls, region.trim(), bucket.trim(),
            accessKey.trim(), secretKey, pathStyle);
    }

    @NonNull
    TerminowS3Provider toProvider() {
        return new TerminowS3Provider(toProviderConfig());
    }

    @NonNull
    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("endpoint", endpoint);
        o.put("tls", tls);
        o.put("region", region);
        o.put("bucket", bucket);
        o.put("accessKey", accessKey);
        o.put("secretKey", secretKey);
        o.put("pathStyle", pathStyle);
        o.put("encryptionPassword", encryptionPassword);
        return o;
    }

    @NonNull
    static TerminowS3Config fromJson(@NonNull String json) {
        TerminowS3Config c = new TerminowS3Config();
        try {
            JSONObject o = new JSONObject(json);
            c.endpoint = o.optString("endpoint", "");
            c.tls = o.optBoolean("tls", true);
            c.region = o.optString("region", "us-east-1");
            c.bucket = o.optString("bucket", "");
            c.accessKey = o.optString("accessKey", "");
            c.secretKey = o.optString("secretKey", "");
            c.pathStyle = o.optBoolean("pathStyle", true);
            c.encryptionPassword = o.optString("encryptionPassword", "");
        } catch (JSONException ignored) {
            // A corrupt value leaves an empty, incomplete config.
        }
        return c;
    }
}
