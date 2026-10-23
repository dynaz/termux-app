package com.termux.app.terminow;

import android.app.AlertDialog;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialInterruptedException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.GetCredentialUnsupportedException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.TermuxActivity;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Date;

/**
 * The account rows of the settings and their dialogs: sign in, create an account, verify the
 * email, check Premium, sign out and delete the account.
 *
 * Premium comes from the accmove.com client area or, in the Play build, from a Google Play
 * subscription bought here (see {@link TerminowBilling}). The app never links to buying it
 * elsewhere, as Google Play requires for apps distributed on Play.
 */
public class TerminowAccountUi {

    /** How old the Premium status may be before it is checked again when the settings open. */
    private static final long PREMIUM_MAX_AGE_MS = 6 * 60 * 60 * 1000;

    public interface Rows {
        void addRow(int titleRes, @Nullable String summary, @Nullable Runnable onClick);
    }

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final TerminowAccount mAccount;
    private final Runnable mOnChanged;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mBusy;
    /** Told when the account, Premium or a purchase changed, on the main thread. */
    private final List<Runnable> mListeners = new ArrayList<>();
    /** Whether a Google sign in runs, so that it is never started twice. */
    private boolean mGoogleBusy;
    @Nullable private String mPremiumError;

    public TerminowAccountUi(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull Runnable onChanged) {
        mActivity = activity;
        mStore = store;
        // The settings, and everything else that shows the account or Premium, like the toolbar
        mOnChanged = () -> {
            onChanged.run();
            for (Runnable listener : new ArrayList<>(mListeners)) listener.run();
        };
        mAccount = new TerminowAccount(BuildConfig.TERMINOW_FIREBASE_API_KEY, mActivity.getPackageName());
        mAccount.setFirebaseBackend(BuildConfig.TERMINOW_FIREBASE_PROJECT_ID, BuildConfig.TERMINOW_FUNCTIONS_REGION);
        mPurchases = new TerminowPlayPurchases(activity, store, mAccount);
    }

    private final TerminowPlayPurchases mPurchases;

    /** Add the rows of the account section. */
    public void addRows(@NonNull Rows rows) {
        if (!mAccount.isConfigured()) {
            rows.addRow(R.string.terminow_account_not_configured, null, null);
            return;
        }
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null) {
            rows.addRow(R.string.terminow_account_sign_in, mActivity.getString(R.string.terminow_account_sign_in_summary), this::showSignIn);
            return;
        }

        rows.addRow(R.string.terminow_account_email, session.email + "\n" + mActivity.getString(session.emailVerified ?
            R.string.terminow_account_verified : R.string.terminow_account_not_verified), session.emailVerified ? null : () -> showVerify(session));
        rows.addRow(R.string.terminow_account_premium, getPremiumSummary(session), () -> checkPremium(session, true));
        if (TerminowBilling.isSupported() && session.emailVerified) {
            JSONObject play = mStore.getPlayEntitlement();
            boolean hasPlay = play != null && play.optJSONObject("google_play") != null;
            if (hasPlay)
                rows.addRow(R.string.terminow_premium_manage, mActivity.getString(R.string.terminow_premium_manage_summary), this::openManage);
            // Never offered while a purchase waits for its payment or its check, or when the
            // build can not have a purchase verified: a payment would never become Premium
            if (!isPremium(mStore) && !TerminowPlayPurchases.isPurchaseInProgress(mStore) && mAccount.hasFirebaseBackend())
                rows.addRow(R.string.terminow_premium_get, mActivity.getString(R.string.terminow_premium_get_summary), this::buyPremium);
            rows.addRow(R.string.terminow_premium_restore, mActivity.getString(R.string.terminow_premium_restore_summary), this::restorePurchases);
        }
        if (session.emailVerified)
            rows.addRow(R.string.terminow_account_link, mActivity.getString(R.string.terminow_account_link_summary), () -> showLinkAccount(session));
        rows.addRow(R.string.terminow_account_sign_out, null, this::confirmSignOut);
        rows.addRow(R.string.terminow_account_delete, mActivity.getString(R.string.terminow_account_delete_summary), () -> confirmDelete(session));

