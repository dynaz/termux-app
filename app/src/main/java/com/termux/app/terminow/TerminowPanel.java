package com.termux.app.terminow;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;

/**
 * A panel at the side of a wide screen, or all of a narrow one, like the host panel and the key
 * panels of Termius: a close button, a title, a menu and a save button, then cards of fields.
 */
public class TerminowPanel {

    /** Like the host panel: a part of a wide screen, all of a narrow one. */
    static final float SCREEN_WIDTH_FRACTION = 0.40f;
    static final int MIN_WIDTH_DP = 340;
    static final int MIN_SCREEN_WIDTH_DP = 600;

    private final Activity mActivity;
    private final Dialog mDialog;
    private final LinearLayout mContent;
    private final ImageButton mSave;
    private final ImageButton mMenu;

    public TerminowPanel(@NonNull Activity activity, @NonNull String title) {
        mActivity = activity;
        mDialog = new Dialog(activity, R.style.TerminowPanelDialog);
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(color(R.color.terminow_panel_scrim));
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(color(R.color.terminow_panel));
        column.setClickable(true);
        column.setFocusable(true);
        column.setFitsSystemWindows(true);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(8), 0);
        ImageButton close = iconButton(R.drawable.ic_terminow_close, mActivity.getString(android.R.string.cancel));
        close.setOnClickListener(v -> mDialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(color(R.color.terminow_text));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMarginStart(dp(8));
        header.addView(titleView, titleParams);
        mMenu = iconButton(R.drawable.ic_terminow_more, mActivity.getString(R.string.terminow_more_options));
        mMenu.setVisibility(View.GONE);
        header.addView(mMenu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        mSave = iconButton(R.drawable.ic_terminow_check, mActivity.getString(R.string.terminow_save));
        mSave.setVisibility(View.GONE);
        header.addView(mSave, new LinearLayout.LayoutParams(dp(44), dp(44)));
        column.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        mContent = new LinearLayout(activity);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setPadding(dp(12), 0, dp(12), dp(24));
        scroll.addView(mContent);
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int rootWidth = right - left;
            int width = rootWidth < dp(MIN_SCREEN_WIDTH_DP) ? rootWidth : Math.max(dp(MIN_WIDTH_DP), Math.round(rootWidth * SCREEN_WIDTH_FRACTION));
            ViewGroup.LayoutParams params = column.getLayoutParams();
            if (params.width != width) {
                params.width = width;
                column.post(() -> column.setLayoutParams(params));
            }
        });
        // Tapping the dimmed screen next to the panel closes it
        root.setOnClickListener(v -> mDialog.dismiss());
        mDialog.setContentView(root);
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    @NonNull
    public Dialog getDialog() {
        return mDialog;
    }

    public void show() {
        mDialog.show();
    }

    public void dismiss() {
        mDialog.dismiss();
    }

    /** Show the save button; it runs {@code onSave}, which closes the panel if it returns true. */
    public void onSave(@NonNull java.util.function.BooleanSupplier onSave) {
        mSave.setVisibility(View.VISIBLE);
        mSave.setOnClickListener(v -> {
            if (onSave.getAsBoolean()) mDialog.dismiss();
        });
    }

    public void setSaveEnabled(boolean enabled) {
        mSave.setEnabled(enabled);
        mSave.setAlpha(enabled ? 1f : 0.35f);
    }

    /** Show the ⋮ button, which gets the button to show a menu at. */
    public void onMenu(@NonNull java.util.function.Consumer<View> onMenu) {
        mMenu.setVisibility(View.VISIBLE);
        mMenu.setOnClickListener(onMenu::accept);
    }

    @NonNull
    public LinearLayout content() {
        return mContent;
    }

