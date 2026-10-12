package com.termux.app.terminow;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;

/**
 * The features of TermiNow Premium for Linux environments, checked the same way wherever they
 * are entered: the Linux file explorer, managing more than one environment, and tmux sessions.
 * Everything that was free stays free: opening Ubuntu, Claude Code, and installing the first
 * environment.
 */
public final class TerminowPremiumGate {

    public enum Feature { LINUX_FILES, MULTIPLE_ENVIRONMENTS, TMUX }

    private TerminowPremiumGate() {}

    /**
     * @param installedEnvironments How many Linux environments are installed now, for
     *                              {@link Feature#MULTIPLE_ENVIRONMENTS}: the first one is free.
     */
    public static boolean allows(@NonNull Feature feature, boolean premium, int installedEnvironments) {
        if (premium) return true;
        return feature == Feature.MULTIPLE_ENVIRONMENTS && installedEnvironments == 0;
    }

    /** Run the action if allowed, otherwise say that it is part of Premium. */
    public static void require(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull Feature feature,
                               int installedEnvironments, @NonNull Runnable action) {
        if (allows(feature, TerminowAccountUi.isPremium(store), installedEnvironments)) {
            action.run();
            return;
        }
        int what;
        switch (feature) {
            case LINUX_FILES: what = R.string.terminow_linux_premium_files; break;
            case MULTIPLE_ENVIRONMENTS: what = R.string.terminow_linux_premium_multiple; break;
            default: what = R.string.terminow_linux_premium_tmux; break;
        }
        new android.app.AlertDialog.Builder(activity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_vault_premium_title)
            .setMessage(activity.getString(R.string.terminow_linux_premium_message, activity.getString(what)))
            .setPositiveButton(R.string.terminow_vault_check_premium, (d, w) -> new TerminowAccountUi(activity, store, () -> {}).checkPremiumNow())
            .setNegativeButton(android.R.string.ok, null)
            .show();
    }

}
