package com.termux.app.terminow;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.Collections;
import java.util.Locale;

/**
 * A small in-app browser for a tool's official documentation: it loads one HTTPS page in a WebView with
 * a title bar and an "Open in browser" action, so reading the docs never leaves TermiNow. Only https
 * pages are loaded; a page that fails to load shows a plain message with the external-browser fallback
 * rather than a broken WebView. It never installs, launches or connects anything — it only shows a page.
 */
public final class TerminowDocView {

    private TerminowDocView() {}

    /** Open the documentation at {@code url} for {@code title}; no-op for a non-HTTPS url. */
    public static void open(@NonNull TermuxActivity activity, @NonNull String title, @NonNull String url) {
        if (!isHttps(url)) {
            activity.showToast(activity.getString(R.string.terminow_doc_unavailable), true);
            return;
        }
        new TerminowDocView().show(activity, title, url);
    }

    static boolean isHttps(@NonNull String url) {
        return url.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    private void show(@NonNull TermuxActivity activity, @NonNull String title, @NonNull String url) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(activity, R.color.terminow_bar_home));

        // Title bar: close, title, Open in browser.
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(activity, 6), dp(activity, 6), dp(activity, 10), dp(activity, 6));

        TextView close = iconText(activity, "✕", activity.getString(R.string.terminow_profiles_close));
        close.setOnClickListener(v -> dialog.dismiss());
        bar.addView(close, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(color(activity, R.color.terminow_text));
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(dp(activity, 6), 0, dp(activity, 6), 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView openExternal = new TextView(activity);
        openExternal.setText(R.string.terminow_doc_open_in_browser);
        openExternal.setAllCaps(false);
        openExternal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        openExternal.setTypeface(Typeface.DEFAULT_BOLD);
        openExternal.setTextColor(color(activity, R.color.terminow_panel_accent));
        openExternal.setGravity(Gravity.CENTER);
        openExternal.setMinHeight(dp(activity, 48));
        openExternal.setMinWidth(dp(activity, 48));
        openExternal.setPadding(dp(activity, 10), 0, dp(activity, 10), 0);
        openExternal.setOnClickListener(v -> TerminowBrowser.open(activity, Collections.singletonList(url)));
        bar.addView(openExternal);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(activity);
        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        body.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 3)));

        TextView error = new TextView(activity);
        error.setText(R.string.terminow_doc_unavailable);
        error.setTextColor(color(activity, R.color.terminow_text_secondary));
        error.setGravity(Gravity.CENTER);
        error.setPadding(dp(activity, 24), dp(activity, 24), dp(activity, 24), dp(activity, 24));
        error.setVisibility(View.GONE);
        FrameLayout.LayoutParams errLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errLp.gravity = Gravity.CENTER;

        WebView web = new WebView(activity);
        web.setBackgroundColor(color(activity, R.color.terminow_bg));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Stay in-app for https pages; anything else (mailto, intents, http) goes to the browser.
                String target = request.getUrl() != null ? request.getUrl().toString() : "";
                if (isHttps(target)) return false;
                if (TerminowBrowser.isWebLink(target)) TerminowBrowser.open(activity, Collections.singletonList(target));
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String finishedUrl) {
                progress.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError err) {
                // Only the main page failing is worth surfacing; sub-resource errors are ignored.
                if (request != null && request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    web.setVisibility(View.GONE);
                    error.setVisibility(View.VISIBLE);
                }
            }
        });
        body.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(error, errLp);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.setOnDismissListener(d -> web.destroy());
        web.loadUrl(url);
        dialog.show();
    }

    @NonNull
    private TextView iconText(@NonNull TermuxActivity activity, @NonNull String glyph, @NonNull String description) {
        TextView view = new TextView(activity);
        view.setText(glyph);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        view.setTextColor(color(activity, R.color.terminow_text));
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        TypedValue ripple = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        if (ripple.resourceId != 0) view.setBackgroundResource(ripple.resourceId);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private static int color(@NonNull TermuxActivity activity, int res) {
        return ContextCompat.getColor(activity, res);
    }

    private static int dp(@NonNull TermuxActivity activity, int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