    /** A card of the panel, with the room of the cards above it. */
    @NonNull
    public LinearLayout card() {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.terminow_panel_card);
        card.setPadding(dp(14), dp(6), dp(14), dp(16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        mContent.addView(card, params);
        return card;
    }

    @NonNull
    public TextView label(@NonNull LinearLayout parent, @NonNull String text) {
        TextView label = text(text, 12, R.color.terminow_text_secondary);
        label.setPadding(0, dp(14), 0, 0);
        parent.addView(label);
        return label;
    }

    /** A field like the ones of the host panel: a label above, a line below. */
    @NonNull
    public EditText field(@NonNull LinearLayout parent, @NonNull String label, @NonNull String hint, int inputType) {
        label(parent, label);
        EditText field = new EditText(mActivity);
        field.setBackgroundResource(R.drawable.terminow_panel_field);
        field.setPadding(0, dp(6), 0, dp(6));
        field.setTextColor(color(R.color.terminow_text));
        field.setHintTextColor(color(R.color.terminow_text_secondary));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        parent.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    /** A field for keys: monospace, several lines, never corrected by the keyboard. */
    @NonNull
    public EditText keyArea(@NonNull LinearLayout parent, @NonNull String label, @NonNull String hint) {
        EditText area = field(parent, label, hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        area.setTypeface(Typeface.MONOSPACE);
        area.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        area.setMinLines(5);
        area.setMaxLines(12);
        area.setGravity(Gravity.TOP | Gravity.START);
        area.setHorizontallyScrolling(false);
        return area;
    }

    /** A line of text under a field, for what was checked: green, red or grey. */
    @NonNull
    public TextView status(@NonNull LinearLayout parent) {
        TextView status = text("", 13, R.color.terminow_text_secondary);
        status.setPadding(0, dp(6), 0, 0);
        parent.addView(status);
        return status;
    }

    public void setStatus(@NonNull TextView status, @NonNull String text, int colorRes) {
        status.setText(text);
        status.setTextColor(color(colorRes));
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** A wide blue button, like "Export or share a public key" of Termius. */
    @NonNull
    public TextView primaryButton(@NonNull LinearLayout parent, @NonNull String text, @NonNull Runnable onClick) {
        TextView button = text(text, 15, R.color.terminow_text);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color(R.color.terminow_panel_accent));
        shape.setCornerRadius(dp(10));
        button.setBackground(shape);
        button.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.topMargin = dp(16);
        parent.addView(button, params);
        return button;
    }

    /** A link with an icon, like "Add certificate" of Termius. */
    @NonNull
    public TextView link(@NonNull LinearLayout parent, int iconRes, @NonNull String text, @NonNull Runnable onClick) {
        TextView link = text(text, 15, R.color.terminow_panel_accent);
        link.setTypeface(Typeface.DEFAULT_BOLD);
        link.setGravity(Gravity.CENTER_VERTICAL);
        link.setMinHeight(dp(44));
        if (iconRes != 0) {
            link.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
            link.setCompoundDrawablePadding(dp(10));
            link.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
        }
        link.setOnClickListener(v -> onClick.run());
        parent.addView(link, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return link;
    }

    /** Links next to each other, like Paste · From file · Generate. */
    @NonNull
    public LinearLayout linkRow(@NonNull LinearLayout parent) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);
        parent.addView(row);
        return row;
    }

    /**
     * A row with an icon, a title and a line under it, and buttons at its end, like the key at
     * the top of the Edit Key panel of Termius.
     */
    @NonNull
    public LinearLayout row(@NonNull LinearLayout parent, int iconRes, @NonNull String title, @Nullable String subtitle,
                            @Nullable Runnable onClick) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(10), dp(8), dp(4), dp(8));
        row.setBackgroundResource(R.drawable.terminow_panel_card);
        if (iconRes != 0) {
            ImageView icon = new ImageView(mActivity);
            icon.setImageResource(iconRes);
            icon.setColorFilter(color(R.color.terminow_text));
            icon.setPadding(dp(10), dp(10), dp(10), dp(10));
            android.graphics.drawable.GradientDrawable tile = new android.graphics.drawable.GradientDrawable();
            tile.setColor(color(R.color.terminow_host_mosh));
            tile.setCornerRadius(dp(10));
            icon.setBackground(tile);
            row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(8), 0);
        TextView titleView = text(title, 16, R.color.terminow_text);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleView);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 13, R.color.terminow_text_secondary);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (onClick != null) {
            TypedValue background = new TypedValue();
            mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true);
            row.setForeground(ContextCompat.getDrawable(mActivity, background.resourceId));
            row.setOnClickListener(v -> onClick.run());
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        parent.addView(row, params);
        return row;
    }

    /** A button with an icon at the end of a row. */
    @NonNull
    public ImageButton rowButton(@NonNull LinearLayout row, int iconRes, @NonNull String description, @NonNull Runnable onClick) {
        ImageButton button = iconButton(iconRes, description);
        button.setColorFilter(color(R.color.terminow_text_secondary));
        button.setOnClickListener(v -> onClick.run());
        row.addView(button, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return button;
    }

    /** A card with a title that opens and closes, like "Attached to" of Termius. */
    @NonNull
    public LinearLayout expandable(@NonNull String title, boolean open) {
        LinearLayout card = card();
        card.setPadding(dp(14), 0, dp(6), dp(4));
        LinearLayout header = new LinearLayout(mActivity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(56));
        TextView titleView = text(title, 16, R.color.terminow_text);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView chevron = new ImageView(mActivity);
        chevron.setImageResource(R.drawable.ic_terminow_chevron_down);
        chevron.setColorFilter(color(R.color.terminow_text_secondary));
        header.addView(chevron, new LinearLayout.LayoutParams(dp(40), dp(40)));
        card.addView(header);
        LinearLayout body = new LinearLayout(mActivity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, dp(8), dp(10));
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        chevron.setRotation(open ? 180 : 0);
        card.addView(body);
        header.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(show ? 180 : 0).setDuration(150).start();
        });
        return body;
    }

    @NonNull
    public TextView text(@NonNull String value, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    @NonNull
    private ImageButton iconButton(int iconRes, @NonNull String description) {
        ImageButton button = new ImageButton(mActivity);
        button.setImageResource(iconRes);
        button.setBackground(null);
        button.setColorFilter(color(R.color.terminow_text));
        button.setContentDescription(description);
        return button;
    }

    int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }
}
