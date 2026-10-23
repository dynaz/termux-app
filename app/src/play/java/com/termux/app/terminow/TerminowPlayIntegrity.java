package com.termux.app.terminow;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.StandardIntegrityManager;
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest;
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider;
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest;

/** Play Integrity tokens of the standard API, in the Play build only. */
final class TerminowPlayIntegrity {

    static final boolean SUPPORTED = true;

    private static StandardIntegrityTokenProvider sProvider;

    private TerminowPlayIntegrity() {}

    /** Ask Google Play for a token bound to {@code requestHash}; the callback runs on the main thread. */
    static void request(@NonNull Context context, long cloudProjectNumber, @NonNull String requestHash,
                        @NonNull TerminowIntegrity.Callback callback) {
        if (cloudProjectNumber <= 0) {
            callback.done(null, "NOT_CONFIGURED");
            return;
        }
        StandardIntegrityTokenProvider provider = sProvider;
        if (provider != null) {
            requestWith(provider, requestHash, callback, context, cloudProjectNumber, true);
            return;
        }
        StandardIntegrityManager manager = IntegrityManagerFactory.createStandard(context.getApplicationContext());
        manager.prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build())
            .addOnSuccessListener(prepared -> {
                sProvider = prepared;
                requestWith(prepared, requestHash, callback, context, cloudProjectNumber, false);
            })
            .addOnFailureListener(e -> callback.done(null, "PREPARE_FAILED"));
    }

    private static void requestWith(@NonNull StandardIntegrityTokenProvider provider, @NonNull String requestHash,
                                    @NonNull TerminowIntegrity.Callback callback, @NonNull Context context,
                                    long cloudProjectNumber, boolean mayPrepareAgain) {
        provider.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build())
            .addOnSuccessListener(token -> callback.done(token.token(), null))
            .addOnFailureListener(e -> {
                // A provider that is too old is prepared once more
                if (mayPrepareAgain) {
                    sProvider = null;
                    request(context, cloudProjectNumber, requestHash, callback);
                } else {
                    callback.done(null, "REQUEST_FAILED");
                }
            });
    }
}
