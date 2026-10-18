package com.termux.app.terminow;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.TermuxActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The vault switcher of the tab bar, like "Personal ▾": the Personal vault and its sync, the
 * Team vaults and their members, and the vault password. Syncing is part of TermiNow Premium.
 */
public class TerminowVaultsUi {

    /** Called after the active vault or its lists changed, to show them. */
    public interface Listener {
        void onVaultChanged();
    }

    private static final long SYNC_DELAY_MS = 4000;
    private static final long RESUME_SYNC_INTERVAL_MS = 60000;
    private static final int MIN_PASSWORD_LENGTH = 10;

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final Listener mListener;
    private final TerminowAccount mAccount;
    private final TerminowAccountUi mAccountUi;
    private final TerminowVaults mVaults;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private boolean mSyncing;
    @Nullable private String mSyncError;
    private long mLastSync;
    private final Runnable mSyncSoon = () -> syncQuietly(false);

    public TerminowVaultsUi(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull Listener listener) {
        mActivity = activity;
        mStore = store;
        mListener = listener;
        mAccount = new TerminowAccount(BuildConfig.TERMINOW_FIREBASE_API_KEY, activity.getPackageName());
        mAccountUi = new TerminowAccountUi(activity, store, listener::onVaultChanged);
        mVaults = new TerminowVaults(store, new TerminowVaults.Tokens() {
            @NonNull
            @Override
            public String getIdToken() throws TerminowVaults.VaultException {
                TerminowAccount.Session session = mStore.getAccount();
                if (session == null) throw new TerminowVaults.VaultException("Sign in first");
                try {
                    String token = mAccount.getIdToken(session);
                    // The refreshed session is kept, like the account screen does
                    mStore.setAccount(session);
                    return token;
                } catch (TerminowAccount.AccountException e) {
                    throw new TerminowVaults.VaultException(String.valueOf(e.getMessage()));
                }
            }

            @NonNull
            @Override
            public String getEmail() {
                TerminowAccount.Session session = mStore.getAccount();
                return session != null ? session.email : "";
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // The name in the tab bar
    // ---------------------------------------------------------------------------------------

    /**
     * The vaults that a host can be saved in, as pairs of the scope in the store and the name:
     * the personal vault first, then the team vaults that this phone has the key of.
     */
    @NonNull
    public java.util.List<String[]> getHostVaults() {
        java.util.List<String[]> vaults = new java.util.ArrayList<>();
        vaults.add(new String[]{"", mActivity.getString(R.string.terminow_vault_personal)});
        for (TerminowVaults.Info info : mVaults.getVaults())
            if (!info.isPersonal() && info.hasKey) vaults.add(new String[]{info.scope(), info.name});
        return vaults;
    }

    /** The name of the vault that is shown, "Personal" or the name of the team. */
    @NonNull
    public String getActiveName() {
        String active = mStore.getActiveVault();
        if (!active.isEmpty()) {
            TerminowVaults.Info info = mVaults.get(active);
            if (info != null) return info.name;
            // Gone from the list: back to the personal vault
            mStore.setActiveVault("");
        }
        return mActivity.getString(R.string.terminow_vault_personal);
    }

    /** Where syncing of the vault that is shown stands. */
    public enum State { OFF, BLOCKED, SYNCING, PENDING, SYNCED, FAILED }

    @NonNull
    public State getState() {
        if (mSyncing) return State.SYNCING;
        if (blockedReason() != 0) return mVaults.getPersonal() == null && mStore.getActiveVault().isEmpty() ? State.OFF : State.BLOCKED;
        if (mStore.getActiveVault().isEmpty() && mVaults.getPersonal() == null) return State.OFF;
        if (mSyncError != null) return State.FAILED;
        if (mStore.isVaultDirty(mStore.getActiveVault())) return State.PENDING;
        return State.SYNCED;
    }

    /** Why syncing can not run now, as a string resource, 0 when it can. */
    private int blockedReason() {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null) return R.string.terminow_vault_state_sign_in;
        if (!session.emailVerified) return R.string.terminow_vault_state_verify;
        if (!TerminowAccountUi.isPremium(mStore)) return R.string.terminow_vault_state_premium;
        if (!mVaults.isUnlocked()) return R.string.terminow_vault_state_locked;
        return 0;
    }

    /** One line that says whether the vault is synced, and if not, why. */
    @NonNull
    public String getStatusText() {
        switch (getState()) {
            case SYNCING: return mActivity.getString(R.string.terminow_vault_state_syncing);
            case OFF: {
                int reason = blockedReason();
                String text = mActivity.getString(R.string.terminow_vault_state_off);
                return reason == 0 || reason == R.string.terminow_vault_state_locked ? text : text + " · " + mActivity.getString(reason);
            }
            case BLOCKED: return mActivity.getString(R.string.terminow_vault_state_blocked, mActivity.getString(blockedReason()));
            case FAILED: return mActivity.getString(R.string.terminow_vault_sync_failed, mSyncError);
            case PENDING: return mActivity.getString(R.string.terminow_vault_state_pending);
            default: {
                long last = mStore.getVaultLastSync();
                return last <= 0 ? mActivity.getString(R.string.terminow_vault_state_synced_now) : mActivity.getString(R.string.terminow_vault_state_synced,
                    android.text.format.DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(),
                        android.text.format.DateUtils.MINUTE_IN_MILLIS));
            }
        }
    }

    /** The cloud icon next to the name: synced, syncing or waiting, not synced, or failed. */
    public int getSyncIcon() {
        switch (getState()) {
            case SYNCED: return R.drawable.ic_terminow_cloud;
            case SYNCING: case PENDING: return R.drawable.ic_terminow_cloud_sync;
            case FAILED: return R.drawable.ic_terminow_cloud_error;
            default: return R.drawable.ic_terminow_cloud_off;
        }
    }

    /** The status, and what to do next, for a tap on the cloud icon. */
    public void showStatus() {
        State state = getState();
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(getActiveName())
            .setMessage(getStatusText())
            .setNegativeButton(R.string.terminow_profiles_close, null)
            .setNeutralButton(R.string.terminow_vault_settings, (d, w) -> showSettings());
        if (state == State.OFF || state == State.BLOCKED)
            builder.setPositiveButton(R.string.terminow_vault_enable_sync, (d, w) -> ready(this::enablePersonalSync));
        else if (state != State.SYNCING)
            builder.setPositiveButton(R.string.terminow_vault_sync_now, (d, w) -> syncQuietly(true));
        builder.show();
    }

    // ---------------------------------------------------------------------------------------
    // Notifications of syncing
    // ---------------------------------------------------------------------------------------

    private static final String CHANNEL_ID = "terminow_vault_sync";
    private static final int NOTIFICATION_ID = 7301;

    /** A notification of Android, and an entry in the bell of the app. */
    private void notifySync(boolean ok, @NonNull String text) {
        notify(mActivity.getString(ok ? R.string.terminow_vault_notification_ok : R.string.terminow_vault_notification_failed), text);
    }

    private void notify(@NonNull String title, @NonNull String text) {
        mStore.addNotification(title, text);
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                com.termux.shared.notification.NotificationUtils.setupNotificationChannel(mActivity, CHANNEL_ID,
                    mActivity.getString(R.string.terminow_vault_notification_channel), android.app.NotificationManager.IMPORTANCE_DEFAULT);
            android.content.Intent intent = new android.content.Intent(mActivity, mActivity.getClass())
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent pending = android.app.PendingIntent.getActivity(mActivity, NOTIFICATION_ID, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.Notification.Builder builder = com.termux.shared.notification.NotificationUtils.geNotificationBuilder(mActivity,
                CHANNEL_ID, android.app.Notification.PRIORITY_DEFAULT, title, text, text, pending, null,
                com.termux.shared.notification.NotificationUtils.NOTIFICATION_MODE_SILENT);
            android.app.NotificationManager manager = com.termux.shared.notification.NotificationUtils.getNotificationManager(mActivity);
            if (builder == null || manager == null) return;
            builder.setSmallIcon(R.drawable.ic_service_notification);
            builder.setAutoCancel(true);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (RuntimeException e) {
            // The entry in the bell is there
        }
    }

    // ---------------------------------------------------------------------------------------
    // Syncing in the background
    // ---------------------------------------------------------------------------------------

    private boolean canSync() {
        TerminowAccount.Session session = mStore.getAccount();
        return session != null && session.emailVerified && mVaults.isUnlocked() && !mVaults.getVaults().isEmpty();
    }

    /** Sync a little after the lists of a synced vault were changed. */
    public void onListsChanged() {
        if (!canSync()) return;
        String active = mStore.getActiveVault();
        if (!mStore.isVaultDirty(active)) return;
        if (active.isEmpty() && mVaults.getPersonal() == null) return;
        mHandler.removeCallbacks(mSyncSoon);
        mHandler.postDelayed(mSyncSoon, SYNC_DELAY_MS);
    }

    /** Whether the Personal vault syncs with the server, else it is only on this phone. */
    public boolean isPersonalSynced() {
        return mVaults.getPersonal() != null;
    }

    /** Set up syncing of the Personal vault, with the vault password, like in the vault settings. */
    public void startPersonalSync() {
        ready(this::enablePersonalSync);
    }

    /** Sync the Personal vault a little later, after a change that is not in the vault that is shown. */
    public void syncPersonalSoon() {
        if (!canSync() || mVaults.getPersonal() == null) return;
        mHandler.removeCallbacks(mSyncSoon);
        mHandler.postDelayed(mSyncSoon, SYNC_DELAY_MS);
    }

    /** Sync everything when the app is shown again, at most once a minute. */
    public void onResume() {
        if (canSync() && System.currentTimeMillis() - mLastSync > RESUME_SYNC_INTERVAL_MS) syncQuietly(false);
        offerPersonalSync();
    }

    /**
     * Once per account that can sync (signed in, verified, Premium), while the Personal vault is
     * only on this phone: ask whether to sync it. Nothing is uploaded unless the user says so.
     */
    public void offerPersonalSync() {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null || mVaults.getPersonal() != null || !mStore.getActiveVault().isEmpty()) return;
        int reason = blockedReason();
        if (reason != 0 && reason != R.string.terminow_vault_state_locked) return;
        if (mStore.isPersonalSyncOffered(session.uid)) return;
        mStore.setPersonalSyncOffered(session.uid);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_vault_offer_title)
            .setMessage(mActivity.getString(R.string.terminow_vault_offer_message) + "\n\n" + mActivity.getString(R.string.terminow_vault_about))
            .setPositiveButton(R.string.terminow_vault_enable_sync, (d, w) -> ready(this::enablePersonalSync))
            .setNegativeButton(R.string.terminow_vault_offer_later, null)
            .show();
    }

    private void syncQuietly(boolean report) {
        if (mSyncing) return;
        if (!canSync()) {
            // Asked for: say why it does not sync instead of doing nothing
            if (report) showStatus();
            return;
        }
        mSyncing = true;
        mListener.onVaultChanged();
        mExecutor.execute(() -> {
            String error = null;
            try {
                List<String> errors = mVaults.syncAll();
                if (!errors.isEmpty()) error = TextUtils.join("\n", errors);
            } catch (TerminowVaults.VaultException e) {
                error = e.getMessage();
            }
            String result = error;
            mHandler.post(() -> {
                String before = mSyncError;
                mSyncing = false;
                mLastSync = System.currentTimeMillis();
                mSyncError = result;
                if (result == null) mStore.setVaultLastSync(mLastSync);
                mListener.onVaultChanged();
                // Asked for, or a change: failing, or working again; not every quiet sync
                if (report || (result != null && !result.equals(before)) || (result == null && before != null)) {
                    if (result != null)
                        notifySync(false, mActivity.getString(R.string.terminow_vault_sync_failed, result));
                    else if (getState() == State.OFF)
                        // The other vaults synced, the Personal vault is not synced at all: never "synced"
                        notify(mActivity.getString(R.string.terminow_vault_notification_personal_off),
                            mActivity.getString(R.string.terminow_vault_personal_off_message));
                    else
                        notifySync(true, getStatusText());
                }
                if (report)
                    mActivity.showToast(result == null ? mActivity.getString(R.string.terminow_vault_synced) :
                        mActivity.getString(R.string.terminow_vault_sync_failed, result), true);
            });
        });
    }

    // ---------------------------------------------------------------------------------------
    // The switcher
    // ---------------------------------------------------------------------------------------

    public void showSwitcher(@NonNull View anchor) {
        LinearLayout menu = new LinearLayout(mActivity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundResource(R.drawable.terminow_menu);
        menu.setPadding(0, dp(8), 0, dp(8));
        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(menu);
        PopupWindow popup = new PopupWindow(scroll, dp(360), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(8));
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        String active = mStore.getActiveVault();
        boolean unlocked = mVaults.isUnlocked();
        TerminowVaults.Info personal = mVaults.getPersonal();

        // Personal
        int personalAction = personal != null && unlocked ? R.string.terminow_vault_sync_now : R.string.terminow_vault_enable_sync;
        addRow(menu, popup, mActivity.getString(R.string.terminow_vault_personal), active.isEmpty() ? getStatusText() : null, active.isEmpty(),
            personal != null && unlocked ? R.drawable.ic_terminow_cloud : R.drawable.ic_terminow_cloud_off,
            mActivity.getString(personalAction), true,
            () -> switchTo(""),
            () -> {
                if (personal != null && unlocked) syncQuietly(true);
                else ready(this::enablePersonalSync);
            });

        // Teams
        boolean anyTeam = false;
        for (TerminowVaults.Info info : mVaults.getVaults()) {
            if (info.isPersonal()) continue;
            anyTeam = true;
            if (!info.hasKey || !unlocked) {
                addRow(menu, popup, info.name, false, R.drawable.ic_terminow_person_add,
                    mActivity.getString(unlocked ? R.string.terminow_vault_waiting : R.string.terminow_vault_unlock), false,
                    () -> ready(() -> explainWaiting(info)), () -> ready(() -> explainWaiting(info)));
                continue;
            }
            addRow(menu, popup, info.name, info.id.equals(active), R.drawable.ic_terminow_person_add,
                mActivity.getString(info.isOwner() ? R.string.terminow_vault_invite : R.string.terminow_vault_members), true,
                () -> switchTo(info.id), () -> ready(() -> showInviteTeam(info)));
        }
        if (!anyTeam) {
            addRow(menu, popup, mActivity.getString(R.string.terminow_vault_team), false, R.drawable.ic_terminow_person_add,
                mActivity.getString(R.string.terminow_vault_create_team), true,
                () -> ready(this::createTeam), () -> ready(this::createTeam));
        }

        View divider = new View(mActivity);
        divider.setBackgroundColor(color(R.color.terminow_field));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.setMargins(dp(16), dp(6), dp(16), dp(6));
        menu.addView(divider, dividerParams);
        if (anyTeam)
            addSimpleRow(menu, popup, R.string.terminow_vault_new_team, () -> ready(this::createTeam));
        addSimpleRow(menu, popup, R.string.terminow_vault_settings, this::showSettings);

        if (mSyncError != null) {
            TextView error = text(mActivity.getString(R.string.terminow_vault_sync_failed, mSyncError), 12, R.color.terminow_mcp_warn);
            error.setPadding(dp(20), dp(6), dp(20), dp(6));
            menu.addView(error, matchWrap());
        }
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    private void addRow(@NonNull LinearLayout menu, @NonNull PopupWindow popup, @NonNull String name, boolean selected,
                        int actionIcon, @NonNull String action, boolean enabled, @NonNull Runnable onRow, @NonNull Runnable onAction) {
        addRow(menu, popup, name, null, selected, actionIcon, action, enabled, onRow, onAction);
    }

    private void addRow(@NonNull LinearLayout menu, @NonNull PopupWindow popup, @NonNull String name, @Nullable String status, boolean selected,
                        int actionIcon, @NonNull String action, boolean enabled, @NonNull Runnable onRow, @NonNull Runnable onAction) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(8), 0);
        if (selected) row.setBackgroundColor(color(R.color.terminow_field));
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_terminow_vault);
        icon.setColorFilter(color(selected ? R.color.terminow_panel_accent : R.color.terminow_text_secondary), PorterDuff.Mode.SRC_IN);
        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView nameView = text(name, 16, enabled ? R.color.terminow_text : R.color.terminow_text_secondary);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(mActivity);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setGravity(Gravity.CENTER_VERTICAL);
        names.setPadding(dp(14), dp(6), dp(8), dp(6));
        names.addView(nameView);
        if (status != null) {
            // Whether it is synced, and if not, why, right where sync is turned on
            TextView statusView = text(status, 12, getState() == State.FAILED ? R.color.terminow_mcp_warn : R.color.terminow_text_secondary);
            statusView.setMaxLines(2);
            statusView.setEllipsize(TextUtils.TruncateAt.END);
            names.addView(statusView);
        }
        names.setMinimumHeight(dp(52));
        row.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        names.setOnClickListener(v -> {
            popup.dismiss();
            onRow.run();
        });
        LinearLayout actionView = new LinearLayout(mActivity);
        actionView.setGravity(Gravity.CENTER_VERTICAL);
        actionView.setPadding(dp(8), 0, dp(8), 0);
        ImageView actionIconView = new ImageView(mActivity);
        actionIconView.setImageResource(actionIcon);
        actionIconView.setColorFilter(color(R.color.terminow_text_secondary), PorterDuff.Mode.SRC_IN);
        actionView.addView(actionIconView, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView actionText = text(action, 13, R.color.terminow_text_secondary);
        actionText.setPadding(dp(6), 0, 0, 0);
        actionView.addView(actionText);
        actionView.setOnClickListener(v -> {
            popup.dismiss();
            onAction.run();
        });
        row.addView(actionView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        menu.addView(row, matchWrap());
    }

    private void addSimpleRow(@NonNull LinearLayout menu, @NonNull PopupWindow popup, int labelRes, @NonNull Runnable onClick) {
        TextView row = text(mActivity.getString(labelRes), 15, R.color.terminow_text);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(52), 0, dp(16), 0);
        row.setOnClickListener(v -> {
            popup.dismiss();
            onClick.run();
        });
        menu.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
    }

    private void switchTo(@NonNull String id) {
        mStore.setActiveVault(id);
        mListener.onVaultChanged();
        if (canSync()) syncQuietly(false);
    }

    // ---------------------------------------------------------------------------------------
    // Getting ready: signed in, verified, Premium, vault password entered on this phone
    // ---------------------------------------------------------------------------------------

    /** Run the action once syncing can be used, asking for whatever is missing first. */
    private void ready(@NonNull Runnable action) {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null) {
            message(R.string.terminow_vault_sign_in_title, mActivity.getString(R.string.terminow_vault_sign_in_message),
                R.string.terminow_account_sign_in, mAccountUi::showSignIn);
            return;
        }
        if (!session.emailVerified) {
            message(R.string.terminow_vault_sign_in_title, mActivity.getString(R.string.terminow_vault_verify_message), 0, null);
            return;
        }
        if (!TerminowAccountUi.isPremium(mStore)) {
            if (TerminowBilling.isSupported())
                new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                    .setTitle(R.string.terminow_vault_premium_title)
                    .setMessage(mActivity.getString(R.string.terminow_vault_premium_message, session.email))
                    .setPositiveButton(R.string.terminow_premium_get, (d, w) -> mAccountUi.buyPremium())
                    .setNeutralButton(R.string.terminow_vault_check_premium, (d, w) -> mAccountUi.checkPremiumNow())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            else
                message(R.string.terminow_vault_premium_title, mActivity.getString(R.string.terminow_vault_premium_message, session.email),
                    R.string.terminow_vault_check_premium, mAccountUi::checkPremiumNow);
            return;
        }
        if (mVaults.isUnlocked()) {
            action.run();
            return;
        }
        work(R.string.terminow_vault_checking, mVaults::refresh, me -> {
            if (me.optJSONObject("account") == null) askNewPassword(false, action);
            else askPassword(action);
        });
    }

