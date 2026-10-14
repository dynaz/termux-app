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
    @Nullable private String mPremiumError;

    public TerminowAccountUi(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull Runnable onChanged) {
        mActivity = activity;
        mStore = store;
        mOnChanged = onChanged;
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

    /** Whether Premium features may be used now, see {@link TerminowEntitlement}. */
    public static boolean isPremium(@NonNull TerminowStore store) {
        return TerminowEntitlement.get(store) == TerminowEntitlement.State.ACTIVE;
    }

    /**
     * Check the Google Play purchases of this device when the app comes back, at most every
     * few minutes, so that a purchase whose check failed or a renewal is picked up.
     */
    public void recoverPurchases(@Nullable Runnable onDone) {
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
    private String getPremiumSummary(@NonNull TerminowAccount.Session session) {
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
    private String getPlaySummary() {
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
        if (!BuildConfig.TERMINOW_GOOGLE_WEB_CLIENT_ID.isEmpty()) {
            TextView google = new TextView(mActivity);
            google.setText(R.string.terminow_account_google);
            google.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
            google.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            google.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            google.setGravity(android.view.Gravity.CENTER);
            google.setBackgroundResource(R.drawable.terminow_action);
            google.setOnClickListener(v -> signInWithGoogle(dialogHolder[0]));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            params.topMargin = dp(8);
            params.bottomMargin = dp(8);
            form.addView(google, params);
            TextView or = new TextView(mActivity);
            or.setText(R.string.terminow_account_or_email);
            or.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
            or.setGravity(android.view.Gravity.CENTER);
            form.addView(or);
        }
        EditText emailView = field(form, R.string.terminow_account_email, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText passwordView = field(form, R.string.terminow_account_password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView note = new TextView(mActivity);
        note.setText(R.string.terminow_account_sign_in_note);
        note.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(0, dp(12), 0, 0);
        form.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_account_sign_in)
            .setView(form)
            .setPositiveButton(R.string.terminow_account_sign_in_button, null)
            .setNeutralButton(R.string.terminow_account_create, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialogHolder[0] = dialog;
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit(dialog, emailView, passwordView, false));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> submit(dialog, emailView, passwordView, true));
        });
        dialog.show();

        // A forgotten password is reset by email
        TextView forgot = new TextView(mActivity);
        forgot.setText(R.string.terminow_account_forgot);
        forgot.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_panel_accent));
        forgot.setPadding(0, dp(10), 0, 0);
        forgot.setOnClickListener(v -> {
            String email = emailView.getText().toString().trim();
            if (email.isEmpty()) {
                emailView.setError(mActivity.getString(R.string.terminow_account_enter_email));
                return;
            }
            run(() -> mAccount.sendPasswordReset(email), () ->
                mActivity.showToast(mActivity.getString(R.string.terminow_account_reset_sent, email), true));
        });
        form.addView(forgot);
    }

    /** Show the Google account picker of Android, then sign in to Firebase with its ID token. */
    private void signInWithGoogle(@Nullable AlertDialog dialog) {
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
                        mActivity.showToast(mActivity.getString(R.string.terminow_account_google_failed, "no Google account"), true);
                        return;
                    }
                    String token = idToken;
                    final TerminowAccount.Session[] result = new TerminowAccount.Session[1];
                    run(() -> result[0] = mAccount.signInWithGoogle(token), () -> {
                        mStore.setAccount(result[0]);
                        mPremiumError = null;
                        if (dialog != null) dialog.dismiss();
                        mOnChanged.run();
                    });
                }

                @Override
                public void onError(GetCredentialException e) {
                    // Closing the picker is not an error
                    if (e instanceof GetCredentialCancellationException) return;
                    mActivity.showToast(mActivity.getString(R.string.terminow_account_google_failed, String.valueOf(e.getMessage())), true);
                }
            });
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
        run(() -> result[0] = create ? mAccount.signUp(email, password) : mAccount.signIn(email, password), () -> {
            mStore.setAccount(result[0]);
            mPremiumError = null;
            dialog.dismiss();
            mOnChanged.run();
            if (!result[0].emailVerified)
                showMessage(R.string.terminow_account_verify_title, mActivity.getString(R.string.terminow_account_verify_sent, result[0].email));
        });
    }

    private void showVerify(@NonNull TerminowAccount.Session session) {
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
    private void restorePurchases() {
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
    private void showLinkAccount(@NonNull TerminowAccount.Session session) {
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

    /** The price and the billing period of a plan, both as Google Play gives them. */
    @NonNull
    private String planLabel(@NonNull TerminowBilling.Plan plan) {
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

    private void openManage() {
        try {
            mActivity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(TerminowBilling.manageUrl(mActivity.getPackageName()))));
        } catch (android.content.ActivityNotFoundException e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_premium_unavailable), true);
        }
    }

    // -----------------------------------------------------------------------------------------

    private void confirmSignOut() {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(R.string.terminow_account_sign_out_confirm)
            .setPositiveButton(R.string.terminow_account_sign_out, (d, w) -> {
                mStore.setAccount(null);
                mPremiumError = null;
                mOnChanged.run();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmDelete(@NonNull TerminowAccount.Session session) {
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
