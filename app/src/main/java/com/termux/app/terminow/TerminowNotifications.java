package com.termux.app.terminow;

import android.app.Dialog;
import android.graphics.Typeface;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.view.ViewUtils;

import java.util.List;

/**
 * The notifications panel that is opened with the bell of the tab bar, like the one of Termius.
 * It lists the notifications of {@link TerminowStore}, like the changes of an app update.
 */
public class TerminowNotifications {

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final Dialog mDialog;
    private final LinearLayout mContentView;

    public TerminowNotifications(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull Runnable onDismiss) {
        mActivity = activity;
        mStore = store;

        mDialog = new Dialog(activity, R.style.TerminowAlertDialog);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView closeView = new ImageView(activity);
        closeView.setImageResource(R.drawable.ic_terminow_close);
        closeView.setPadding(dp(10), dp(10), dp(10), dp(10));
        closeView.setContentDescription(activity.getString(android.R.string.cancel));
        closeView.setOnClickListener(v -> mDialog.dismiss());
        header.addView(closeView, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView titleView = new TextView(activity);
        titleView.setText(R.string.terminow_notifications);
        titleView.setTextColor(color(R.color.terminow_text));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        // Center the title in the dialog instead of in the space next to the close button
        titleParams.setMarginEnd(dp(44));
        header.addView(titleView, titleParams);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        mContentView = new LinearLayout(activity);
        mContentView.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(mContentView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        mDialog.setContentView(root);
        mDialog.setOnDismissListener(dialog -> onDismiss.run());
    }

    public void show() {
        update();
        mDialog.show();
        Window window = mDialog.getWindow();
        if (window != null) {
            int width = Math.min(mActivity.getResources().getDisplayMetrics().widthPixels - dp(16), dp(640));
            int height = Math.min(mActivity.getResources().getDisplayMetrics().heightPixels - dp(120), dp(640));
            window.setLayout(width, height);
        }
        // The notifications are seen once the panel is opened
        mStore.markNotificationsRead();
    }

    private void update() {
        mContentView.removeAllViews();
        List<TerminowStore.Notification> notifications = mStore.getNotifications();

        if (notifications.isEmpty()) {
            mContentView.setGravity(Gravity.CENTER);
            TextView titleView = new TextView(mActivity);
            titleView.setText(R.string.terminow_notifications_empty_title);
            titleView.setTextColor(color(R.color.terminow_text));
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setGravity(Gravity.CENTER);
            mContentView.addView(titleView);
            TextView textView = new TextView(mActivity);
            textView.setText(R.string.terminow_notifications_empty);
            textView.setTextColor(color(R.color.terminow_text));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            textView.setGravity(Gravity.CENTER);
            textView.setPadding(0, dp(12), 0, 0);
            mContentView.addView(textView);
            return;
        }

        mContentView.setGravity(Gravity.TOP);
        for (TerminowStore.Notification notification : notifications) {
            LinearLayout item = new LinearLayout(mActivity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setBackgroundResource(R.drawable.terminow_card);
            item.setPadding(dp(16), dp(14), dp(16), dp(14));

            LinearLayout header = new LinearLayout(mActivity);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView titleView = new TextView(mActivity);
            titleView.setText(notification.title);
            titleView.setTextColor(color(R.color.terminow_text));
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            titleView.setTypeface(null, Typeface.BOLD);
            header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView timeView = new TextView(mActivity);
            timeView.setText(DateUtils.getRelativeTimeSpanString(notification.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            timeView.setTextColor(color(R.color.terminow_text_secondary));
            timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            header.addView(timeView);
            item.addView(header);

            if (!notification.text.isEmpty()) {
                TextView textView = new TextView(mActivity);
                textView.setText(notification.text);
                textView.setTextColor(color(R.color.terminow_text_secondary));
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                textView.setPadding(0, dp(6), 0, 0);
                textView.setLineSpacing(dp(2), 1);
                item.addView(textView);
            }

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(10);
            mContentView.addView(item, params);
        }
    }

    private int color(int colorRes) {
        return ContextCompat.getColor(mActivity, colorRes);
    }

    private int dp(float dp) {
        return (int) ViewUtils.dpToPx(mActivity, dp);
    }

}
