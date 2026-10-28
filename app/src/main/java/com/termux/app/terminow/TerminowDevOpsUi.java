package com.termux.app.terminow;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

/**
 * The DevOps MVP landing (a Premium feature): connect a provider, then run pipelines and deployments for
 * your hosts. This is the first slice of the MVP, so entitlement is woven in from the start — the entry
 * ({@link TerminowHome#showDevOps()}) and every action here re-check Premium through the shared
 * {@link TerminowPremiumGate}/{@link TerminowAccountUi} system, never a separate Premium state.
 *
 * <p>The provider connections, pipelines, logs and deployments are performed by the TermiNow DevOps
 * backend service (which verifies the signed-in user and entitlement server-side and holds the provider
 * OAuth secrets). That service does not exist yet, so the client performs no provider connection and
 * shows an honest "service not available" message instead of simulating one — it never embeds a provider
 * secret or bypasses the backend. When Premium expires the entry is locked and this screen is not
 * reachable; installed configuration and SSH hosts/credentials are left untouched.</p>
 */
public final class TerminowDevOpsUi {

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;

    public TerminowDevOpsUi(@NonNull TermuxActivity activity, @NonNull TerminowStore store) {
        mActivity = activity;
        mStore = store;
    }

    public void open() {
        // Defence in depth: never render DevOps for a user who is not Premium, even if reached directly.
        if (!TerminowAccountUi.isPremium(mStore)) {
            TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.DEVOPS, 0, this::open);
            return;
        }

        boolean phone = mActivity.getResources().getConfiguration().screenWidthDp < 600;
        Dialog dialog = new Dialog(mActivity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.terminow_bar_home));
        bg.setStroke(dp(1), color(R.color.terminow_quick_border));
        float r = dp(24);
        bg.setCornerRadii(phone ? new float[]{r, r, r, r, 0, 0, 0, 0} : new float[]{r, r, r, r, r, r, r, r});
        root.setBackground(bg);

        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(mActivity.getString(R.string.terminow_devops_title), 19, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", 18, R.color.terminow_text);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(mActivity.getString(android.R.string.cancel));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(head, wrap());

        root.addView(text(mActivity.getString(R.string.terminow_devops_subtitle), 13, R.color.terminow_text_secondary), wrap());

        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(8);
        root.addView(scroll, scrollLp);

        section(content, R.string.terminow_devops_providers);
        for (String provider : new String[]{"GitHub", "GitLab", "Bitbucket"})
            content.addView(providerRow(dialog, provider));

        section(content, R.string.terminow_devops_pipelines);
        content.addView(text(mActivity.getString(R.string.terminow_devops_coming), 13, R.color.terminow_text_secondary), wrap());
        section(content, R.string.terminow_devops_deployments);
        content.addView(text(mActivity.getString(R.string.terminow_devops_coming), 13, R.color.terminow_text_secondary), wrap());

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screen = mActivity.getResources().getDisplayMetrics().widthPixels;
            if (phone) {
                window.setGravity(Gravity.BOTTOM);
                window.setLayout(screen, ViewGroup.LayoutParams.WRAP_CONTENT);
            } else {
                window.setLayout(Math.min(screen - dp(32), dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
        dialog.show();
    }

    /** A "Connect <provider>" row. Re-checks Premium, then — with no backend yet — explains honestly. */
    @NonNull
    private View providerRow(@NonNull Dialog dialog, @NonNull String provider) {
        TextView row = text(mActivity.getString(R.string.terminow_devops_connect, provider), 15, R.color.terminow_text);
        row.setTypeface(Typeface.DEFAULT_BOLD);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(56));
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color(R.color.terminow_quick_card));
        shape.setCornerRadius(dp(12));
        shape.setStroke(Math.max(1, dp(1)), color(R.color.terminow_quick_border));
        row.setBackground(shape);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            // Re-check entitlement at the action, then refuse honestly: no backend, no client-side OAuth.
            if (!TerminowAccountUi.isPremium(mStore)) {
                dialog.dismiss();
                TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.DEVOPS, 0, this::open);
                return;
            }
            new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(mActivity.getString(R.string.terminow_devops_connect, provider))
                .setMessage(R.string.terminow_devops_backend_required)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        });
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private void section(@NonNull LinearLayout content, int label) {
        TextView view = text(mActivity.getString(label), 13, R.color.terminow_text_secondary);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(16), 0, dp(4));
        androidx.core.view.ViewCompat.setAccessibilityHeading(view, true);
        content.addView(view);
    }

    @NonNull
    private TextView text(@NonNull String value, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    @NonNull
    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }
}
