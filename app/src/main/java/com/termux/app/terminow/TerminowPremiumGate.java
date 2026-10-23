package com.termux.app.terminow;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;

/**
 * The features of TermiNow Premium that are checked the same way wherever they are entered: the
 * Linux file explorer, managing more than one environment, tmux sessions, and MCP servers.
 * Everything that was free stays free: opening Ubuntu, Claude Code, and installing the first
 * environment.
 */
public final class TerminowPremiumGate {

    public enum Feature { LINUX_FILES, MULTIPLE_ENVIRONMENTS, TMUX, MCP, BACKUP }

    private TerminowPremiumGate() {}

    /**
     * @param installedEnvironments How many Linux environments are installed now, for
     *                              {@link Feature#MULTIPLE_ENVIRONMENTS}: the first one is free.
     */
    public static boolean allows(@NonNull Feature feature, boolean premium, int installedEnvironments) {
        if (premium) return true;
        return feature == Feature.MULTIPLE_ENVIRONMENTS && installedEnvironments == 0;
    }

    /** The gold of the crown of Premium. */
    public static final int PREMIUM_GOLD = 0xFFFFC107;

    /**
     * A label with the Premium badge after it, for a menu or a button of a feature that is part
     * of Premium: "Backup &amp; Restore  ♛ PREMIUM". Shown also with Premium, so the label says where
     * the feature belongs.
     */
    @NonNull
    public static CharSequence withBadge(@NonNull android.content.Context context, @NonNull CharSequence label) {
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(label).append("  ");
        int start = text.length();
        text.append("♛ ").append(context.getString(R.string.terminow_account_premium).toUpperCase(java.util.Locale.getDefault()));
        int end = text.length();
        text.setSpan(new android.text.style.ForegroundColorSpan(PREMIUM_GOLD), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new android.text.style.RelativeSizeSpan(0.75f), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
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
            case MCP: what = 0; break;
            case BACKUP: what = R.string.terminow_backup_premium_what; break;
            default: what = R.string.terminow_linux_premium_tmux; break;
        }
        TerminowAccountUi accountUi = new TerminowAccountUi(activity, store, () -> {});
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_vault_premium_title)
            .setMessage(feature == Feature.MCP ? activity.getString(R.string.terminow_mcp_premium_message) :
                activity.getString(R.string.terminow_linux_premium_message, activity.getString(what)))
            .setNegativeButton(android.R.string.ok, null);
        if (TerminowBilling.isSupported()) {
            builder.setPositiveButton(R.string.terminow_premium_get, (d, w) -> accountUi.buyPremium());
            builder.setNeutralButton(R.string.terminow_vault_check_premium, (d, w) -> accountUi.checkPremiumNow());
        } else {
            builder.setPositiveButton(R.string.terminow_vault_check_premium, (d, w) -> accountUi.checkPremiumNow());
        }
        builder.show();
    }

}
