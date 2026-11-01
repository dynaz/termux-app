package com.termux.app.terminow;

import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

/**
 * Shows the Local AI Bridge approval prompt and keeps it honest about app lifecycle.
 *
 * <p>When a tool call reaches a host that needs the user's agreement, {@link TerminowAiBridge} parks
 * the request in {@link TerminowAiApprovals} and blocks; this controller is the registry's listener.
 * With the activity in the foreground it shows a prompt naming the client/session, the host and the
 * exact capability (and, for a command, the command itself in a scrollable, selectable area); the
 * user's choice resolves the parked call, which then resumes on its own — no "type Retry". When the
 * activity is backgrounded it posts a notification that brings the app forward to the prompt instead
 * of launching an activity over whatever is in front. If the request is cancelled or times out while
 * the prompt is up, the prompt closes.</p>
 *
 * <p>It never guesses session identity: "Allow for this session" is offered only when the request is
 * bound to an identified session, and is disabled with an explanation otherwise. A decision grants
 * only the requested capability; the bridge enforces that.</p>
 */
final class TerminowAiApprovalController implements TerminowAiApprovals.Listener {

    private static final String CHANNEL_ID = "terminow_ai_approvals";
    private static final int NOTIFICATION_ID = 7311;

    private final TermuxActivity mActivity;
    private final TerminowAiBridgeManager mManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mResumed;
    @Nullable private Dialog mDialog;
    @Nullable private String mShowingId;

    TerminowAiApprovalController(@NonNull TermuxActivity activity, @NonNull TerminowAiBridgeManager manager) {
        mActivity = activity;
        mManager = manager;
        mManager.approvals().setListener(this);
    }

    /** The registry changed on some connection thread: refresh the prompt on the main thread. */
    @Override
    public void onApprovalsChanged() {
        mHandler.post(this::refresh);
    }

    void onResume() {
        mResumed = true;
        refresh();
    }

    void onPause() {
        mResumed = false;
    }

    /** Show, swap or dismiss the prompt to match the current pending set and lifecycle state. */
    private void refresh() {
        java.util.List<TerminowAiApprovals.Request> pending = mManager.approvals().list();
        if (pending.isEmpty()) {
            dismiss();
            cancelNotification();
            return;
        }
        if (!mResumed) {
            // Backgrounded: do not throw an activity over another app; let a notification bring us up.
            dismiss();
            postNotification(pending.get(pending.size() - 1));
            return;
        }
        cancelNotification();
        TerminowAiApprovals.Request request = pending.get(pending.size() - 1);
        // The one on screen is still valid: leave it.
        if (mDialog != null && mDialog.isShowing() && request.id.equals(mShowingId)) return;
        showPrompt(request);
    }

    private void dismiss() {
        if (mDialog != null) {
            Dialog dialog = mDialog;
            mDialog = null;
            mShowingId = null;
            if (dialog.isShowing()) dialog.dismiss();
        }
    }

    // ---- the prompt ----

    private void showPrompt(@NonNull TerminowAiApprovals.Request request) {
        dismiss();
        Dialog dialog = new Dialog(mActivity, R.style.TerminowPanelDialog);
        mDialog = dialog;
        mShowingId = request.id;

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(color(R.color.terminow_panel_scrim));

        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(R.color.terminow_panel));
        background.setCornerRadius(dp(20));
        card.setBackground(background);
        card.setPadding(dp(20), dp(18), dp(20), dp(14));

        TextView title = new TextView(mActivity);
        title.setText(mActivity.getString(R.string.terminow_ai_approval_title));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(color(R.color.terminow_text));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        ViewCompat.setAccessibilityHeading(title, true);
        card.addView(title);

        TerminowAiApprovals.Spec spec = request.spec;
        boolean run = TerminowStore.AiAccess.CAP_RUN_COMMAND.equals(spec.capability);
        String capText = mActivity.getString(run
            ? R.string.terminow_ai_approval_cap_run : R.string.terminow_ai_approval_cap_test);

        detail(card, mActivity.getString(R.string.terminow_ai_approval_client), spec.clientName);
        detail(card, mActivity.getString(R.string.terminow_ai_approval_session), spec.sessionLabel);
        detail(card, mActivity.getString(R.string.terminow_ai_approval_host),
            spec.hostAlias + "  (" + spec.hostAddress + ":" + spec.hostPort + ")");
        detail(card, mActivity.getString(R.string.terminow_ai_approval_capability), capText);