    private void askNewPassword(boolean reset, @NonNull Runnable then) {
        LinearLayout box = box();
        box.addView(text(mActivity.getString(reset ? R.string.terminow_vault_reset_message : R.string.terminow_vault_new_password_message),
            13, R.color.terminow_text_secondary), matchWrap());
        EditText password = passwordField(R.string.terminow_vault_password);
        EditText again = passwordField(R.string.terminow_vault_password_again);
        box.addView(password, matchWrap());
        box.addView(again, matchWrap());
        TextView error = text("", 13, R.color.terminow_mcp_warn);
        box.addView(error, matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(reset ? R.string.terminow_vault_reset_title : R.string.terminow_vault_new_password_title)
            .setView(box)
            .setPositiveButton(reset ? R.string.terminow_vault_reset : R.string.terminow_vault_set_password, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = password.getText().toString();
            if (value.length() < MIN_PASSWORD_LENGTH) {
                error.setText(mActivity.getString(R.string.terminow_vault_password_short, MIN_PASSWORD_LENGTH));
                return;
            }
            if (!value.equals(again.getText().toString())) {
                error.setText(R.string.terminow_vault_password_mismatch);
                return;
            }
            dialog.dismiss();
            work(R.string.terminow_vault_making_keys, () -> {
                if (reset) mVaults.resetAccount(value);
                else mVaults.createAccount(value);
                return new JSONObject();
            }, r -> then.run());
        }));
        dialog.show();
    }

    private void askPassword(@NonNull Runnable then) {
        LinearLayout box = box();
        box.addView(text(mActivity.getString(R.string.terminow_vault_unlock_message), 13, R.color.terminow_text_secondary), matchWrap());
        EditText password = passwordField(R.string.terminow_vault_password);
        box.addView(password, matchWrap());
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_vault_unlock_title)
            .setView(box)
            .setPositiveButton(R.string.terminow_vault_unlock, (d, w) -> {
                String value = password.getText().toString();
                work(R.string.terminow_vault_unlocking, () -> {
                    mVaults.unlock(value);
                    return new JSONObject();
                }, r -> then.run());
            })
            .setNeutralButton(R.string.terminow_vault_forgot, (d, w) -> askNewPassword(true, then))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Personal and team vaults
    // ---------------------------------------------------------------------------------------

    private void enablePersonalSync() {
        work(R.string.terminow_vault_syncing, () -> {
            mVaults.enablePersonalSync();
            return new JSONObject();
        }, r -> {
            mSyncError = null;
            mLastSync = System.currentTimeMillis();
            mStore.setVaultLastSync(mLastSync);
            mListener.onVaultChanged();
            notifySync(true, mActivity.getString(R.string.terminow_vault_personal_enabled));
            mActivity.showToast(mActivity.getString(R.string.terminow_vault_personal_enabled), true);
        });
    }

    private void createTeam() {
        LinearLayout box = box();
        box.addView(text(mActivity.getString(R.string.terminow_vault_team_message), 13, R.color.terminow_text_secondary), matchWrap());
        EditText name = new EditText(mActivity);
        name.setSingleLine(true);
        name.setHint(R.string.terminow_vault_team_name_hint);
        name.setTextColor(color(R.color.terminow_text));
        name.setHintTextColor(color(R.color.terminow_text_secondary));
        box.addView(name, matchWrap());
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_vault_new_team)
            .setView(box)
            .setPositiveButton(R.string.terminow_vault_create, (d, w) -> {
                String value = name.getText().toString().trim();
                if (value.isEmpty()) return;
                work(R.string.terminow_vault_creating, () -> new JSONObject().put("id", mVaults.createTeam(value)), r -> {
                    switchTo(r.optString("id"));
                    TerminowVaults.Info info = mVaults.get(r.optString("id"));
                    if (info != null) showInviteTeam(info);
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void explainWaiting(@NonNull TerminowVaults.Info info) {
        if (info.hasKey) {
            switchTo(info.id);
            return;
        }
        message(R.string.terminow_vault_waiting, mActivity.getString(R.string.terminow_vault_waiting_message, info.name, info.owner,
            mVaults.getFingerprint()), R.string.terminow_vault_leave, () -> confirmLeave(info, () -> { }));
    }

    // ---------------------------------------------------------------------------------------
    // Invite your team, like Termius: the members of the team, the owner with a crown, and
    // Invite team members. The chip of the hosts shows their initials and opens it.
    // ---------------------------------------------------------------------------------------

    /** The members of each team as the server last listed them, for the chip. */
    private final java.util.Map<String, JSONArray> mMembers = new java.util.HashMap<>();
    @Nullable private LinearLayout mTeamChip;
    private static final int[] AVATAR_COLORS = {0xFFF8B4E6, 0xFF8FC7FF, 0xFF9BE3B5, 0xFFFFD08A, 0xFFC7B6FF, 0xFFFFA8A0};

    /**
     * The team that inviting is for: the one that is shown, else the first that this account
     * owns, else the first one; {@code null} without a team.
     */
    @Nullable
    private TerminowVaults.Info inviteTeam() {
        TerminowVaults.Info active = mVaults.get(mStore.getActiveVault());
        if (active != null && !active.isPersonal()) return active;
        TerminowVaults.Info first = null;
        for (TerminowVaults.Info info : mVaults.getVaults()) {
            if (info.isPersonal()) continue;
            if (info.isOwner()) return info;
            if (first == null) first = info;
        }
        return first;
    }

    /** The chip at the end of the actions of the hosts: initials of the team, then +. */
    public void bindTeamChip(@NonNull LinearLayout chip) {
        mTeamChip = chip;
        chip.setOnClickListener(v -> showInviteTeam(null));
        renderTeamChip();
    }

    /** After the vault that is shown, the account or the teams changed. */
    public void refreshTeamChip() {
        renderTeamChip();
    }

    private void renderTeamChip() {
        LinearLayout chip = mTeamChip;
        if (chip == null) return;
        chip.removeAllViews();
        List<String> emails = new java.util.ArrayList<>();
        TerminowVaults.Info team = inviteTeam();
        JSONArray members = team != null ? mMembers.get(team.id) : null;
        for (int i = 0; members != null && i < members.length() && emails.size() < 3; i++) {
            JSONObject member = members.optJSONObject(i);
            if (member != null) emails.add(member.optString("email"));
        }
        TerminowAccount.Session session = mStore.getAccount();
        if (emails.isEmpty() && session != null) emails.add(session.email);
        for (int i = 0; i < emails.size(); i++) {
            TextView avatar = avatar(emails.get(i), 32, 16);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32));
            // Overlapping a little, like the faces of Termius
            if (i > 0) params.setMarginStart(-dp(8));
            chip.addView(avatar, params);
        }
        TextView plus = text("+", 18, R.color.terminow_text_secondary);
        plus.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(color(R.color.terminow_field));
        plus.setBackground(circle);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32));
        if (!emails.isEmpty()) params.setMarginStart(-dp(6));
        chip.addView(plus, params);
    }

    /** A rounded square with the first letter of an email, in a color of that email. */
    @NonNull
    private TextView avatar(@NonNull String email, int sizeDp, int textSp) {
        String letter = email.isEmpty() ? "?" : email.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        TextView avatar = text(letter, textSp, R.color.terminow_text);
        avatar.setTextColor(Color.WHITE);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(AVATAR_COLORS[Math.abs(email.toLowerCase(java.util.Locale.ROOT).hashCode() % AVATAR_COLORS.length)]);
        shape.setCornerRadius(dp(sizeDp * 0.28f));
        shape.setStroke(dp(2), color(R.color.terminow_panel_accent));
        avatar.setBackground(shape);
        return avatar;
    }

    /**
     * Invite your team: the members of {@code team}, or of the team that inviting is for when
     * it is {@code null}. Without a team, Invite team members creates one first.
     */
    public void showInviteTeam(@Nullable TerminowVaults.Info team) {
        TerminowVaults.Info info = team != null ? team : inviteTeam();
        android.app.Dialog dialog = new android.app.Dialog(mActivity, R.style.TerminowPanelDialog);
        android.widget.FrameLayout root = new android.widget.FrameLayout(mActivity);
        root.setBackgroundColor(color(R.color.terminow_panel_scrim));
        root.setOnClickListener(v -> dialog.dismiss());

        LinearLayout sheet = new LinearLayout(mActivity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(color(R.color.terminow_panel));
        background.setCornerRadius(dp(28));
        sheet.setBackground(background);
        sheet.setPadding(dp(16), dp(12), dp(16), dp(24));

        android.widget.ImageButton back = new android.widget.ImageButton(mActivity);
        back.setImageResource(R.drawable.ic_terminow_back);
        back.setColorFilter(color(R.color.terminow_text));
        back.setBackgroundResource(android.R.color.transparent);
        back.setContentDescription(mActivity.getString(R.string.terminow_rdp_back));
        back.setOnClickListener(v -> dialog.dismiss());
        sheet.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        ScrollView scroll = new ScrollView(mActivity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(content);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_terminow_team);
        content.addView(icon, new LinearLayout.LayoutParams(dp(96), dp(96)));
        TextView title = text(mActivity.getString(R.string.terminow_team_invite_title), 26, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(16), 0, dp(10));
        content.addView(title, matchWrap());
        TextView about = text(info != null ? mActivity.getString(R.string.terminow_team_invite_message_team, info.name) :
            mActivity.getString(R.string.terminow_team_invite_message), 15, R.color.terminow_text);
        about.setGravity(Gravity.CENTER);
        about.setLineSpacing(0, 1.25f);
        about.setPadding(dp(8), 0, dp(8), dp(20));
        content.addView(about, matchWrap());

        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.terminow_panel_card);
        content.addView(card, matchWrap());
        TextView note = text("", 12, R.color.terminow_text_secondary);
        note.setPadding(dp(8), dp(14), dp(8), 0);
        content.addView(note, matchWrap());
        TextView leave = link("");
        leave.setTextColor(color(R.color.terminow_mcp_warn));
        leave.setPadding(dp(8), dp(14), dp(8), dp(4));
        content.addView(leave);
        leave.setVisibility(View.GONE);

        Runnable[] load = new Runnable[1];
        load[0] = () -> {
            card.removeAllViews();
            if (info == null) {
                // No team yet: this account would be its owner
                TerminowAccount.Session session = mStore.getAccount();
                String me = session != null ? session.email : mActivity.getString(R.string.terminow_team_you);
                memberRow(card, me, true, null, null);
                inviteRow(card, () -> {
                    dialog.dismiss();
                    ready(this::createTeam);
                });
                note.setText(R.string.terminow_team_invite_note_new);
                return;
            }
            ProgressBar spinner = new ProgressBar(mActivity);
            LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(32), dp(32));
            spinnerParams.gravity = Gravity.CENTER_HORIZONTAL;
            spinnerParams.setMargins(0, dp(20), 0, dp(20));
            card.addView(spinner, spinnerParams);
            mExecutor.execute(() -> {
                JSONArray members = null;
                String error = null;
                try {
                    members = mVaults.getMembers(info.id);
                } catch (TerminowVaults.VaultException e) {
                    error = e.status == 402 ? mActivity.getString(R.string.terminow_vault_premium_needed) : e.getMessage();
                } catch (Exception e) {
                    error = String.valueOf(e.getMessage());
                }
                JSONArray finalMembers = members;
                String finalError = error;
                mHandler.post(() -> {
                    if (!dialog.isShowing()) return;
                    card.removeAllViews();
                    if (finalMembers == null) {
                        TextView failed = text(mActivity.getString(R.string.terminow_team_load_failed, finalError), 14, R.color.terminow_mcp_warn);
                        failed.setPadding(dp(16), dp(16), dp(16), 0);
                        card.addView(failed, matchWrap());
                        TextView retry = link(mActivity.getString(R.string.terminow_team_retry));
                        retry.setPadding(dp(16), dp(8), dp(16), dp(16));
                        retry.setOnClickListener(v -> load[0].run());
                        card.addView(retry);
                        return;
                    }
                    mMembers.put(info.id, finalMembers);
                    renderTeamChip();
                    for (int i = 0; i < finalMembers.length(); i++) {
                        JSONObject member = finalMembers.optJSONObject(i);
                        if (member == null) continue;
                        String email = member.optString("email");
                        boolean owner = "owner".equals(member.optString("role"));
                        String status = member.optString("status");
                        String publicKey = member.isNull("publicKey") ? "" : member.optString("publicKey");
                        String state = owner ? null : "active".equals(status) ? mActivity.getString(R.string.terminow_vault_member_active) :
                            "waiting".equals(status) ? mActivity.getString(R.string.terminow_vault_member_waiting, TerminowVaultCrypto.fingerprint(publicKey)) :
                            mActivity.getString(R.string.terminow_vault_member_invited);
                        Runnable manage = !info.isOwner() || owner ? null : () -> manageMember(info, email, status, publicKey, load[0]);
                        memberRow(card, email, owner, state, manage);
                    }
                    if (info.isOwner()) inviteRow(card, () -> invite(info, load[0]));
                    note.setText(info.isOwner() ? mActivity.getString(R.string.terminow_vault_members_note) :
                        mActivity.getString(R.string.terminow_team_invite_note_member, info.owner));
                    leave.setText(info.isOwner() ? R.string.terminow_vault_delete_team : R.string.terminow_vault_leave);
                    leave.setVisibility(View.VISIBLE);
                    leave.setOnClickListener(v -> confirmLeave(info, dialog::dismiss));
                });
            });
        };
        load[0].run();

        android.widget.FrameLayout.LayoutParams sheetParams = new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        root.addView(sheet, sheetParams);
        // A card in the middle like Termius, with the room of the screen around it on a large screen
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left - dp(32), dp(640));
            int height = Math.min(bottom - top - dp(48), dp(760));
            ViewGroup.LayoutParams params = sheet.getLayoutParams();
            if (params.width != width || params.height != height) {
                params.width = width;
                params.height = height;
                sheet.post(() -> sheet.setLayoutParams(params));
            }
        });
        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dialog.show();
    }

    private void memberRow(@NonNull LinearLayout card, @NonNull String email, boolean owner, @Nullable String state,
                           @Nullable Runnable onClick) {
        if (card.getChildCount() > 0) divider(card);
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.addView(avatar(email, 52, 22), new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(8), 0);
        LinearLayout line = new LinearLayout(mActivity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(email, 16, R.color.terminow_text);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (owner) {
            // The crown of the owner, in a dark badge
            ImageView crown = new ImageView(mActivity);
            crown.setImageResource(R.drawable.ic_terminow_crown);
            crown.setColorFilter(color(R.color.terminow_text_secondary));
            crown.setPadding(dp(6), dp(3), dp(6), dp(3));
            android.graphics.drawable.GradientDrawable badge = new android.graphics.drawable.GradientDrawable();
            badge.setColor(color(R.color.terminow_bar_home));
            badge.setCornerRadius(dp(6));
            crown.setBackground(badge);
            crown.setContentDescription(mActivity.getString(R.string.terminow_vault_member_owner));
            LinearLayout.LayoutParams crownParams = new LinearLayout.LayoutParams(dp(36), dp(24));
            crownParams.setMarginStart(dp(8));
            line.addView(crown, crownParams);
            // The name takes only its room, so the crown is next to it
            name.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
        }
        texts.addView(line, matchWrap());
        if (state != null) {
            TextView sub = text(state, 13, R.color.terminow_text_secondary);
            sub.setPadding(0, dp(2), 0, 0);
            texts.addView(sub, matchWrap());
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (onClick != null) {
            ImageView more = new ImageView(mActivity);
            more.setImageResource(R.drawable.ic_terminow_more);
            more.setColorFilter(color(R.color.terminow_text_secondary));
            row.addView(more, new LinearLayout.LayoutParams(dp(24), dp(24)));
            selectable(row);
            row.setOnClickListener(v -> onClick.run());
        }
        card.addView(row, matchWrap());
    }

    private void inviteRow(@NonNull LinearLayout card, @NonNull Runnable onClick) {
        if (card.getChildCount() > 0) divider(card);
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView plus = text("+", 30, R.color.terminow_panel_accent);
        plus.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable tile = new android.graphics.drawable.GradientDrawable();
        tile.setColor(color(R.color.terminow_field));
        tile.setCornerRadius(dp(14));
        plus.setBackground(tile);
        row.addView(plus, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView label = text(mActivity.getString(R.string.terminow_team_invite_members), 16, R.color.terminow_panel_accent);
        label.setPadding(dp(14), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectable(row);
        row.setOnClickListener(v -> onClick.run());
        card.addView(row, matchWrap());
    }

    /** Give access to a member that is waiting for it, or remove a member, for the owner. */
    private void manageMember(@NonNull TerminowVaults.Info info, @NonNull String email, @NonNull String status,
                              @NonNull String publicKey, @NonNull Runnable then) {
        List<CharSequence> actions = new java.util.ArrayList<>();
        boolean waiting = "waiting".equals(status);
        if (waiting) actions.add(mActivity.getString(R.string.terminow_vault_grant));
        actions.add(mActivity.getString(R.string.terminow_vault_remove));
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(email)
            .setItems(actions.toArray(new CharSequence[0]), (d, which) -> {
                if (waiting && which == 0) confirmGrant(info, email, publicKey, then);
                else confirmRemove(info, email, then);
            })
            .show();
    }

    private void divider(@NonNull LinearLayout card) {
        View divider = new View(mActivity);
        divider.setBackgroundColor(color(R.color.terminow_field));
        card.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void selectable(@NonNull View view) {
        TypedValue value = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        view.setForeground(ContextCompat.getDrawable(mActivity, value.resourceId));
    }

    private void invite(@NonNull TerminowVaults.Info info, @NonNull Runnable then) {
        LinearLayout box = box();
        box.addView(text(mActivity.getString(R.string.terminow_vault_invite_message), 13, R.color.terminow_text_secondary), matchWrap());
        EditText email = new EditText(mActivity);
        email.setSingleLine(true);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setHint("name@example.com");
        email.setTextColor(color(R.color.terminow_text));
        email.setHintTextColor(color(R.color.terminow_text_secondary));
        box.addView(email, matchWrap());
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_vault_invite_title, info.name))
            .setView(box)
            .setPositiveButton(R.string.terminow_vault_invite, (d, w) -> work(R.string.terminow_vault_inviting, () -> {
                mVaults.invite(info.id, email.getText().toString());
                return new JSONObject();
            }, r -> then.run()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmGrant(@NonNull TerminowVaults.Info info, @NonNull String email, @NonNull String publicKey, @NonNull Runnable then) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_vault_grant)
            .setMessage(mActivity.getString(R.string.terminow_vault_grant_message, email, TerminowVaultCrypto.fingerprint(publicKey), info.name))
            .setPositiveButton(R.string.terminow_vault_grant, (d, w) -> work(R.string.terminow_vault_granting, () -> {
                mVaults.grant(info.id, email, publicKey);
                return new JSONObject();
            }, r -> then.run()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmRemove(@NonNull TerminowVaults.Info info, @NonNull String email, @NonNull Runnable then) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_vault_remove_title, email))
            .setMessage(R.string.terminow_vault_remove_message)
            .setPositiveButton(R.string.terminow_vault_remove, (d, w) -> work(R.string.terminow_vault_removing, () -> {
                mVaults.removeMember(info.id, email);
                return new JSONObject();
            }, r -> then.run()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmLeave(@NonNull TerminowVaults.Info info, @NonNull Runnable then) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(info.isOwner() ? mActivity.getString(R.string.terminow_vault_delete_team_title, info.name) :
                mActivity.getString(R.string.terminow_vault_leave_title, info.name))
            .setMessage(info.isOwner() ? R.string.terminow_vault_delete_team_message : R.string.terminow_vault_leave_message)
            .setPositiveButton(info.isOwner() ? R.string.terminow_vault_delete_team : R.string.terminow_vault_leave, (d, w) ->
                work(R.string.terminow_vault_working, () -> {
                    mVaults.leaveOrDelete(info);
                    return new JSONObject();
                }, r -> {
                    mMembers.remove(info.id);
                    mListener.onVaultChanged();
                    renderTeamChip();
                    then.run();
                }))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Vault password, fingerprint, sync now and lock, from the switcher and the settings. */
    public void showSettings() {
        TerminowAccount.Session session = mStore.getAccount();
        boolean unlocked = mVaults.isUnlocked();
        StringBuilder summary = new StringBuilder();
        summary.append(session == null ? mActivity.getString(R.string.terminow_vault_not_signed_in) : session.email);
        summary.append("\n").append(unlocked ? mActivity.getString(R.string.terminow_vault_fingerprint, mVaults.getFingerprint()) :
            mActivity.getString(R.string.terminow_vault_locked));
        TerminowVaults.Info personal = mVaults.getPersonal();
        summary.append("\n").append(mActivity.getString(personal != null ? R.string.terminow_vault_personal_synced :
            R.string.terminow_vault_personal_local));
        if (mSyncError != null) summary.append("\n").append(mActivity.getString(R.string.terminow_vault_sync_failed, mSyncError));
        summary.append("\n\n").append(mActivity.getString(R.string.terminow_vault_about));

        CharSequence[] actions = unlocked ? new CharSequence[]{mActivity.getString(R.string.terminow_vault_sync_now),
            mActivity.getString(R.string.terminow_vault_change_password), mActivity.getString(R.string.terminow_vault_lock)} :
            new CharSequence[]{mActivity.getString(R.string.terminow_vault_unlock)};
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_vault_settings);
        TextView message = text(summary.toString(), 13, R.color.terminow_text_secondary);
        message.setPadding(dp(24), dp(8), dp(24), dp(8));
        message.setTextIsSelectable(true);
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(message, matchWrap());
        AlertDialog[] dialog = new AlertDialog[1];
        for (int i = 0; i < actions.length; i++) {
            int which = i;
            TextView action = link(actions[i].toString());
            action.setPadding(dp(24), dp(12), dp(24), dp(12));
            action.setOnClickListener(v -> {
                dialog[0].dismiss();
                if (!unlocked) ready(() -> mListener.onVaultChanged());
                else if (which == 0) ready(() -> syncQuietly(true));
                else if (which == 1) changePassword();
                else {
                    mVaults.lock();
                    mSyncError = null;
                    mListener.onVaultChanged();
                    mActivity.showToast(mActivity.getString(R.string.terminow_vault_locked_done), true);
                }
            });
            box.addView(action, matchWrap());
        }
        dialog[0] = builder.setView(box).setNegativeButton(R.string.terminow_profiles_close, null).show();
    }

    private void changePassword() {
        LinearLayout box = box();
        EditText password = passwordField(R.string.terminow_vault_password);
        EditText again = passwordField(R.string.terminow_vault_password_again);
        box.addView(password, matchWrap());
        box.addView(again, matchWrap());
        TextView error = text("", 13, R.color.terminow_mcp_warn);
        box.addView(error, matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_vault_change_password)
            .setView(box)
            .setPositiveButton(R.string.terminow_vault_set_password, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = password.getText().toString();
            if (value.length() < MIN_PASSWORD_LENGTH) {
                error.setText(mActivity.getString(R.string.terminow_vault_password_short, MIN_PASSWORD_LENGTH));
                return;
            }
            if (!value.equals(again.getText().toString())) {
                error.setText(R.string.terminow_vault_password_mismatch);
                return;
            }
            dialog.dismiss();
            work(R.string.terminow_vault_making_keys, () -> {
                mVaults.changePassword(value);
                return new JSONObject();
            }, r -> mActivity.showToast(mActivity.getString(R.string.terminow_vault_password_changed), true));
        }));
        dialog.show();
    }

    // ---------------------------------------------------------------------------------------
    // Work in the background with a small progress dialog
    // ---------------------------------------------------------------------------------------

    private interface Work {
        @NonNull JSONObject run() throws Exception;
    }

    private interface Done {
        void done(@NonNull JSONObject result);
    }

    private void work(int messageRes, @NonNull Work work, @NonNull Done done) {
        LinearLayout box = new LinearLayout(mActivity);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(20));
        ProgressBar spinner = new ProgressBar(mActivity);
        box.addView(spinner, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView label = text(mActivity.getString(messageRes), 15, R.color.terminow_text);
        label.setPadding(dp(16), 0, 0, 0);
        box.addView(label);
        AlertDialog progress = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog).setView(box).setCancelable(false).show();
        mExecutor.execute(() -> {
            JSONObject result = null;
            String error = null;
            try {
                result = work.run();
            } catch (TerminowVaults.VaultException e) {
                error = e.status == 402 ? mActivity.getString(R.string.terminow_vault_premium_needed) : e.getMessage();
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            JSONObject finalResult = result;
            String finalError = error;
            mHandler.post(() -> {
                if (!mActivity.isFinishing()) progress.dismiss();
                if (finalError != null) {
                    if (messageRes == R.string.terminow_vault_syncing) {
                        mSyncError = finalError;
                        mListener.onVaultChanged();
                        notifySync(false, mActivity.getString(R.string.terminow_vault_sync_failed, finalError));
                    }
                    message(R.string.terminow_vault_error_title, finalError, 0, null);
                    return;
                }
                done.done(finalResult != null ? finalResult : new JSONObject());
            });
        });
    }

    private void message(int titleRes, @NonNull String message, int actionRes, @Nullable Runnable action) {
        if (mActivity.isFinishing()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(titleRes)
            .setMessage(message)
            .setNegativeButton(android.R.string.ok, null);
        if (action != null) builder.setPositiveButton(actionRes, (d, w) -> action.run());
        builder.show();
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    private LinearLayout box() {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        return box;
    }

    @NonNull
    private EditText passwordField(int hintRes) {
        EditText field = new EditText(mActivity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setHint(hintRes);
        field.setTextColor(color(R.color.terminow_text));
        field.setHintTextColor(color(R.color.terminow_text_secondary));
        return field;
    }

    @NonNull
    private TextView link(@NonNull String label) {
        TextView link = text(label, 14, R.color.terminow_panel_accent);
        link.setTypeface(Typeface.DEFAULT_BOLD);
        link.setPadding(0, dp(8), 0, 0);
        return link;
    }

    @NonNull
    private TextView text(@NonNull String text, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, mActivity.getResources().getDisplayMetrics()));
    }

}
