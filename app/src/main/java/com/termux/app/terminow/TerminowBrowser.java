package com.termux.app.terminow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.List;

/**
 * Opens links from the terminal in Chrome, outside of TermiNow, which keeps its sessions as they
 * are. Without Chrome the default browser opens them instead, and the user is told. Links are
 * opened exactly as they were selected, and never logged.
 */
public final class TerminowBrowser {

    static final String CHROME = "com.android.chrome";

    private TerminowBrowser() {}

    /** One link: open it. More: let the user pick one first. */
    public static void open(@NonNull TermuxActivity activity, @NonNull List<String> urls) {
        if (urls.isEmpty()) return;
        if (urls.size() == 1) {
            openOne(activity, urls.get(0));
            return;
        }
        CharSequence[] labels = new CharSequence[urls.size()];
        for (int i = 0; i < urls.size(); i++) labels[i] = shorten(urls.get(i), 90);
        new AlertDialog.Builder(activity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_open_url_pick)
            .setItems(labels, (dialog, which) -> openOne(activity, urls.get(which)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * An intent to view the link in a browser: in Chrome when given its package. A link is only
     * opened when it is http or https.
     */
    @NonNull
    static Intent viewIntent(@NonNull String url, @androidx.annotation.Nullable String browserPackage) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        if (browserPackage != null) intent.setPackage(browserPackage);
        return intent;
    }

    static boolean isWebLink(@NonNull String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private static void openOne(@NonNull Activity activity, @NonNull String url) {
        if (!isWebLink(url)) return;
        try {
            activity.startActivity(viewIntent(url, CHROME));
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            // Chrome is not installed, is turned off, or does not take it: the default browser
        }
        try {
            activity.startActivity(viewIntent(url, null));
            if (activity instanceof TermuxActivity)
                ((TermuxActivity) activity).showToast(activity.getString(R.string.terminow_open_url_other_browser), false);
        } catch (ActivityNotFoundException | SecurityException e) {
            if (activity instanceof TermuxActivity)
                ((TermuxActivity) activity).showToast(activity.getString(R.string.terminow_open_url_no_browser), true);
        }
    }

    /** A long link with its middle left out, for the picker, which then still shows its host and its end. */
    @NonNull
    static String shorten(@NonNull String url, int max) {
        if (url.length() <= max) return url;
        int head = (max - 1) * 2 / 3;
        int tail = max - 1 - head;
        return url.substring(0, head) + "…" + url.substring(url.length() - tail);
    }
}