        if (run && spec.command != null && !spec.command.isEmpty()) {
            TextView commandLabel = new TextView(mActivity);
            commandLabel.setText(mActivity.getString(R.string.terminow_ai_approval_command_label));
            commandLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            commandLabel.setTextColor(color(R.color.terminow_panel_accent));
            commandLabel.setTypeface(Typeface.DEFAULT_BOLD);
            commandLabel.setAllCaps(true);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(10);
            commandLabel.setLayoutParams(labelParams);
            card.addView(commandLabel);

            ScrollView commandScroll = new ScrollView(mActivity);
            GradientDrawable box = new GradientDrawable();
            box.setColor(color(R.color.terminow_panel_scrim));
            box.setCornerRadius(dp(8));
            commandScroll.setBackground(box);
            commandScroll.setPadding(dp(10), dp(8), dp(10), dp(8));
            TextView command = new TextView(mActivity);
            command.setText(spec.command);
            command.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            command.setTextColor(color(R.color.terminow_text));
            command.setTypeface(Typeface.MONOSPACE);
            command.setTextIsSelectable(true);
            commandScroll.addView(command);
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120));
            scrollParams.topMargin = dp(4);
            card.addView(commandScroll, scrollParams);
        }

        // Buttons: Deny, Allow once, Allow for this session (disabled if the session can't be bound).
        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = dp(14);
        buttons.setLayoutParams(buttonsParams);

        Button allowOnce = actionButton(mActivity.getString(R.string.terminow_ai_approval_allow_once), true);
        allowOnce.setOnClickListener(v -> decide(request.id, TerminowAiApprovals.Decision.ALLOW_ONCE));
        buttons.addView(allowOnce);

        Button allowSession = actionButton(mActivity.getString(R.string.terminow_ai_approval_allow_session), false);
        if (spec.sessionBindable) {
            allowSession.setOnClickListener(v -> decide(request.id, TerminowAiApprovals.Decision.ALLOW_SESSION));
        } else {
            allowSession.setEnabled(false);
            allowSession.setAlpha(0.5f);
        }
        buttons.addView(allowSession);

        Button deny = actionButton(mActivity.getString(R.string.terminow_ai_approval_deny), false);
        deny.setOnClickListener(v -> decide(request.id, TerminowAiApprovals.Decision.DENY));
        buttons.addView(deny);

        if (!spec.sessionBindable) {
            TextView note = new TextView(mActivity);
            note.setText(mActivity.getString(R.string.terminow_ai_approval_session_unavailable));
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            note.setTextColor(color(R.color.terminow_text_secondary));
            LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            noteParams.topMargin = dp(6);
            note.setLayoutParams(noteParams);
            buttons.addView(note);
        }
        card.addView(buttons);

        ScrollView outer = new ScrollView(mActivity);
        outer.addView(card);
        // card is the ScrollView's child, so its params must be FrameLayout.LayoutParams, not
        // LinearLayout.LayoutParams — ScrollView (a FrameLayout) casts its child's params and would
        // otherwise crash with ClassCastException.
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(Math.min(widthPx(), dp(480)),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER_HORIZONTAL;
        cardParams.setMargins(dp(16), dp(16), dp(16), dp(16));
        card.setLayoutParams(cardParams);
        root.addView(outer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        // A decision must be deliberate: dismissing by back or outside tap is neither allow nor deny,
        // so the request stays pending (and still times out), rather than being silently resolved.
        dialog.setCanceledOnTouchOutside(false);
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnDismissListener(d -> {
            if (mDialog == dialog) {
                mDialog = null;
                mShowingId = null;
            }
        });
        dialog.show();
    }

    private void decide(@NonNull String id, @NonNull TerminowAiApprovals.Decision decision) {
        mManager.decideApproval(id, decision);
        // The registry listener will refresh (dismiss or show the next). Dismiss now for responsiveness.
        dismiss();
        refresh();
    }

    private void detail(@NonNull LinearLayout card, @NonNull String label, @NonNull String value) {
        TextView view = new TextView(mActivity);
        view.setText(label + ":  " + value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(color(R.color.terminow_text));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        view.setLayoutParams(params);
        card.addView(view);
    }

    @NonNull
    private Button actionButton(@NonNull String text, boolean primary) {
        Button button = new Button(mActivity);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(color(primary ? R.color.terminow_text : R.color.terminow_text));
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(primary ? R.color.terminow_panel_accent : R.color.terminow_panel_scrim));
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    // ---- background notification ----

    private void postNotification(@NonNull TerminowAiApprovals.Request request) {
        String title = mActivity.getString(R.string.terminow_ai_approval_notification_title);
        String text = mActivity.getString(R.string.terminow_ai_approval_notification_text,
            request.spec.clientName, request.spec.hostAlias);
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                com.termux.shared.notification.NotificationUtils.setupNotificationChannel(mActivity, CHANNEL_ID,
                    mActivity.getString(R.string.terminow_ai_approval_notification_channel),
                    android.app.NotificationManager.IMPORTANCE_HIGH);
            android.content.Intent intent = new android.content.Intent(mActivity, mActivity.getClass())
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent pending = android.app.PendingIntent.getActivity(mActivity, NOTIFICATION_ID, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.Notification.Builder builder = com.termux.shared.notification.NotificationUtils.geNotificationBuilder(mActivity,
                CHANNEL_ID, android.app.Notification.PRIORITY_HIGH, title, text, text, pending, null,
                com.termux.shared.notification.NotificationUtils.NOTIFICATION_MODE_SILENT);
            android.app.NotificationManager manager = com.termux.shared.notification.NotificationUtils.getNotificationManager(mActivity);
            if (builder == null || manager == null) return;
            builder.setSmallIcon(R.drawable.ic_service_notification);
            builder.setAutoCancel(true);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (RuntimeException e) {
            // Without the notification the request still waits and still times out; the pending list holds it.
        }
    }

    private void cancelNotification() {
        try {
            android.app.NotificationManager manager = com.termux.shared.notification.NotificationUtils.getNotificationManager(mActivity);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private int widthPx() {
        return mActivity.getResources().getDisplayMetrics().widthPixels;
    }
}
