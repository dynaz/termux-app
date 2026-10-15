package com.termux.app.terminow;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.R;

/**
 * The "Continue with Google" button of the Google branding guidelines: the multicolor G and a dark
 * label on a white, outlined, rounded surface, at least 48dp high. While signing in it shows a
 * spinner and does not take taps, so a sign in is never started twice.
 */
public final class TerminowGoogleButton extends LinearLayout {

    private final ImageView mLogo;
    private final ProgressBar mSpinner;
    private final TextView mLabel;
    private boolean mLoading;

    public TerminowGoogleButton(@NonNull Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setMinimumHeight(dp(48));
        setPadding(dp(12), 0, dp(12), 0);
        setBackgroundResource(R.drawable.terminow_google_button);
        setClickable(true);
        setFocusable(true);

        mLogo = new ImageView(context);
        mLogo.setImageResource(R.drawable.ic_terminow_google_g);
        mLogo.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(mLogo, new LayoutParams(dp(20), dp(20)));

        mSpinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        mSpinner.setIndeterminateTintList(ColorStateList.valueOf(0xFF4285F4));
        mSpinner.setVisibility(GONE);
        addView(mSpinner, new LayoutParams(dp(20), dp(20)));

        mLabel = new TextView(context);
        mLabel.setText(R.string.terminow_account_google);
        mLabel.setTextColor(0xFF1F1F1F);
        mLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mLabel.setMaxLines(1);
        LayoutParams labelParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        labelParams.setMarginStart(dp(12));
        addView(mLabel, labelParams);
        setContentDescription(mLabel.getText());
    }

    /** Show that a sign in runs, and take no taps meanwhile. */
    public void setLoading(boolean loading) {
        mLoading = loading;
        mLogo.setVisibility(loading ? GONE : VISIBLE);
        mSpinner.setVisibility(loading ? VISIBLE : GONE);
        mLabel.setText(loading ? R.string.terminow_account_google_signing_in : R.string.terminow_account_google);
        setContentDescription(mLabel.getText());
        setEnabled(!loading);
    }

    public boolean isLoading() {
        return mLoading;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        // Faded while disabled, but still readable while loading
        setAlpha(enabled || mLoading ? 1f : 0.5f);
    }

    @Override
    public boolean performClick() {
        // Repeated taps while a sign in runs do nothing
        if (mLoading || !isEnabled()) return false;
        return super.performClick();
    }

    /** Full width with the margins of a form. */
    @NonNull
    public static LayoutParams fullWidth(@NonNull View anchor, int topDp, int bottomDp) {
        float density = anchor.getResources().getDisplayMetrics().density;
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = Math.round(topDp * density);
        params.bottomMargin = Math.round(bottomDp * density);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
