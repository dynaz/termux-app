package com.termux.app.terminow;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.List;

/**
 * The account security of Account & Premium, like the one of Termius: 2FA with an
 * authenticator app, changing the password, and the devices that are signed in, with signing
 * them out. Firebase checks all of it; nothing here decides by itself.
 */
public class TerminowAccountSecurity {

    private final TermuxActivity mActivity;
    private final TerminowAccountUi mAccountUi;
    private final TerminowAccount mAccount;
    private final TerminowStore mStore;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** Told when the details of the account changed, to draw them again. */
    private final Runnable mOnChanged;

    @Nullable private TerminowAccount.Details mDetails;
    @Nullable private String mDetailsError;
    private boolean mLoading;
    /** Whether the details were loaded once, so that a failure is not tried again at each draw. */
    private boolean mTried;

    public TerminowAccountSecurity(@NonNull TermuxActivity activity, @NonNull TerminowAccountUi accountUi, @NonNull Runnable onChanged) {
        mActivity = activity;
        mAccountUi = accountUi;
        mAccount = accountUi.getAccount();
        mStore = accountUi.getStore();
        mOnChanged = onChanged;
    }

    /** How the account signs in and its 2FA, {@code null} until loaded. */
    @Nullable
    public TerminowAccount.Details getDetails() {
        return mDetails;
    }

    @Nullable
    public String getDetailsError() {
        return mDetailsError;
    }

    public boolean isLoading() {
        return mLoading;
    }

    /** Whether 2FA is on, {@code null} while not known. */
    @Nullable
    public Boolean isMfaOn() {
        return mDetails == null ? null : !mDetails.factors.isEmpty();
    }