        JSONObject premium = mStore.getPremium();
        if (session.emailVerified && !mBusy && mPremiumError == null &&
            (premium == null || System.currentTimeMillis() - premium.optLong("checked") > PREMIUM_MAX_AGE_MS))
            checkPremium(session, false);
    }

    public void addChangeListener(@NonNull Runnable listener) {
        if (!mListeners.contains(listener)) mListeners.add(listener);
    }

    public void removeChangeListener(@NonNull Runnable listener) {
        mListeners.remove(listener);
    }

    @NonNull
    TerminowStore getStore() {
        return mStore;
    }

    @NonNull
    TerminowAccount getAccount() {
        return mAccount;
    }

    /** Tell everything that shows the account that it changed. */
    void notifyChanged() {
        mOnChanged.run();
    }

    /** How often this device tells the server that it is active, when the app comes back. */
    private static final long DEVICE_REGISTER_INTERVAL_MS = 10 * 60 * 1000;
    private static long sDeviceRegistered;
    private static String sDeviceRegisteredId = "";

    /**
     * Tell the server that this device is signed in, for the Devices of the account, at most
     * every few minutes unless {@code force}. A device that was signed out from another one is
     * signed out here.
     */
    void registerDevice(boolean force) {
        TerminowAccount.Session session = mStore.getAccount();
        String deviceId = mStore.getAccountDeviceId();
        if (session == null || deviceId.isEmpty() || !mAccount.hasFirebaseBackend()) return;
        long now = System.currentTimeMillis();
        if (!force && deviceId.equals(sDeviceRegisteredId) && now - sDeviceRegistered < DEVICE_REGISTER_INTERVAL_MS) return;
        sDeviceRegistered = now;
        sDeviceRegisteredId = deviceId;
        String installId = mStore.getInstallId();
        String name = deviceName();
        String kind = mActivity.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY) ? "phone" : "tablet";
        new Thread(() -> {
            boolean signedIn;
            try {
                signedIn = mAccount.registerDevice(session, deviceId, installId, name, kind, BuildConfig.VERSION_NAME);
            } catch (TerminowAccount.AccountException e) {
                // Offline, or the server is not there yet: the next time
                sDeviceRegistered = 0;
                return;
            }
            mHandler.post(() -> {
                // Only the same sign in: one that started meanwhile is not touched
                if (!deviceId.equals(mStore.getAccountDeviceId())) return;
                if (mStore.getAccount() != null) mStore.setAccount(session);
                if (signedIn) return;
                mStore.setAccount(null);
                mPremiumError = null;
                mOnChanged.run();
                showMessage(R.string.terminow_account_sign_out, mActivity.getString(R.string.terminow_devices_signed_out_here));
            });
        }, "TerminowDevice").start();
    }

    /** Like "Samsung SM-F946B", as the devices of the account show it. */
    @NonNull
    static String deviceName() {
        String maker = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER.trim();
        String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL.trim();
        if (!maker.isEmpty()) maker = maker.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + maker.substring(1);
        if (model.toLowerCase(java.util.Locale.ROOT).startsWith(maker.toLowerCase(java.util.Locale.ROOT))) return model;
        return (maker + " " + model).trim();
    }

    /**
     * Sign out on this device only: it is removed from the devices of the account first, if the
     * server can be reached. Everything on the device stays.
     */
    void signOutHere() {
        TerminowAccount.Session session = mStore.getAccount();
        String deviceId = mStore.getAccountDeviceId();
        mStore.setAccount(null);
        mPremiumError = null;
        mOnChanged.run();
        if (session == null || deviceId.isEmpty() || !mAccount.hasFirebaseBackend()) return;
        new Thread(() -> {
            try {
                mAccount.forgetDevice(session, deviceId);
            } catch (TerminowAccount.AccountException e) {
                // It stays in the list until it is signed out from another device
            }
        }, "TerminowDeviceForget").start();
    }

    /**
     * Ask for the code of the authenticator app to finish a sign in that needs 2FA, then save the
     * session like any sign in.
     */
    void askMfaCode(@NonNull TerminowAccount.MfaRequiredException pending, @Nullable AlertDialog signInDialog) {
        LinearLayout form = form();
        TextView note = new TextView(mActivity);
        note.setText(pending.factorName.isEmpty() ? mActivity.getString(R.string.terminow_mfa_code_message) :
            mActivity.getString(R.string.terminow_mfa_code_message_named, pending.factorName));
        note.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        note.setPadding(0, 0, 0, dp(8));
        form.addView(note);
        EditText codeView = field(form, R.string.terminow_mfa_code, InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mfa_title)
            .setView(form)
            .setPositiveButton(R.string.terminow_account_sign_in_button, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = codeView.getText().toString().trim();
            if (!code.matches("[0-9]{6,8}")) {
                codeView.setError(mActivity.getString(R.string.terminow_mfa_code_invalid));
                return;
            }
            final TerminowAccount.Session[] result = new TerminowAccount.Session[1];
            run(() -> result[0] = mAccount.finishMfaSignIn(pending, code), () -> {
                dialog.dismiss();
                if (signInDialog != null) signInDialog.dismiss();
                onSignedIn(result[0]);
            });
        }));
        dialog.show();
    }

    /** Save a new session, then show and check what it has. */
    private void onSignedIn(@NonNull TerminowAccount.Session session) {
        mStore.setAccount(session);
        mPremiumError = null;
        mOnChanged.run();
        registerDevice(true);
        if (!session.emailVerified)
            showMessage(R.string.terminow_account_verify_title, mActivity.getString(R.string.terminow_account_verify_sent, session.email));
        else
            checkPremiumNow();
    }

    /** Whether accounts can be used in this build, with its Firebase project. */
    boolean isConfigured() {
        return mAccount.isConfigured();
    }

    /** Whether Google sign in is set up in this build. */
    static boolean hasGoogleSignIn() {
        return !BuildConfig.TERMINOW_GOOGLE_WEB_CLIENT_ID.isEmpty();
    }

    /** Whether Premium features may be used now, see {@link TerminowEntitlement}. */
    public static boolean isPremium(@NonNull TerminowStore store) {
        return TerminowEntitlement.get(store) == TerminowEntitlement.State.ACTIVE;
    }

    /**
     * Check the Google Play purchases of this device when the app comes back, at most every
     * few minutes, so that a purchase whose check failed or a renewal is picked up.
     */
    public void recoverPurchases(@Nullable Runnable onDone) {
        registerDevice(false);
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null || !session.emailVerified || mBusy) return;
        mPurchases.recover(false, error -> {
            mOnChanged.run();
            // Premium may have changed: what shows it, like the MCP page, is drawn again
            if (onDone != null) onDone.run();
        });
    }

    /** Check Premium with WHMCS and Google Play now, like tapping the Premium row. */
    public void checkPremiumNow() {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null) {
            showSignIn();
            return;
        }
        checkPremium(session, true);
    }

    public boolean isCheckingPremium() {
        return mBusy;
    }

    /** Why the last check failed, {@code null} if it did not. */
    @Nullable
    public String getPremiumError() {
        return mPremiumError;
    }

    @NonNull
    String getPremiumSummary(@NonNull TerminowAccount.Session session) {
        if (!session.emailVerified) return mActivity.getString(R.string.terminow_account_premium_verify_first);
        if (mBusy) return mActivity.getString(R.string.terminow_account_premium_checking);
        JSONObject premium = mStore.getPremium();
        String summary;
        if (premium == null) {
            summary = mActivity.getString(R.string.terminow_account_premium_unknown);
        } else if (premium.optBoolean("premium")) {
            String due = premium.isNull("nextDueDate") ? "" : premium.optString("nextDueDate");
            summary = mActivity.getString(R.string.terminow_account_premium_active, premium.optString("plan")) +
                (due.isEmpty() ? "" : "\n" + mActivity.getString(R.string.terminow_account_premium_until, due));
        } else {
            summary = mActivity.getString(R.string.terminow_account_premium_inactive, session.email);
        }
        if (premium != null)
            summary += "\n" + mActivity.getString(R.string.terminow_account_premium_checked,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(premium.optLong("checked"))));
        String play = getPlaySummary();
        if (play != null) summary += "\n" + play;
        if (mPremiumError != null) summary += "\n" + mPremiumError;
        return summary;
    }

    /** What the Google Play purchase of the account is, from the server, and what was last tried. */
    @Nullable
    String getPlaySummary() {
        JSONObject status = mStore.getPlayPurchaseStatus();
        TerminowPlayPurchases.Status state = TerminowPlayPurchases.getStatus(mStore);
        JSONObject entitlement = mStore.getPlayEntitlement();
        JSONObject play = entitlement != null ? entitlement.optJSONObject("google_play") : null;
        String code = status != null && !status.isNull("code") ? status.optString("code") : "";
        String test = (status != null && status.optBoolean("test")) || (play != null && play.optBoolean("test")) ?
            " " + mActivity.getString(R.string.terminow_premium_test_marker) : "";
        if (state == TerminowPlayPurchases.Status.PENDING) return mActivity.getString(R.string.terminow_premium_status_pending);
        if (state == TerminowPlayPurchases.Status.VERIFYING) return mActivity.getString(R.string.terminow_premium_status_verifying);
        if (state == TerminowPlayPurchases.Status.UNAVAILABLE) return mActivity.getString(R.string.terminow_premium_status_unavailable, code);
        if (state == TerminowPlayPurchases.Status.REJECTED) return mActivity.getString(R.string.terminow_premium_status_rejected, code);
        if (play == null) return null;
        String expires = play.optLong("expiresAt") > 0 ?
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(play.optLong("expiresAt"))) : "";
        if (play.optBoolean("premium"))
            return mActivity.getString(play.optBoolean("autoRenewEnabled") ? R.string.terminow_premium_status_active_renews :
                R.string.terminow_premium_status_active_ends, expires) + test;
        if ("SUBSCRIPTION_STATE_ON_HOLD".equals(play.optString("subscriptionState")) || "SUBSCRIPTION_STATE_PAUSED".equals(play.optString("subscriptionState")))
            return mActivity.getString(R.string.terminow_premium_status_on_hold) + test;
        return mActivity.getString(R.string.terminow_premium_status_expired) + test;
    }

    // -----------------------------------------------------------------------------------------

    void showSignIn() {
        LinearLayout form = form();
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        if (hasGoogleSignIn()) {
            TerminowGoogleButton google = new TerminowGoogleButton(mActivity);
            google.setOnClickListener(v -> signInWithGoogle(dialogHolder[0], google));
            form.addView(google, TerminowGoogleButton.fullWidth(form, 8, 4));
            form.addView(divider(R.string.terminow_account_or_email));
        }
        EditText emailView = field(form, R.string.terminow_account_email, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailView.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        EditText passwordView = passwordField(form);
        TextView note = new TextView(mActivity);
        note.setText(R.string.terminow_account_sign_in_note);
        note.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(0, dp(12), 0, 0);

        // A forgotten password is reset by email
        TextView forgot = new TextView(mActivity);
        forgot.setText(R.string.terminow_account_forgot);
        forgot.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_panel_accent));
        forgot.setPadding(0, dp(12), 0, dp(4));
        forgot.setMinHeight(dp(48));
        forgot.setGravity(android.view.Gravity.CENTER_VERTICAL);
        forgot.setOnClickListener(v -> {
            String email = emailView.getText().toString().trim();
            if (email.isEmpty()) {
                emailView.setError(mActivity.getString(R.string.terminow_account_enter_email));
                emailView.requestFocus();
                return;
            }
            run(() -> mAccount.sendPasswordReset(email), () ->
                mActivity.showToast(mActivity.getString(R.string.terminow_account_reset_sent, email), true));
        });
        form.addView(forgot);
        form.addView(note);

        // Scrolls on small screens and with the keyboard open
        android.widget.ScrollView scroll = new android.widget.ScrollView(mActivity);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_account_sign_in)
            .setView(scroll)
            .setPositiveButton(R.string.terminow_account_sign_in_button, null)
            .setNeutralButton(R.string.terminow_account_create, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialogHolder[0] = dialog;
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit(dialog, emailView, passwordView, false));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> submit(dialog, emailView, passwordView, true));
        });
        // Enter on the password signs in
        passwordView.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submit(dialog, emailView, passwordView, false);
                return true;
            }
            return false;
        });
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    /** A line with a text in the middle, like "or sign in with email". */
    @NonNull
    android.view.View divider(int textRes) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(4));
        for (int i = 0; i < 3; i++) {
            if (i == 1) {
                TextView text = new TextView(mActivity);
                text.setText(textRes);
                text.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                text.setPadding(dp(12), 0, dp(12), 0);
                row.addView(text);
            } else {
                android.view.View line = new android.view.View(mActivity);
                line.setBackgroundColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
                line.setAlpha(0.35f);
                row.addView(line, new LinearLayout.LayoutParams(0, Math.max(1, dp(1)), 1));
            }
        }
        return row;
    }

    /** A password field with a button that shows or hides it. */
    @NonNull
    private EditText passwordField(@NonNull LinearLayout form) {
        android.widget.FrameLayout box = new android.widget.FrameLayout(mActivity);
        EditText field = new EditText(mActivity);
        int hidden = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        int shown = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        field.setHint(R.string.terminow_account_password);
        field.setInputType(hidden);
        field.setSingleLine(true);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        field.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
        field.setHintTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        field.setPaddingRelative(field.getPaddingStart(), field.getPaddingTop(), dp(48), field.getPaddingBottom());
        box.addView(field, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        android.widget.ImageButton toggle = new android.widget.ImageButton(mActivity);
        toggle.setImageResource(R.drawable.ic_terminow_lock);
        toggle.setColorFilter(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        toggle.setBackgroundResource(android.R.color.transparent);
        toggle.setContentDescription(mActivity.getString(R.string.terminow_account_password_show));
        toggle.setOnClickListener(v -> {
            boolean show = field.getInputType() == hidden;
            int cursor = field.getSelectionEnd();
            field.setInputType(show ? shown : hidden);
            field.setSelection(Math.max(0, Math.min(cursor, field.length())));
            toggle.setColorFilter(ContextCompat.getColor(mActivity, show ? R.color.terminow_panel_accent : R.color.terminow_text_secondary));
            toggle.setContentDescription(mActivity.getString(show ? R.string.terminow_account_password_hide : R.string.terminow_account_password_show));
        });
        android.widget.FrameLayout.LayoutParams toggleParams = new android.widget.FrameLayout.LayoutParams(dp(48), dp(48),
            android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        box.addView(toggle, toggleParams);
        form.addView(box);
        return field;
    }

    /** Show the Google account picker of Android, then sign in to Firebase with its ID token. */
    void signInWithGoogle(@Nullable AlertDialog dialog, @Nullable TerminowGoogleButton button) {
        if (mGoogleBusy) return;
        mGoogleBusy = true;
        if (button != null) button.setLoading(true);
        Runnable done = () -> {
            mGoogleBusy = false;
            if (button != null) button.setLoading(false);
        };
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(BuildConfig.TERMINOW_GOOGLE_WEB_CLIENT_ID).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        CredentialManager.create(mActivity).getCredentialAsync(mActivity, request, new CancellationSignal(),
            ContextCompat.getMainExecutor(mActivity), new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override
                public void onResult(GetCredentialResponse response) {
                    Credential credential = response.getCredential();
                    String idToken = null;
                    if (credential instanceof CustomCredential &&
                        GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
                        try {
                            idToken = GoogleIdTokenCredential.createFrom(credential.getData()).getIdToken();
                        } catch (Exception e) {
                            idToken = null;
                        }
                    }
                    if (idToken == null) {
                        done.run();
                        showMessage(R.string.terminow_account_google_failed_title, mActivity.getString(R.string.terminow_account_google_no_token));
                        return;
                    }
                    String token = idToken;
                    final TerminowAccount.Session[] result = new TerminowAccount.Session[1];
                    final TerminowAccount.MfaRequiredException[] mfa = new TerminowAccount.MfaRequiredException[1];
                    // Signing in gives no Premium: that is only what the server verified
                    runThen(() -> {
                        try {
                            result[0] = mAccount.signInWithGoogle(token);
                        } catch (TerminowAccount.MfaRequiredException e) {
                            mfa[0] = e;
                        }
                    }, error -> {
                        done.run();
                        if (error != null) {
                            showMessage(R.string.terminow_account_google_failed_title, error);
                            return;
                        }
                        if (mfa[0] != null) {
                            askMfaCode(mfa[0], dialog);
                            return;
                        }
                        if (dialog != null) dialog.dismiss();
                        // The Premium of this account, from the server
                        onSignedIn(result[0]);
                    });
                }

                @Override
                public void onError(GetCredentialException e) {
                    done.run();
                    // Closing the picker is not an error
                    if (e instanceof GetCredentialCancellationException) return;
                    showMessage(R.string.terminow_account_google_failed_title, describeGoogleError(e));
                }
            });
    }

    /** What a failed Google sign in means, and what to do about it. */
    @NonNull
    String describeGoogleError(@NonNull GetCredentialException e) {
        if (e instanceof NoCredentialException) return mActivity.getString(R.string.terminow_account_google_no_account);
        if (e instanceof GetCredentialInterruptedException) return mActivity.getString(R.string.terminow_account_google_interrupted);
        if (e instanceof GetCredentialProviderConfigurationException || e instanceof GetCredentialUnsupportedException)
            return mActivity.getString(R.string.terminow_account_google_unsupported);
        return mActivity.getString(R.string.terminow_account_google_failed, String.valueOf(e.getMessage()));
    }

    private void submit(@NonNull AlertDialog dialog, @NonNull EditText emailView, @NonNull EditText passwordView, boolean create) {
        String email = emailView.getText().toString().trim();
        String password = passwordView.getText().toString();
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailView.setError(mActivity.getString(R.string.terminow_account_enter_email));
            return;
        }
        if (password.length() < (create ? 8 : 1)) {
            passwordView.setError(mActivity.getString(create ? R.string.terminow_account_password_short : R.string.terminow_account_enter_password));
            return;
        }
        final TerminowAccount.Session[] result = new TerminowAccount.Session[1];
        final TerminowAccount.MfaRequiredException[] mfa = new TerminowAccount.MfaRequiredException[1];
        run(() -> {
            try {
                result[0] = create ? mAccount.signUp(email, password) : mAccount.signIn(email, password);
            } catch (TerminowAccount.MfaRequiredException e) {
                mfa[0] = e;
            }
        }, () -> {
            if (mfa[0] != null) {
                askMfaCode(mfa[0], dialog);
                return;
            }
            dialog.dismiss();
            onSignedIn(result[0]);
        });
    }

    void showVerify(@NonNull TerminowAccount.Session session) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_account_verify_title)
            .setMessage(mActivity.getString(R.string.terminow_account_verify_message, session.email))
            .setPositiveButton(R.string.terminow_account_verify_done, (d, w) -> run(() -> mAccount.refreshVerified(session), () -> {
                mStore.setAccount(session);
                if (!session.emailVerified)
                    mActivity.showToast(mActivity.getString(R.string.terminow_account_still_not_verified), true);
                mOnChanged.run();
            }))
            .setNeutralButton(R.string.terminow_account_verify_resend, (d, w) -> run(() -> mAccount.sendVerificationEmail(session), () ->
                mActivity.showToast(mActivity.getString(R.string.terminow_account_verify_sent, session.email), true)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void checkPremium(@NonNull TerminowAccount.Session session, boolean userAsked) {
        if (mBusy) return;
        if (!session.emailVerified) {
            if (userAsked) showVerify(session);
            return;
        }
        mBusy = true;
        mPremiumError = null;
        mOnChanged.run();
        // Google Play purchases first: one whose check failed before is given to the server again
        mPurchases.recover(userAsked, playError -> new Thread(() -> {
            TerminowAccount.Premium premium = null;
            String error = playError;
            try {
                premium = mAccount.checkPremium(session);
            } catch (TerminowAccount.AccountException e) {
                error = e.getMessage();
            }
            TerminowAccount.Premium finalPremium = premium;
            String finalError = error;
            mHandler.post(() -> {
                mBusy = false;
                // The refresh token may have been replaced
                if (mStore.getAccount() != null) mStore.setAccount(session);
                if (finalPremium != null) mStore.setPremium(finalPremium);
                mPremiumError = finalError != null ? mActivity.getString(R.string.terminow_account_premium_failed, finalError) : null;
                mOnChanged.run();
            });
        }).start());
    }

    /** Give every Google Play purchase of this device to the server again, like after a reinstall. */
    void restorePurchases() {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null || mBusy) return;
        mBusy = true;
        mOnChanged.run();
        mPurchases.recover(true, error -> {
            mBusy = false;
            mOnChanged.run();
            String summary = getPlaySummary();
            showMessage(R.string.terminow_premium_restore, error != null ? error :
                summary != null ? summary : mActivity.getString(R.string.terminow_premium_restore_none));
        });
    }

    /** Link an existing accmove.com client with the one-time code of its client area. */
    void showLinkAccount(@NonNull TerminowAccount.Session session) {
        LinearLayout form = form();
        TextView note = new TextView(mActivity);
        note.setText(R.string.terminow_account_link_note);
        note.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        form.addView(note);
        EditText codeView = field(form, R.string.terminow_account_link_code, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_account_link)
            .setView(form)
            .setPositiveButton(R.string.terminow_account_link_button, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = codeView.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);
            if (!code.matches("[A-Z0-9]{8}")) {
                codeView.setError(mActivity.getString(R.string.terminow_account_link_code_invalid));
                return;
            }
            run(() -> mAccount.linkWhmcsAccount(session, code), () -> {
                dialog.dismiss();
                showMessage(R.string.terminow_account_link, mActivity.getString(R.string.terminow_account_linked));
            });
        }));
        dialog.show();
    }

    // -----------------------------------------------------------------------------------------
    // Buying Premium with Google Play, in the Play build

    /** Show the plans of Premium and buy one with Google Play. Needs a verified account. */
    public void buyPremium() {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null) {
            showSignIn();
            return;
        }
        if (!session.emailVerified) {
            showVerify(session);
            return;
        }
        if (!mAccount.hasFirebaseBackend()) {
            showMessage(R.string.terminow_premium_choose_title, mActivity.getString(R.string.terminow_premium_not_set_up));
            return;
        }
        // A purchase that waits for its payment or its check is never bought again
        if (TerminowPlayPurchases.isPurchaseInProgress(mStore)) {
            String summary = getPlaySummary();
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_premium_choose_title)
                .setMessage(summary != null ? summary : mActivity.getString(R.string.terminow_premium_status_verifying))
                .setPositiveButton(R.string.terminow_premium_check_again, (d, w) -> restorePurchases())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        final TerminowBilling[] holder = new TerminowBilling[1];
        holder[0] = TerminowBilling.create(mActivity, new TerminowBilling.Listener() {
            @Override
            public void onPurchases(@NonNull List<TerminowBilling.Owned> purchases) {
                holder[0].end();
                onPurchased(session, purchases);
            }

            @Override
            public void onCancelled() {
                holder[0].end();
            }

            @Override
            public void onError(@NonNull String message) {
                holder[0].end();
                mActivity.showToast(message, true);
            }
        });
        if (holder[0] == null) return;
        AlertDialog progress = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(R.string.terminow_account_working)
            .show();
        // An account that has a subscription on this device has it checked instead of buying again
        holder[0].queryOwned((owned, ownedError) -> {
            String accountId = TerminowBilling.accountId(session.uid);
            boolean hasOwn = false;
            if (owned != null) for (TerminowBilling.Owned purchase : owned) hasOwn |= accountId.equals(purchase.accountId);
            if (hasOwn) {
                progress.dismiss();
                holder[0].end();
                onPurchased(session, owned);
                return;
            }
            showPlans(session, holder, progress);
        });
    }

    private void showPlans(@NonNull TerminowAccount.Session session, @NonNull TerminowBilling[] holder, @NonNull AlertDialog progress) {
        holder[0].queryPlans((plans, error) -> {
            progress.dismiss();
            if (plans == null || plans.isEmpty()) {
                holder[0].end();
                mActivity.showToast(error != null ? error : mActivity.getString(R.string.terminow_premium_unavailable), true);
                return;
            }
            LinearLayout form = form();
            TextView message = new TextView(mActivity);
            message.setText(R.string.terminow_premium_choose_message);
            message.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
            message.setPadding(0, 0, 0, dp(8));
            form.addView(message);
            AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_premium_choose_title)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
            final boolean[] buying = new boolean[1];
            dialog.setOnDismissListener(d -> {
                if (!buying[0]) holder[0].end();
            });
            for (TerminowBilling.Plan plan : plans) {
                TextView button = new TextView(mActivity);
                button.setText(planLabel(plan));
                button.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
                button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                button.setGravity(android.view.Gravity.CENTER);
                button.setBackgroundResource(R.drawable.terminow_action);
                button.setOnClickListener(v -> {
                    buying[0] = true;
                    dialog.dismiss();
                    holder[0].buy(mActivity, plan, TerminowBilling.accountId(session.uid), (none, buyError) -> {
                        if (buyError == null) return;
                        holder[0].end();
                        mActivity.showToast(buyError, true);
                    });
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
                params.topMargin = dp(8);
                form.addView(button, params);
            }
            dialog.show();
        });
    }

    /** Why Premium can not be bought here now, {@code null} if it can. */
    @Nullable
    String whyNoPurchase() {
        TerminowAccount.Session session = mStore.getAccount();
        if (!TerminowBilling.isSupported()) return mActivity.getString(R.string.terminow_premium_play_only);
        if (session == null) return mActivity.getString(R.string.terminow_premium_sign_in_first);
        if (!session.emailVerified) return mActivity.getString(R.string.terminow_account_premium_verify_first);
        if (!mAccount.hasFirebaseBackend()) return mActivity.getString(R.string.terminow_premium_not_set_up);
        if (isPremium(mStore)) return mActivity.getString(R.string.terminow_premium_already);
        if (TerminowPlayPurchases.isPurchaseInProgress(mStore)) {
            String summary = getPlaySummary();
            return summary != null ? summary : mActivity.getString(R.string.terminow_premium_status_verifying);
        }
        return null;
    }

    /** The plans of Premium with their prices and periods from Google Play. */
    void loadPlans(@NonNull TerminowBilling.Result<List<TerminowBilling.Plan>> result) {
        final TerminowBilling[] holder = new TerminowBilling[1];
        holder[0] = TerminowBilling.create(mActivity, new TerminowBilling.Listener() {
            @Override
            public void onPurchases(@NonNull List<TerminowBilling.Owned> purchases) {
            }

            @Override
            public void onCancelled() {
            }

            @Override
            public void onError(@NonNull String message) {
            }
        });
        if (holder[0] == null) {
            result.onResult(null, mActivity.getString(R.string.terminow_premium_unavailable));
            return;
        }
        holder[0].queryPlans((plans, error) -> {
            holder[0].end();
            mHandler.post(() -> result.onResult(plans, error));
        });
    }

    /**
     * Buy a plan with Google Play for the signed in account, like the plans dialog does: a
     * subscription that this account has on the device already is checked instead of bought again.
     * {@code onCancelled} is called when the user closes Google Play without buying.
     */
    void buyPlan(@NonNull TerminowBilling.Plan plan, @NonNull Runnable onCancelled) {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null || whyNoPurchase() != null) {
            onCancelled.run();
            return;
        }
        final TerminowBilling[] holder = new TerminowBilling[1];
        holder[0] = TerminowBilling.create(mActivity, new TerminowBilling.Listener() {
            @Override
            public void onPurchases(@NonNull List<TerminowBilling.Owned> purchases) {
                holder[0].end();
                onPurchased(session, purchases);
            }

            @Override
            public void onCancelled() {
                holder[0].end();
                onCancelled.run();
            }

            @Override
            public void onError(@NonNull String message) {
                holder[0].end();
                onCancelled.run();
                showMessage(R.string.terminow_premium_choose_title, message);
            }
        });
        if (holder[0] == null) {
            onCancelled.run();
            return;
        }
        holder[0].queryOwned((owned, ownedError) -> {
            String accountId = TerminowBilling.accountId(session.uid);
            boolean hasOwn = false;
            if (owned != null) for (TerminowBilling.Owned purchase : owned) hasOwn |= accountId.equals(purchase.accountId);
            if (hasOwn) {
                holder[0].end();
                mHandler.post(() -> {
                    onCancelled.run();
                    onPurchased(session, owned);
                });
                return;
            }
            mHandler.post(() -> holder[0].buy(mActivity, plan, accountId, (none, buyError) -> {
                if (buyError == null) return;
                holder[0].end();
                onCancelled.run();
                showMessage(R.string.terminow_premium_choose_title, buyError);
            }));
        });
    }

    /** The client area of accmove.com, where Premium from accmove is managed. */
    void openAccmove() {
        try {
            mActivity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://cp.accmove.com/clientarea.php?action=services")));
        } catch (android.content.ActivityNotFoundException e) {
            mActivity.showToast("https://cp.accmove.com/clientarea.php", true);
        }
    }

    /** The price and the billing period of a plan, both as Google Play gives them. */
    @NonNull
    String planLabel(@NonNull TerminowBilling.Plan plan) {
        java.util.regex.Matcher period = java.util.regex.Pattern.compile("P(\\d+)([DWMY])").matcher(plan.period);
        if (!period.matches()) return plan.price + " / " + plan.period;
        int count = Integer.parseInt(period.group(1));
        int res;
        switch (period.group(2)) {
            case "D": res = R.plurals.terminow_premium_period_days; break;
            case "W": res = R.plurals.terminow_premium_period_weeks; break;
            case "M": res = R.plurals.terminow_premium_period_months; break;
            default: res = R.plurals.terminow_premium_period_years;
        }
        return mActivity.getString(R.string.terminow_premium_plan_price, plan.price,
            mActivity.getResources().getQuantityString(res, count, count));
    }

    /** Google Play finished a purchase: the server verifies it and decides Premium. */
    private void onPurchased(@NonNull TerminowAccount.Session session, @NonNull List<TerminowBilling.Owned> purchases) {
        mStore.setPlayPurchaseStatus(TerminowPlayPurchases.Status.VERIFYING.name(), null, false);
        mOnChanged.run();
        AlertDialog progress = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(R.string.terminow_premium_status_verifying)
            .setCancelable(false)
            .show();
        mPurchases.verifyAsync(session, purchases, true, error -> {
            progress.dismiss();
            mOnChanged.run();
            TerminowPlayPurchases.Status status = TerminowPlayPurchases.getStatus(mStore);
            if (status == TerminowPlayPurchases.Status.ACTIVE && isPremium(mStore))
                mActivity.showToast(mActivity.getString(R.string.terminow_premium_thanks), true);
            else
                showMessage(R.string.terminow_premium_choose_title, error != null && getPlaySummary() == null ? error :
                    String.valueOf(getPlaySummary()));
        });
    }

    void openManage() {
        try {
            mActivity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(TerminowBilling.manageUrl(mActivity.getPackageName()))));
        } catch (android.content.ActivityNotFoundException e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_premium_unavailable), true);
        }
    }

    // -----------------------------------------------------------------------------------------

    void confirmSignOut() {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(R.string.terminow_account_sign_out_confirm)
            .setPositiveButton(R.string.terminow_account_sign_out, (d, w) -> signOutHere())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    void confirmDelete(@NonNull TerminowAccount.Session session) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_account_delete)
            .setMessage(mActivity.getString(R.string.terminow_account_delete_confirm, session.email))
            .setPositiveButton(R.string.terminow_delete, (d, w) -> run(() -> mAccount.deleteAccount(session), () -> {
                mStore.setAccount(null);
                mOnChanged.run();
                mActivity.showToast(mActivity.getString(R.string.terminow_account_deleted), true);
            }))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // -----------------------------------------------------------------------------------------

    private interface Call {
        void run() throws TerminowAccount.AccountException;
    }

    /** Run a call in the background without a dialog, then tell its error, {@code null} on success, on the main thread. */
    private void runThen(@NonNull Call call, @NonNull java.util.function.Consumer<String> onDone) {
        new Thread(() -> {
            String error = null;
            try {
                call.run();
            } catch (TerminowAccount.AccountException e) {
                error = "NEED_CONFIRMATION".equals(e.code) ? mActivity.getString(R.string.terminow_account_google_has_password) : e.getMessage();
            }
            String finalError = error;
            mHandler.post(() -> onDone.accept(finalError));
        }).start();
    }

    /** Run a call in the background with a progress dialog, then {@code onDone} on success. */
    private void run(@NonNull Call call, @NonNull Runnable onDone) {
        AlertDialog progress = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(R.string.terminow_account_working)
            .setCancelable(false)
            .show();
        new Thread(() -> {
            String error = null;
            try {
                call.run();
            } catch (TerminowAccount.AccountException e) {
                error = e.getMessage();
            }
            String finalError = error;
            mHandler.post(() -> {
                progress.dismiss();
                if (finalError != null) mActivity.showToast(finalError, true);
                else onDone.run();
            });
        }).start();
    }

    private void showMessage(int titleRes, @NonNull String message) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    @NonNull
    private LinearLayout form() {
        LinearLayout form = new LinearLayout(mActivity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), dp(4));
        return form;
    }

    @NonNull
    private EditText field(@NonNull LinearLayout form, int hintRes, int inputType) {
        EditText field = new EditText(mActivity);
        field.setHint(hintRes);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
        field.setHintTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        form.addView(field);
        return field;
    }

    private int dp(float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, mActivity.getResources().getDisplayMetrics()));
    }

}
