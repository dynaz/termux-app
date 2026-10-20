package com.termux.app.terminow;

import android.content.Context;

import androidx.annotation.NonNull;

/** The full build is not installed from Google Play: it asks for no Play Integrity token. */
final class TerminowPlayIntegrity {

    static final boolean SUPPORTED = false;

    private TerminowPlayIntegrity() {}

    static void request(@NonNull Context context, long cloudProjectNumber, @NonNull String requestHash,
                        @NonNull TerminowIntegrity.Callback callback) {
        callback.done(null, "UNSUPPORTED");
    }
}
