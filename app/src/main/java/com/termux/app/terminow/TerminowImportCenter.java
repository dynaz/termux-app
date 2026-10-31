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
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

/**
 * The unified Import Center: one screen that replaces the separate "Import" and "Quick Import" menus. It
 * groups every existing import source — host imports from other apps, configuration files, cloud providers,
 * and copying device files into a workspace — and dispatches each to the parser or flow that already backs
 * it ({@link Actions}, implemented by TerminowHome). It never parses or executes anything itself, and it
 * keeps host import and workspace file copy as distinct actions.
 *
 * <p>Descriptions match what the parsers actually accept; no unsupported compatibility is implied. Icons
 * are original TermiNow category glyphs (document/table/terminal-config/cloud/folder), not the third
 * parties' trademarked logos, so the screen works offline without redistributing brand assets. A draggable
 * bottom sheet on phones, a constrained dialog on wider screens; light and dark themed.</p>
 */
final class TerminowImportCenter {

    /** The existing import flows this screen dispatches to; TerminowHome implements them. */
    interface Actions {
        /** Pick a file and detect a supported format from its contents (OpenSSH config / CSV / Termius). */
        void chooseFileAutoDetect();
        /** Pick a file and parse it as a specific app/config format. */
        void importSource(@NonNull TerminowImportParsers.Source source);
        void importTermius();
        void importAws();
        void importDigitalOcean();
        /** Copy device files into a selected workspace folder (not a host parser). */
        void importFilesToWorkspace();
    }

    private final TermuxActivity mActivity;
    private final Actions mActions;
    private Dialog mDialog;

    TerminowImportCenter(@NonNull TermuxActivity activity, @NonNull Actions actions) {
        mActivity = activity;
        mActions = actions;
    }

