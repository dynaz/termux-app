package com.termux.app.terminow;

import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.List;

/**
 * A small floating card over the terminal that shows a tool installing in the background, decoupled from
 * the Tools sheet: it reads {@link TerminowToolInstaller}'s shared job store, so closing or swiping the
 * sheet away never stops a job. It shows the running tool, an indeterminate progress bar and how many
 * more are queued; a × hides it while the job keeps running, and a new job brings it back. Tapping it
 * opens the Tools sheet. The card lives on the activity content view, so it floats above the terminal.
 *
 * <p>Compile-checked; its placement above the keyboard and navigation bar needs device verification.</p>
 */
public final class TerminowInstallProgress {

    private final TermuxActivity mActivity;
    private final TerminowLauncher mLauncher;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mOnJobs = () -> mHandler.post(this::update);

    @Nullable private View mCard;
    @Nullable private TextView mText;
    /** Hidden by the user with ×; stays hidden until more jobs than when it was hidden are running. */
    private boolean mHidden;
    private int mHiddenAt;

    public TerminowInstallProgress(@NonNull TermuxActivity activity, @NonNull TerminowLauncher launcher) {
        mActivity = activity;
        mLauncher = launcher;
    }

    public void attach() {
        TerminowToolInstaller.addListener(mOnJobs);
        update();
    }

    public void detach() {
        TerminowToolInstaller.removeListener(mOnJobs);
        removeCard();
    }

    private void update() {
        List<TerminowToolInstaller.Job> running = TerminowToolInstaller.runningJobs();
        if (running.isEmpty()) {
            mHidden = false;
            removeCard();
            return;
        }
        if (mHidden && running.size() <= mHiddenAt) {
            removeCard();
            return;
        }
        mHidden = false;
        TerminowToolInstaller.Job job = running.get(0);
        String label = mActivity.getString(R.string.terminow_tools_github_installing, TerminowToolInstaller.jobLabel(job));
        int queued = (running.size() - 1) + TerminowToolInstaller.queuedCount();
        if (queued > 0) label = label + mActivity.getString(R.string.terminow_tools_progress_queued, queued);
        showCard();
        if (mText != null) mText.setText(label);
    }

    private void showCard() {
        if (mCard != null) return;
        FrameLayout root = mActivity.findViewById(android.R.id.content);
        if (root == null) return;

        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(10), dp(8), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.terminow_panel));
        bg.setStroke(dp(1), color(R.color.terminow_panel_accent));
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setElevation(dp(8));
        card.setClickable(true);
        card.setOnClickListener(v -> mLauncher.showInstallTools());

        ProgressBar bar = new ProgressBar(mActivity);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        barParams.rightMargin = dp(12);
        card.addView(bar, barParams);

        TextView text = new TextView(mActivity);
        text.setTextColor(color(R.color.terminow_text));
        text.setTextSize(13);
        text.setMaxLines(2);
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mText = text;

        TextView close = new TextView(mActivity);
        close.setText("✕");
        close.setTextColor(color(R.color.terminow_text_secondary));
        close.setTextSize(16);
        close.setPadding(dp(12), dp(4), dp(12), dp(4));
        close.setContentDescription(mActivity.getString(android.R.string.cancel));
        close.setOnClickListener(v -> {
            mHidden = true;
            mHiddenAt = TerminowToolInstaller.runningJobs().size();
            removeCard();
        });
        card.addView(close);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.setMargins(dp(12), 0, dp(12), dp(80));
        card.setLayoutParams(lp);
        root.addView(card);
        mCard = card;
    }

    private void removeCard() {
        if (mCard != null && mCard.getParent() instanceof ViewGroup) ((ViewGroup) mCard.getParent()).removeView(mCard);
        mCard = null;
        mText = null;
    }

    private int color(int res) {
        return androidx.core.content.ContextCompat.getColor(mActivity, res);
    }

    private int dp(int v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }
}
