package com.termux.app.terminow;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Account & Premium: who is signed in, the Premium that the server verified and where it comes
 * from, the plans of Google Play with their prices, and buying, restoring, refreshing and managing
 * them. It only shows and starts what {@link TerminowAccountUi} does; signing in never gives
 * Premium, and signing out keeps everything on the device.
 */
public class TerminowAccountScreen {

    /** The photo of the account, loaded once per process. */
    @Nullable private static Bitmap sPhoto;
    @Nullable private static String sPhotoUrl;

    private final TermuxActivity mActivity;
    private final TerminowAccountUi mAccountUi;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    @Nullable private Dialog mDialog;
    @Nullable private LinearLayout mContent;
    /** The plans of Google Play, {@code null} until loaded. */
    @Nullable private List<TerminowBilling.Plan> mPlans;
    @Nullable private String mPlansError;
    private boolean mPlansLoading;
    private boolean mBuying;
    private final Runnable mOnChanged = () -> {
        render();
        // A sign in may make plans available to buy
        loadPlansIfUseful();
    };

    public TerminowAccountScreen(@NonNull TermuxActivity activity, @NonNull TerminowAccountUi accountUi) {
        mActivity = activity;
        mAccountUi = accountUi;
    }

    public void show() {
        if (mDialog != null && mDialog.isShowing()) return;
        Dialog dialog = new Dialog(mActivity, R.style.TerminowAlertDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(24));
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(R.color.terminow_panel));
        background.setCornerRadius(dp(20));
        scroll.setBackground(background);
        scroll.addView(content);
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // The whole height of a phone, and at most 720dp wide on a tablet or an open foldable
            int width = Math.min(mActivity.getResources().getDisplayMetrics().widthPixels - dp(16), dp(720));
            window.setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        mDialog = dialog;
        mContent = content;
        mAccountUi.addChangeListener(mOnChanged);
        dialog.setOnDismissListener(d -> {
            mAccountUi.removeChangeListener(mOnChanged);
            mDialog = null;
            mContent = null;
        });
        render();
        dialog.show();
        loadPlansIfUseful();
    }

    // ---------------------------------------------------------------------------------------

    private void render() {
        LinearLayout content = mContent;
        if (content == null) return;
        content.removeAllViews();
        TerminowStore store = mAccountUi.getStore();
        TerminowAccount.Session session = store.getAccount();

        LinearLayout header = new LinearLayout(mActivity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(mActivity.getString(R.string.terminow_account_screen_title), 20, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = text("✕", 20, R.color.terminow_text_secondary);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(mActivity.getString(R.string.terminow_ubuntu_setup_close));
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(header);

        if (!mAccountUi.isConfigured()) {
            content.addView(card(text(mActivity.getString(R.string.terminow_account_not_configured), 14, R.color.terminow_text_secondary)));
            return;
        }
        if (session == null) {
            renderSignedOut(content);
        } else {
            renderAccount(content, session);
        }
        renderPlan(content, store, session);
        if (session != null) {
            renderPlans(content);
            renderActions(content, store, session);
        }
    }

    private void renderSignedOut(@NonNull LinearLayout content) {
        LinearLayout card = card(null);
        TextView why = text(mActivity.getString(R.string.terminow_account_screen_signed_out), 14, R.color.terminow_text_secondary);
        card.addView(why);
        if (TerminowAccountUi.hasGoogleSignIn()) {
            TerminowGoogleButton google = new TerminowGoogleButton(mActivity);
            google.setOnClickListener(v -> mAccountUi.signInWithGoogle(null, google));
            card.addView(google, TerminowGoogleButton.fullWidth(card, 14, 0));
        }
        TextView email = button(mActivity.getString(R.string.terminow_account_screen_email), false);
        email.setOnClickListener(v -> mAccountUi.showSignIn());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.topMargin = dp(10);
        card.addView(email, params);
        content.addView(card);
    }

    private void renderAccount(@NonNull LinearLayout content, @NonNull TerminowAccount.Session session) {
        LinearLayout card = card(null);
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(avatar(session), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(8), 0);
        if (!session.displayName.isEmpty()) {
            TextView name = text(session.displayName, 16, R.color.terminow_text);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            texts.addView(name);
        }
        texts.addView(text(session.email, 14, session.displayName.isEmpty() ? R.color.terminow_text : R.color.terminow_text_secondary));
        TextView verified = text(mActivity.getString(session.emailVerified ? R.string.terminow_account_verified : R.string.terminow_account_not_verified),
            12, session.emailVerified ? R.color.terminow_text_secondary : R.color.terminow_mcp_warn);
        if (!session.emailVerified) verified.setOnClickListener(v -> mAccountUi.showVerify(session));
        texts.addView(verified);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView signOut = button(mActivity.getString(R.string.terminow_account_sign_out), false);
        signOut.setOnClickListener(v -> mAccountUi.confirmSignOut());
        row.addView(signOut, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        card.addView(row);
        content.addView(card);
    }

    /** The current plan: Free or Premium, where it comes from, and what the server says about it. */
    private void renderPlan(@NonNull LinearLayout content, @NonNull TerminowStore store, @Nullable TerminowAccount.Session session) {
        LinearLayout card = card(null);
        boolean premium = TerminowAccountUi.isPremium(store);
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView crown = new ImageView(mActivity);
        crown.setImageResource(R.drawable.ic_terminow_crown);
        crown.setColorFilter(premium ? 0xFFFFC107 : color(R.color.terminow_text_secondary));
        row.addView(crown, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView plan = text(mActivity.getString(premium ? R.string.terminow_account_plan_premium : R.string.terminow_account_plan_free), 17, R.color.terminow_text);
        plan.setTypeface(Typeface.DEFAULT_BOLD);
        plan.setPadding(dp(12), 0, 0, 0);
        row.addView(plan, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (mAccountUi.isCheckingPremium()) {
            ProgressBar spinner = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleSmall);
            row.addView(spinner, new LinearLayout.LayoutParams(dp(22), dp(22)));
        }
        card.addView(row);
        String source = sourceLabel(store);
        if (source != null) card.addView(detail(source));
        if (session != null) {
            // What the server and Google Play confirmed, with their dates only if they gave them
            card.addView(detail(mAccountUi.getPremiumSummary(session)));
        } else {
            card.addView(detail(mActivity.getString(R.string.terminow_account_plan_signed_out)));
        }
        content.addView(card);
    }

    /** Where Premium comes from. */
    enum Source { NONE, PLAY, ACCMOVE, BOTH }

    /**
     * Where the Premium of the store comes from, as the server verified it: Google Play
     * (the play entitlement of the Cloud Functions) and/or accmove.com (WHMCS).
     */
    @NonNull
    static Source sourceOf(@NonNull TerminowStore store) {
        if (!TerminowAccountUi.isPremium(store)) return Source.NONE;
        JSONObject play = store.getPlayEntitlement();
        JSONObject googlePlay = play != null ? play.optJSONObject("google_play") : null;
        boolean fromPlay = googlePlay != null && googlePlay.optBoolean("premium");
        JSONObject premium = store.getPremium();
        boolean fromAccmove = premium != null && premium.optBoolean("premium") && !"play".equals(premium.optString("source"));
        return fromPlay && fromAccmove ? Source.BOTH : fromPlay ? Source.PLAY : fromAccmove ? Source.ACCMOVE : Source.NONE;
    }

    @Nullable
    private String sourceLabel(@NonNull TerminowStore store) {
        switch (sourceOf(store)) {
            case BOTH: return mActivity.getString(R.string.terminow_account_source_both);
            case PLAY: return mActivity.getString(R.string.terminow_account_source_play);
            case ACCMOVE: return mActivity.getString(R.string.terminow_account_source_accmove);
            default: return null;
        }
    }

    private void loadPlansIfUseful() {
        if (mPlans != null || mPlansLoading || mAccountUi.whyNoPurchase() != null) return;
        mPlansLoading = true;
        mPlansError = null;
        render();
        mAccountUi.loadPlans((plans, error) -> {
            mPlansLoading = false;
            mPlans = plans;
            mPlansError = plans == null || plans.isEmpty() ? (error != null ? error : mActivity.getString(R.string.terminow_premium_unavailable)) : null;
            render();
        });
    }

    /** The plans with their prices and periods from Google Play, or why there are none to buy. */
    private void renderPlans(@NonNull LinearLayout content) {
        String why = mAccountUi.whyNoPurchase();
        TerminowStore store = mAccountUi.getStore();
        if (why != null) {
            // Premium users see nothing to buy; others see why, like a purchase that waits
            if (!TerminowAccountUi.isPremium(store)) content.addView(card(detail(why)));
            return;
        }
        LinearLayout card = card(null);
        TextView title = text(mActivity.getString(R.string.terminow_premium_choose_title), 15, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);
        card.addView(detail(mActivity.getString(R.string.terminow_premium_choose_message)));
        if (mPlansLoading) {
            ProgressBar spinner = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleSmall);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(28), dp(28));
            params.topMargin = dp(10);
            card.addView(spinner, params);
        } else if (mPlans == null || mPlans.isEmpty()) {
            card.addView(detail(String.valueOf(mPlansError)));
            TextView retry = button(mActivity.getString(R.string.terminow_ubuntu_setup_retry), false);
            retry.setOnClickListener(v -> {
                mPlans = null;
                loadPlansIfUseful();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
            params.topMargin = dp(8);
            card.addView(retry, params);
        } else {
            for (TerminowBilling.Plan plan : mPlans) {
                TextView buy = button(mAccountUi.planLabel(plan), true);
                buy.setEnabled(!mBuying);
                buy.setAlpha(mBuying ? 0.5f : 1f);
                buy.setOnClickListener(v -> {
                    if (mBuying) return;
                    mBuying = true;
                    render();
                    mAccountUi.buyPlan(plan, () -> {
                        mBuying = false;
                        render();
                    });
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
                params.topMargin = dp(10);
                card.addView(buy, params);
            }
            card.addView(detail(mActivity.getString(R.string.terminow_account_plans_note)));
        }
        content.addView(card);
    }

    private void renderActions(@NonNull LinearLayout content, @NonNull TerminowStore store, @NonNull TerminowAccount.Session session) {
        LinearLayout card = card(null);
        if (TerminowBilling.isSupported() && session.emailVerified)
            card.addView(action(R.string.terminow_premium_restore, R.string.terminow_premium_restore_summary, mAccountUi::restorePurchases));
        card.addView(action(R.string.terminow_account_refresh, R.string.terminow_account_refresh_summary, mAccountUi::checkPremiumNow));
        JSONObject play = store.getPlayEntitlement();
        if (play != null && play.optJSONObject("google_play") != null)
            card.addView(action(R.string.terminow_premium_manage, R.string.terminow_premium_manage_summary, mAccountUi::openManage));
        JSONObject premium = store.getPremium();
        if (premium != null && premium.optBoolean("premium") && !"play".equals(premium.optString("source")))
            card.addView(action(R.string.terminow_account_manage_accmove, R.string.terminow_account_manage_accmove_summary, mAccountUi::openAccmove));
        if (session.emailVerified)
            card.addView(action(R.string.terminow_account_link, R.string.terminow_account_link_summary, () -> mAccountUi.showLinkAccount(session)));
        card.addView(action(R.string.terminow_account_delete, R.string.terminow_account_delete_summary, () -> mAccountUi.confirmDelete(session)));
        content.addView(card);
        content.addView(detail(mActivity.getString(R.string.terminow_account_screen_local_note)));
    }

    // ---------------------------------------------------------------------------------------

    private void dismiss() {
        if (mDialog != null) mDialog.dismiss();
    }

    /** The photo of the Google account, or the first letter of the name or email. */
    @NonNull
    private View avatar(@NonNull TerminowAccount.Session session) {
        if (sPhoto != null && session.photoUrl.equals(sPhotoUrl)) {
            ImageView image = new ImageView(mActivity);
            image.setImageBitmap(sPhoto);
            image.setClipToOutline(true);
            GradientDrawable round = new GradientDrawable();
            round.setShape(GradientDrawable.OVAL);
            round.setColor(Color.TRANSPARENT);
            image.setBackground(round);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setContentDescription(null);
            return image;
        }
        if (!session.photoUrl.isEmpty() && !session.photoUrl.equals(sPhotoUrl)) loadPhoto(session.photoUrl);
        String name = !session.displayName.isEmpty() ? session.displayName : session.email;
        TextView letter = text(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.getDefault()), 20, R.color.terminow_text);
        letter.setGravity(Gravity.CENTER);
        letter.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(color(R.color.terminow_panel_accent));
        letter.setBackground(round);
        letter.setTextColor(Color.WHITE);
        return letter;
    }

    /** Load the photo over https, at most 512 KB, then draw again. */
    private void loadPhoto(@NonNull String url) {
        sPhotoUrl = url;
        new Thread(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                URL parsed = new URL(url);
                if (!"https".equals(parsed.getProtocol())) return;
                connection = (HttpURLConnection) parsed.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        if (out.size() > 512 * 1024) return;
                    }
                    bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size());
                }
            } catch (Exception e) {
                // The letter stays
            } finally {
                if (connection != null) connection.disconnect();
            }
            Bitmap finalBitmap = bitmap;
            if (finalBitmap != null) mHandler.post(() -> {
                if (!url.equals(sPhotoUrl)) return;
                sPhoto = finalBitmap;
                render();
            });
        }, "TerminowAccountPhoto").start();
    }

    @NonNull
    private LinearLayout card(@Nullable View child) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color(R.color.terminow_panel_card));
        shape.setCornerRadius(dp(16));
        card.setBackground(shape);
        if (child != null) card.addView(child);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    @NonNull
    private View action(int titleRes, int summaryRes, @NonNull Runnable onClick) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setBackgroundResource(R.drawable.terminow_action);
        TextView title = text(mActivity.getString(titleRes), 15, R.color.terminow_text);
        row.addView(title);
        row.addView(text(mActivity.getString(summaryRes), 12, R.color.terminow_text_secondary));
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    @NonNull
    private TextView detail(@NonNull String value) {
        TextView view = text(value, 13, R.color.terminow_text_secondary);
        view.setPadding(0, dp(8), 0, 0);
        return view;
    }

    @NonNull
    private TextView button(@NonNull String label, boolean primary) {
        TextView view = text(label, 15, primary ? R.color.terminow_bar_home : R.color.terminow_text);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), 0, dp(16), 0);
        view.setMinHeight(dp(44));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color(primary ? R.color.terminow_panel_accent : R.color.terminow_panel));
        shape.setCornerRadius(dp(22));
        if (!primary) shape.setStroke(dp(1), color(R.color.terminow_text_secondary));
        view.setBackground(shape);
        return view;
    }

    @NonNull
    private TextView text(@NonNull String value, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }

}