    /** Load the details of the account from Firebase, once or again after a change. */
    public void load(boolean again) {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null || mLoading || (mTried && !again)) return;
        mTried = true;
        mLoading = true;
        mDetailsError = null;
        new Thread(() -> {
            TerminowAccount.Details details = null;
            String error = null;
            try {
                details = mAccount.getDetails(session);
            } catch (TerminowAccount.AccountException e) {
                error = e.getMessage();
            }
            TerminowAccount.Details finalDetails = details;
            String finalError = error;
            mHandler.post(() -> {
                mLoading = false;
                mDetails = finalDetails;
                mDetailsError = finalError;
                if (mStore.getAccount() != null) mStore.setAccount(session);
                mOnChanged.run();
            });
        }, "TerminowAccountDetails").start();
    }

    // ---------------------------------------------------------------------------------------
    // 2FA

    /** Turn 2FA on with an authenticator app, or show it with the option to remove it. */
    public void showMfa() {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null || mDetails == null) return;
        if (mDetails.factors.isEmpty()) {
            startEnrollment(session);
            return;
        }
        TerminowAccount.Factor factor = mDetails.factors.get(0);
        String name = factor.name.isEmpty() ? mActivity.getString(R.string.terminow_mfa_app) : factor.name;
        String message = mActivity.getString(R.string.terminow_mfa_on_message, name);
        if (factor.enrolledAt > 0)
            message += "\n" + mActivity.getString(R.string.terminow_mfa_added, DateUtils.formatDateTime(mActivity, factor.enrolledAt,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR));
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mfa_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.terminow_mfa_turn_off, (d, w) -> confirmRemove(session, factor))
            .show();
    }

    private void confirmRemove(@NonNull TerminowAccount.Session session, @NonNull TerminowAccount.Factor factor) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mfa_turn_off)
            .setMessage(R.string.terminow_mfa_turn_off_message)
            .setPositiveButton(R.string.terminow_mfa_turn_off, (d, w) -> work(() -> mAccount.removeFactor(session, factor.id), error -> {
                if (error != null) {
                    showError(R.string.terminow_mfa_title, error);
                    return;
                }
                mStore.setAccount(session);
                mActivity.showToast(mActivity.getString(R.string.terminow_mfa_turned_off), true);
                load(true);
            }))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void startEnrollment(@NonNull TerminowAccount.Session session) {
        final TerminowAccount.TotpSetup[] setup = new TerminowAccount.TotpSetup[1];
        work(() -> setup[0] = mAccount.startTotpEnrollment(session), error -> {
            if (error != null) {
                showError(R.string.terminow_mfa_title, error);
                return;
            }
            showEnrollment(session, setup[0]);
        });
    }

    /** The secret to add to an authenticator app, then a code of it to confirm. */
    private void showEnrollment(@NonNull TerminowAccount.Session session, @NonNull TerminowAccount.TotpSetup setup) {
        LinearLayout form = form();
        form.addView(note(mActivity.getString(R.string.terminow_mfa_step_add)));
        TextView secret = new TextView(mActivity);
        secret.setText(groups(setup.secret));
        secret.setTypeface(Typeface.MONOSPACE);
        secret.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        secret.setTextColor(color(R.color.terminow_text));
        secret.setTextIsSelectable(true);
        secret.setPadding(0, dp(8), 0, dp(8));
        form.addView(secret);

        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView open = link(mActivity.getString(R.string.terminow_mfa_open_app));
        open.setOnClickListener(v -> {
            try {
                mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(setup.uri(session.email))));
            } catch (Exception e) {
                mActivity.showToast(mActivity.getString(R.string.terminow_mfa_no_app), true);
            }
        });
        buttons.addView(open);
        TextView copy = link(mActivity.getString(R.string.terminow_mfa_copy_secret));
        copy.setPadding(dp(20), 0, 0, 0);
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            ClipData clip = ClipData.newPlainText("TermiNow 2FA", setup.secret);
            // Not shown in the preview of the clipboard, like a password
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                clip.getDescription().setExtras(extras);
            }
            clipboard.setPrimaryClip(clip);
            mActivity.showToast(mActivity.getString(R.string.terminow_mfa_secret_copied), false);
        });
        buttons.addView(copy);
        form.addView(buttons);

        form.addView(note(mActivity.getString(R.string.terminow_mfa_step_code, setup.digits)));
        EditText code = new EditText(mActivity);
        code.setHint(R.string.terminow_mfa_code);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setSingleLine(true);
        code.setTextColor(color(R.color.terminow_text));
        code.setHintTextColor(color(R.color.terminow_text_secondary));
        form.addView(code);
        form.addView(note(mActivity.getString(R.string.terminow_mfa_keep_note)));

        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mfa_turn_on)
            .setView(scroll)
            .setPositiveButton(R.string.terminow_mfa_turn_on, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = code.getText().toString().trim();
            if (!value.matches("[0-9]{" + setup.digits + "}")) {
                code.setError(mActivity.getString(R.string.terminow_mfa_code_invalid));
                return;
            }
            work(() -> mAccount.finishTotpEnrollment(session, setup, value, mActivity.getString(R.string.terminow_mfa_app)), error -> {
                if (error != null) {
                    code.setError(error);
                    return;
                }
                dialog.dismiss();
                mStore.setAccount(session);
                mActivity.showToast(mActivity.getString(R.string.terminow_mfa_turned_on), true);
                load(true);
            });
        }));
        dialog.show();
    }

    /** "ABCD EFGH IJKL", easier to type into an authenticator app. */
    @NonNull
    static String groups(@NonNull String secret) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < secret.length(); i++) {
            if (i > 0 && i % 4 == 0) out.append(' ');
            out.append(secret.charAt(i));
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Password

    public void showChangePassword() {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null) return;
        LinearLayout form = form();
        EditText current = password(form, R.string.terminow_password_current);
        EditText fresh = password(form, R.string.terminow_password_new);
        EditText again = password(form, R.string.terminow_password_again);
        EditText code = new EditText(mActivity);
        code.setHint(R.string.terminow_mfa_code);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setSingleLine(true);
        code.setTextColor(color(R.color.terminow_text));
        code.setHintTextColor(color(R.color.terminow_text_secondary));
        // Only asked for when the account has 2FA
        code.setVisibility(Boolean.TRUE.equals(isMfaOn()) ? View.VISIBLE : View.GONE);
        form.addView(code);
        form.addView(note(mActivity.getString(R.string.terminow_password_note)));
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_password_change)
            .setView(form)
            .setPositiveButton(R.string.terminow_password_change_button, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String oldPassword = current.getText().toString();
            String newPassword = fresh.getText().toString();
            if (oldPassword.isEmpty()) {
                current.setError(mActivity.getString(R.string.terminow_account_enter_password));
                return;
            }
            if (newPassword.length() < 8) {
                fresh.setError(mActivity.getString(R.string.terminow_account_password_short));
                return;
            }
            if (!newPassword.equals(again.getText().toString())) {
                again.setError(mActivity.getString(R.string.terminow_password_mismatch));
                return;
            }
            String codeValue = code.getVisibility() == View.VISIBLE ? code.getText().toString().trim() : null;
            if (codeValue != null && !codeValue.matches("[0-9]{6,8}")) {
                code.setError(mActivity.getString(R.string.terminow_mfa_code_invalid));
                return;
            }
            final boolean[] needsCode = new boolean[1];
            work(() -> {
                try {
                    mAccount.changePassword(session, oldPassword, newPassword, codeValue);
                } catch (TerminowAccount.MfaRequiredException e) {
                    needsCode[0] = true;
                }
            }, error -> {
                if (error != null) {
                    showError(R.string.terminow_password_change, error);
                    return;
                }
                if (needsCode[0]) {
                    // The account has 2FA that was not known yet: ask its code too
                    code.setVisibility(View.VISIBLE);
                    code.requestFocus();
                    code.setError(mActivity.getString(R.string.terminow_mfa_code_message));
                    return;
                }
                dialog.dismiss();
                mStore.setAccount(session);
                mActivity.showToast(mActivity.getString(R.string.terminow_password_changed), true);
            });
        }));
        dialog.show();
    }

    // ---------------------------------------------------------------------------------------
    // Devices

    public void showDevices() {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null) return;
        LinearLayout list = form();
        ProgressBar spinner = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleSmall);
        list.addView(spinner, new LinearLayout.LayoutParams(dp(28), dp(28)));
        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(list);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_devices_title)
            .setView(scroll)
            .setPositiveButton(R.string.terminow_profiles_close, null)
            .setNegativeButton(R.string.terminow_devices_sign_out_all, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> confirmSignOutEverywhere(session, dialog)));
        dialog.show();
        loadDevices(session, list, dialog);
    }

    private void loadDevices(@NonNull TerminowAccount.Session session, @NonNull LinearLayout list, @NonNull AlertDialog dialog) {
        String deviceId = mStore.getAccountDeviceId();
        // This device first, so that it is in the list even when it registered just now
        mAccountUi.registerDevice(false);
        final List<TerminowAccount.Device>[] devices = new List[1];
        work(() -> devices[0] = mAccount.listDevices(session, deviceId), error -> {
            if (!dialog.isShowing()) return;
            list.removeAllViews();
            if (error != null) {
                list.addView(note(error));
                return;
            }
            boolean hasCurrent = false;
            for (TerminowAccount.Device device : devices[0]) hasCurrent |= device.current;
            if (!hasCurrent) list.addView(row(TerminowAccountUi.deviceName(), mActivity.getString(R.string.terminow_devices_this), null));
            for (TerminowAccount.Device device : devices[0]) {
                String when = device.current ? mActivity.getString(R.string.terminow_devices_this) :
                    mActivity.getString(R.string.terminow_devices_last_active, device.lastActive <= 0 ? "—" :
                        DateUtils.formatDateTime(mActivity, device.lastActive, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR));
                list.addView(row(device.name, when, device.current ? null : () -> confirmSignOut(session, device, list, dialog)));
            }
            list.addView(note(mActivity.getString(R.string.terminow_devices_note)));
        }, false);
    }

    private void confirmSignOut(@NonNull TerminowAccount.Session session, @NonNull TerminowAccount.Device device,
                                @NonNull LinearLayout list, @NonNull AlertDialog devices) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(mActivity.getString(R.string.terminow_devices_sign_out_confirm, device.name))
            .setPositiveButton(R.string.terminow_account_sign_out, (d, w) -> work(() -> mAccount.signOutDevice(session, device.id), error -> {
                if (error != null) {
                    showError(R.string.terminow_devices_title, error);
                    return;
                }
                mActivity.showToast(mActivity.getString(R.string.terminow_devices_signed_out, device.name), true);
                loadDevices(session, list, devices);
            }))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmSignOutEverywhere(@NonNull TerminowAccount.Session session, @NonNull AlertDialog devices) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_devices_sign_out_all)
            .setMessage(R.string.terminow_devices_sign_out_all_message)
            .setPositiveButton(R.string.terminow_devices_sign_out_all, (d, w) -> work(() -> mAccount.signOutEverywhere(session), error -> {
                if (error != null) {
                    showError(R.string.terminow_devices_title, error);
                    return;
                }
                devices.dismiss();
                // Firebase ended this session too: sign out here, keeping everything on the device
                mStore.setAccount(null);
                mAccountUi.notifyChanged();
                mActivity.showToast(mActivity.getString(R.string.terminow_devices_signed_out_all), true);
            }))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------

    private interface Call {
        void run() throws TerminowAccount.AccountException;
    }

    private void work(@NonNull Call call, @NonNull java.util.function.Consumer<String> onDone) {
        work(call, onDone, true);
    }

    /** Run a call in the background, with a progress dialog if {@code progress}, then its error or {@code null}. */
    private void work(@NonNull Call call, @NonNull java.util.function.Consumer<String> onDone, boolean progress) {
        AlertDialog dialog = progress ? new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(R.string.terminow_account_working)
            .setCancelable(false)
            .show() : null;
        new Thread(() -> {
            String error = null;
            try {
                call.run();
            } catch (TerminowAccount.AccountException e) {
                error = describe(e);
            }
            String finalError = error;
            mHandler.post(() -> {
                if (dialog != null) dialog.dismiss();
                onDone.accept(finalError);
            });
        }, "TerminowAccountSecurity").start();
    }

    /** What a failure means here, where some codes say more than the general message. */
    @NonNull
    private String describe(@NonNull TerminowAccount.AccountException e) {
        switch (e.code) {
            case "CREDENTIAL_TOO_OLD_LOGIN_AGAIN": return mActivity.getString(R.string.terminow_security_sign_in_again);
            case "INVALID_LOGIN_CREDENTIALS":
            case "INVALID_PASSWORD": return mActivity.getString(R.string.terminow_password_wrong);
            case "BACKEND_NOT_DEPLOYED":
            case "BACKEND_NOT_CONFIGURED": return mActivity.getString(R.string.terminow_devices_unavailable);
            default:
                // 2FA that the Firebase project does not have turned on yet
                if (e.code.startsWith("OPERATION_NOT_ALLOWED") || e.code.contains("MFA") && e.code.contains("NOT"))
                    return mActivity.getString(R.string.terminow_mfa_unavailable);
                return String.valueOf(e.getMessage());
        }
    }

    private void showError(int titleRes, @NonNull String message) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    @NonNull
    private View row(@NonNull String title, @NonNull String summary, @Nullable Runnable onSignOut) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(0, dp(6), 0, dp(6));
        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(mActivity);
        name.setText(title);
        name.setTextColor(color(R.color.terminow_text));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        texts.addView(name);
        TextView detail = new TextView(mActivity);
        detail.setText(summary);
        detail.setTextColor(color(R.color.terminow_text_secondary));
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        texts.addView(detail);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (onSignOut != null) {
            TextView signOut = link(mActivity.getString(R.string.terminow_account_sign_out));
            signOut.setOnClickListener(v -> onSignOut.run());
            row.addView(signOut);
        }
        return row;
    }

    @NonNull
    private TextView link(@NonNull String text) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextColor(color(R.color.terminow_panel_accent));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setMinHeight(dp(48));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    @NonNull
    private TextView note(@NonNull String text) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextColor(color(R.color.terminow_text_secondary));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(0, dp(8), 0, dp(4));
        return view;
    }

    @NonNull
    private EditText password(@NonNull LinearLayout form, int hintRes) {
        EditText field = new EditText(mActivity);
        field.setHint(hintRes);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true);
        field.setTextColor(color(R.color.terminow_text));
        field.setHintTextColor(color(R.color.terminow_text_secondary));
        form.addView(field);
        return field;
    }

    @NonNull
    private LinearLayout form() {
        LinearLayout form = new LinearLayout(mActivity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), dp(4));
        return form;
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }

}
