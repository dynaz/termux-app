package com.termux.app.terminow;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** The full build for direct installs does not sell Premium with Google Play. */
final class TerminowPlayBilling {

    static final boolean SUPPORTED = false;

    private TerminowPlayBilling() {}

    @Nullable
    static TerminowBilling open(@NonNull Activity activity, @NonNull TerminowBilling.Listener listener) {
        return null;
    }

}
