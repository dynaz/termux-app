package com.termux.app.terminow;

import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;

/** The ⓘ help button next to a section header: it opens the matching page on terminow.online. */
final class TerminowHelp {

    static final String BASE = "https://terminow.online/";

    private TerminowHelp() {}

    /** Open the terminow.online help page at {@code path} (e.g. "ai", "mcp", "support"), or the home. */
    static void open(@NonNull TermuxActivity activity, @NonNull String path) {
        String url = BASE + (path.isEmpty() ? "" : path + "/");
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            activity.showToast(url, false);
        }
    }

    /** An ⓘ info button with a 44dp touch target that opens the help page at {@code path}. */
    @NonNull
    static ImageView icon(@NonNull TermuxActivity activity, @NonNull String path) {
        ImageView info = new ImageView(activity);
        info.setImageResource(R.drawable.ic_terminow_info);
        int pad = Math.round(activity.getResources().getDisplayMetrics().density * 10);
        info.setPadding(pad, pad, pad, pad);
        info.setContentDescription(activity.getString(R.string.terminow_help));
        info.setOnClickListener(v -> open(activity, path));
        int size = Math.round(activity.getResources().getDisplayMetrics().density * 44);
        info.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        return info;
    }

    /** A horizontal row with {@code title} and a trailing ⓘ help button for {@code path}. */
    @NonNull
    static LinearLayout header(@NonNull TermuxActivity activity, @NonNull View title, @NonNull String path) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title);
        row.addView(icon(activity, path));
        return row;
    }
}
