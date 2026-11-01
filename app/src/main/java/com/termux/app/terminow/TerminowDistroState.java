package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Lets a background scheduled backup ask whether an environment has live terminal sessions, so it can
 * skip it rather than back up files changing under it (and never stop the user's work). The launcher
 * registers a check backed by its live session list; while the app's process is alive the answer is
 * accurate. If the process was restarted and no launcher has registered yet, the answer is "not known
 * to be active" — safe, because the backup itself never stops or deletes anything either way.
 */
final class TerminowDistroState {

    interface ActivityCheck {
        boolean isActive(@NonNull String alias);
    }

    @Nullable private static volatile ActivityCheck sCheck;

    private TerminowDistroState() {}

    static void setCheck(@Nullable ActivityCheck check) {
        sCheck = check;
    }

    static boolean isActive(@NonNull String alias) {
        ActivityCheck check = sCheck;
        return check != null && !alias.isEmpty() && check.isActive(alias);
    }
}