    void show() {
        boolean phone = mActivity.getResources().getConfiguration().screenWidthDp < 600;
        mDialog = new Dialog(mActivity);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.terminow_bar_home));
        bg.setStroke(dp(1), color(R.color.terminow_quick_border));
        float r = dp(24);
        bg.setCornerRadii(phone ? new float[]{r, r, r, r, 0, 0, 0, 0} : new float[]{r, r, r, r, r, r, r, r});
        root.setBackground(bg);

        // Header: title + subtitle + close
        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(mActivity);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(mActivity.getString(R.string.terminow_impc_title), 19, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        titles.addView(title);
        titles.addView(text(mActivity.getString(R.string.terminow_impc_subtitle), 13, R.color.terminow_text_secondary));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", 18, R.color.terminow_text);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(mActivity.getString(android.R.string.cancel));
        close.setOnClickListener(v -> mDialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(head, matchWrap());

        LinearLayout list = new LinearLayout(mActivity);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(mActivity) {
            @Override protected void onMeasure(int w, int h) {
                int max = Math.round(mActivity.getResources().getDisplayMetrics().heightPixels * 0.62f);
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(true);
        scroll.addView(list);
        LinearLayout.LayoutParams sclp = matchWrap();
        sclp.topMargin = dp(10);
        root.addView(scroll, sclp);

        // Primary action: Choose file (auto-detect).
        primary(list, mActivity.getString(R.string.terminow_impc_choose_file),
            mActivity.getString(R.string.terminow_impc_choose_file_sub), () -> { mDialog.dismiss(); mActions.chooseFileAutoDetect(); });

        // FROM OTHER APPS
        section(list, mActivity.getString(R.string.terminow_impc_group_apps));
        row(list, R.drawable.ic_terminow_imp_transfer, 0xFFB03A2E, mActivity.getString(R.string.terminow_qimport_src_filezilla),
            mActivity.getString(R.string.terminow_impc_filezilla_sub), () -> dispatchSource(TerminowImportParsers.Source.FILEZILLA));
        row(list, R.drawable.ic_terminow_terminal, 0xFF2E6DA4, mActivity.getString(R.string.terminow_qimport_src_putty),
            mActivity.getString(R.string.terminow_impc_putty_sub), () -> dispatchSource(TerminowImportParsers.Source.PUTTY));
        row(list, R.drawable.ic_terminow_imp_shield, 0xFF1E7D45, mActivity.getString(R.string.terminow_qimport_src_securecrt),
            mActivity.getString(R.string.terminow_impc_securecrt_sub), () -> dispatchSource(TerminowImportParsers.Source.SECURECRT));
        row(list, R.drawable.ic_terminow_hosts, 0xFF3C4258, mActivity.getString(R.string.terminow_import_termius),
            mActivity.getString(R.string.terminow_impc_termius_sub), () -> { mDialog.dismiss(); mActions.importTermius(); });

        // CONFIGURATION FILES
        section(list, mActivity.getString(R.string.terminow_impc_group_config));
        row(list, R.drawable.ic_terminow_imp_config, 0xFF5B6770, mActivity.getString(R.string.terminow_qimport_src_openssh),
            mActivity.getString(R.string.terminow_impc_openssh_sub), () -> dispatchSource(TerminowImportParsers.Source.OPENSSH));
        row(list, R.drawable.ic_terminow_imp_table, 0xFF2E8B57, mActivity.getString(R.string.terminow_qimport_src_csv),
            mActivity.getString(R.string.terminow_impc_csv_sub), () -> dispatchSource(TerminowImportParsers.Source.CSV));

        // CLOUD PROVIDERS
        section(list, mActivity.getString(R.string.terminow_impc_group_cloud));
        row(list, R.drawable.ic_terminow_imp_cloud, 0xFFE38A1C, mActivity.getString(R.string.terminow_cloud_src_aws),
            mActivity.getString(R.string.terminow_impc_aws_sub), () -> { mDialog.dismiss(); mActions.importAws(); });
        row(list, R.drawable.ic_terminow_imp_droplet, 0xFF2E86DE, mActivity.getString(R.string.terminow_cloud_src_do),
            mActivity.getString(R.string.terminow_impc_do_sub), () -> { mDialog.dismiss(); mActions.importDigitalOcean(); });

        // FILES TO WORKSPACE
        section(list, mActivity.getString(R.string.terminow_impc_group_files));
        row(list, R.drawable.ic_terminow_imp_folder, 0xFFB8860B, mActivity.getString(R.string.terminow_impc_files),
            mActivity.getString(R.string.terminow_impc_files_sub), () -> { mDialog.dismiss(); mActions.importFilesToWorkspace(); });

        mDialog.setContentView(root);
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);  // keep the terminal keyboard hidden
            int screen = mActivity.getResources().getDisplayMetrics().widthPixels;
            if (phone) { window.setGravity(Gravity.BOTTOM); window.setLayout(screen, ViewGroup.LayoutParams.WRAP_CONTENT); }
            else window.setLayout(Math.min(screen - dp(32), dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        mDialog.show();
    }

    private void dispatchSource(@NonNull TerminowImportParsers.Source source) {
        mDialog.dismiss();
        mActions.importSource(source);
    }

    // ---- Views ----------------------------------------------------------------------------------

    private void primary(@NonNull LinearLayout list, @NonNull String title, @NonNull String sub, @NonNull Runnable onClick) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(14), dp(10), dp(12), dp(10));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(color(R.color.terminow_panel_accent));
        pbg.setCornerRadius(dp(14));
        row.setBackground(pbg);
        row.setClickable(true);
        row.setOnClickListener(v -> onClick.run());

        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_terminow_import);
        icon.setColorFilter(color(R.color.terminow_bar_home));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(32), dp(32));
        ilp.rightMargin = dp(12);
        row.addView(icon, ilp);

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 16, R.color.terminow_bar_home);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t);
        texts.addView(text(sub, 12, R.color.terminow_bar_home));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setContentDescription(title + ", " + sub);

        LinearLayout.LayoutParams rlp = matchWrap();
        rlp.topMargin = dp(4);
        list.addView(row, rlp);
    }

    private void section(@NonNull LinearLayout list, @NonNull String label) {
        TextView v = text(label, 12, R.color.terminow_text_secondary);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setAllCaps(true);
        v.setPadding(dp(2), dp(16), 0, dp(2));
        androidx.core.view.ViewCompat.setAccessibilityHeading(v, true);
        list.addView(v);
    }

    private void row(@NonNull LinearLayout list, int iconRes, int accent, @NonNull String title, @NonNull String sub, @NonNull Runnable onClick) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(color(R.color.terminow_quick_card));
        cbg.setCornerRadius(dp(12));
        cbg.setStroke(Math.max(1, dp(1)), color(R.color.terminow_quick_border));
        row.setBackground(cbg);
        row.setClickable(true);
        row.setOnClickListener(v -> onClick.run());

        // Icon on a distinct per-source accent chip, so no two sources look identical.
        android.widget.FrameLayout chip = new android.widget.FrameLayout(mActivity);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setShape(GradientDrawable.OVAL);
        chipBg.setColor(accent);
        chip.setBackground(chipBg);
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        android.widget.FrameLayout.LayoutParams iconLp = new android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER);
        chip.addView(icon, iconLp);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        chipLp.rightMargin = dp(12);
        row.addView(chip, chipLp);

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 15, R.color.terminow_text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        texts.addView(t);
        TextView s = text(sub, 12, R.color.terminow_text_secondary);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = text("›", 20, R.color.terminow_text_secondary);
        chevron.setPadding(dp(8), 0, dp(4), 0);
        row.addView(chevron);

        row.setContentDescription(title + ", " + sub);
        LinearLayout.LayoutParams rlp = matchWrap();
        rlp.topMargin = dp(8);
        list.addView(row, rlp);
    }

    @NonNull
    private TextView text(@NonNull String value, int sp, int colorRes) {
        TextView v = new TextView(mActivity);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color(colorRes));
        return v;
    }

    @NonNull
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) { return ContextCompat.getColor(mActivity, res); }
    private int dp(float v) { return Math.round(v * mActivity.getResources().getDisplayMetrics().density); }
}
