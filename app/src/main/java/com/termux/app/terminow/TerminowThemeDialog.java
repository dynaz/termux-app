package com.termux.app.terminow;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.view.ViewUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The dialog to pick the font size, font and color theme of the terminal. The font and the
 * colors are applied by writing the `font.ttf` and `colors.properties` files that the terminal
 * loads, like the Termux:Styling app does.
 */
public class TerminowThemeDialog {

    /** The fonts that are included in the app, as their names and their files in the fonts assets. */
    private static final String[][] FONTS = {
        {"JetBrains Mono", "JetBrainsMono.ttf"},
        {"Fira Code", "FiraCode.ttf"},
        {"Source Code Pro", "SourceCodePro.ttf"},
        {"Cascadia Code", "CascadiaCode.ttf"},
        {"Ubuntu Mono", "UbuntuMono.ttf"},
        {"PT Mono", "PTMono.ttf"},
    };

    private static final String FONTS_ASSETS_DIR = "fonts";

    private static final String LOG_TAG = "TerminowThemeDialog";

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final Dialog mDialog;
    private final TextView mSizeView;
    private final TextView mFontView;
    private final ThemesAdapter mAdapter = new ThemesAdapter();

    public TerminowThemeDialog(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @Nullable Runnable onDismiss) {
        mActivity = activity;
        mStore = store;

        mDialog = new Dialog(activity, R.style.TerminowAlertDialog);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView closeView = new ImageView(activity);
        closeView.setImageResource(R.drawable.ic_terminow_close);
        closeView.setPadding(dp(10), dp(10), dp(10), dp(10));
        closeView.setOnClickListener(v -> mDialog.dismiss());
        header.addView(closeView, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView titleView = new TextView(activity);
        titleView.setText(R.string.terminow_theme_title);
        titleView.setTextColor(color(R.color.terminow_text));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        // Center the title in the dialog instead of in the space next to the close button
        titleParams.setMarginEnd(dp(44));
        header.addView(titleView, titleParams);
        content.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(createSizeButton("−", false), new LinearLayout.LayoutParams(dp(44), dp(44)));
        mSizeView = createBox();
        mSizeView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sizeParams = new LinearLayout.LayoutParams(dp(52), dp(44));
        sizeParams.setMargins(dp(6), 0, dp(6), 0);
        controls.addView(mSizeView, sizeParams);
        controls.addView(createSizeButton("+", true), new LinearLayout.LayoutParams(dp(44), dp(44)));
        mFontView = createBox();
        mFontView.setGravity(Gravity.CENTER_VERTICAL);
        mFontView.setPadding(dp(14), 0, dp(14), 0);
        mFontView.setOnClickListener(v -> showFonts());
        LinearLayout.LayoutParams fontParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        fontParams.setMarginStart(dp(14));
        controls.addView(mFontView, fontParams);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsParams.topMargin = dp(8);
        controlsParams.bottomMargin = dp(14);
        content.addView(controls, controlsParams);

        GridView gridView = new GridView(activity);
        gridView.setNumColumns(GridView.AUTO_FIT);
        gridView.setColumnWidth(dp(104));
        gridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gridView.setVerticalSpacing(dp(10));
        gridView.setHorizontalSpacing(dp(6));
        gridView.setVerticalScrollBarEnabled(false);
        gridView.setSelector(android.R.color.transparent);
        gridView.setAdapter(mAdapter);
        gridView.setOnItemClickListener((parent, view, position, id) -> setTheme(TerminowThemes.THEMES[position]));
        content.addView(gridView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        mDialog.setContentView(content);
        mDialog.setOnDismissListener(dialog -> {
            if (onDismiss != null) onDismiss.run();
        });
        update();
    }

    public void show() {
        mDialog.show();
        Window window = mDialog.getWindow();
        if (window != null) {
            // Wide enough for five themes in a row on a large screen, like the dialog of Termius
            int width = Math.min(mActivity.getResources().getDisplayMetrics().widthPixels - dp(16), dp(640));
            int height = Math.min(mActivity.getResources().getDisplayMetrics().heightPixels - dp(120), dp(640));
            window.setLayout(width, height);
        }
    }



    private void update() {
        mSizeView.setText(String.valueOf(mActivity.getPreferences().getFontSize()));
        String font = mStore.getTerminalFont();
        mFontView.setText(font.isEmpty() ? mActivity.getString(R.string.terminow_theme_font_default) : font);
        mFontView.setTypeface(getFontTypeface(font));
        mAdapter.notifyDataSetChanged();
    }

    private void changeFontSize(boolean increase) {
        TermuxAppSharedPreferences preferences = mActivity.getPreferences();
        preferences.changeFontSize(increase);
        mActivity.getTerminalView().setTextSize(preferences.getFontSize());
        update();
    }

    private void showFonts() {
        CharSequence[] names = new CharSequence[FONTS.length + 1];
        names[0] = mActivity.getString(R.string.terminow_theme_font_default);
        for (int i = 0; i < FONTS.length; i++)
            names[i + 1] = FONTS[i][0];
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setItems(names, (dialog, which) -> setFont(which == 0 ? null : FONTS[which - 1]))
            .show();
    }

    /** Get the typeface of a font of the app to show its name with, the default monospace for an unknown one. */
    @NonNull
    private Typeface getFontTypeface(@NonNull String name) {
        for (String[] font : FONTS) {
            if (!font[0].equals(name)) continue;
            try {
                return Typeface.createFromAsset(mActivity.getAssets(), FONTS_ASSETS_DIR + "/" + font[1]);
            } catch (RuntimeException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load font \"" + font[1] + "\"", e);
            }
        }
        return Typeface.MONOSPACE;
    }

    /** Set the font of the terminal to a font of the app, or to the default one for {@code null}. */
    private void setFont(@Nullable String[] font) {
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        try {
            if (font == null) {
                if (fontFile.exists() && !fontFile.delete())
                    throw new IOException("Failed to delete \"" + fontFile + "\"");
            } else {
                // Write to another file first, so that the terminal never loads a part of the font
                File tmpFile = new File(fontFile.getPath() + ".tmp");
                createParentDirectory(fontFile);
                try (InputStream in = mActivity.getAssets().open(FONTS_ASSETS_DIR + "/" + font[1]);
                     FileOutputStream out = new FileOutputStream(tmpFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1)
                        out.write(buffer, 0, bytesRead);
                }
                if (!tmpFile.renameTo(fontFile))
                    throw new IOException("Failed to replace \"" + fontFile + "\"");
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to set font", e);
            mActivity.showToast(e.getMessage() != null ? e.getMessage() : e.toString(), true);
            return;
        }

        mStore.setTerminalFont(font == null ? "" : font[0]);
        reloadStyle();
    }

    /** Set the colors of the terminal to a theme. */
    private void setTheme(@NonNull TerminowThemes.Theme theme) {
        File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
        try {
            if (theme == TerminowThemes.DEFAULT_THEME) {
                if (colorsFile.exists() && !colorsFile.delete())
                    throw new IOException("Failed to delete \"" + colorsFile + "\"");
            } else {
                createParentDirectory(colorsFile);
                try (FileOutputStream out = new FileOutputStream(colorsFile)) {
                    out.write(theme.getProperties().getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to set theme", e);
            mActivity.showToast(e.getMessage() != null ? e.getMessage() : e.toString(), true);
            return;
        }

        mStore.setTerminalTheme(theme.name);
        reloadStyle();
    }

    private static void createParentDirectory(@NonNull File file) throws IOException {
        File directory = file.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Failed to create \"" + directory + "\"");
    }

    /** Make the terminal load the font and colors files again. */
    private void reloadStyle() {
        TermuxActivity.updateTermuxActivityStyling(mActivity, false);
        update();
    }



    @NonNull
    private TextView createBox() {
        TextView view = new TextView(mActivity);
        view.setTextColor(color(R.color.terminow_text));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setMaxLines(1);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), color(R.color.terminow_host_group));
        view.setBackground(background);
        return view;
    }

    @NonNull
    private TextView createSizeButton(@NonNull String text, boolean increase) {
        TextView button = new TextView(mActivity);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(color(R.color.terminow_text));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setColor(color(R.color.terminow_host_group));
        button.setBackground(background);
        button.setOnClickListener(v -> changeFontSize(increase));
        return button;
    }

    private int color(int colorRes) {
        return ContextCompat.getColor(mActivity, colorRes);
    }

    private int dp(float dp) {
        return (int) ViewUtils.dpToPx(mActivity, dp);
    }



    private class ThemesAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return TerminowThemes.THEMES.length;
        }

        @Override
        public Object getItem(int position) {
            return TerminowThemes.THEMES[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout item = (LinearLayout) convertView;
            if (item == null) {
                item = new LinearLayout(mActivity);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                item.setPadding(dp(4), dp(4), dp(4), dp(6));
                item.addView(new ThemePreviewView(mActivity), new LinearLayout.LayoutParams(dp(88), dp(58)));
                TextView nameView = new TextView(mActivity);
                nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                nameView.setGravity(Gravity.CENTER);
                nameView.setMaxLines(2);
                nameView.setPadding(0, dp(8), 0, 0);
                item.addView(nameView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            }

            TerminowThemes.Theme theme = TerminowThemes.THEMES[position];
            String selectedName = mStore.getTerminalTheme();
            boolean selected = theme.name.equals(selectedName.isEmpty() ? TerminowThemes.DEFAULT_THEME_NAME : selectedName);

            ((ThemePreviewView) item.getChildAt(0)).setTheme(theme);
            TextView nameView = (TextView) item.getChildAt(1);
            nameView.setText(theme.name);
            nameView.setTextColor(color(selected ? R.color.terminow_accent : R.color.terminow_text_secondary));

            // A border around the theme that is being used
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(10));
            background.setStroke(dp(2), selected ? color(R.color.terminow_accent) : 0);
            item.setBackground(background);
            return item;
        }

    }

    /** A small picture of a terminal with the colors of a theme. */
    private static class ThemePreviewView extends View {

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private TerminowThemes.Theme mTheme;

        ThemePreviewView(@NonNull Context context) {
            super(context);
        }

        void setTheme(@NonNull TerminowThemes.Theme theme) {
            mTheme = theme;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (mTheme == null) return;
            float width = getWidth();
            float height = getHeight();
            float density = getResources().getDisplayMetrics().density;
            float radius = 8 * density;

            // The outline keeps a theme with the color of the dialog visible
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0xFF4A5073);
            mRect.set(0, 0, width, height);
            canvas.drawRoundRect(mRect, radius, radius, mPaint);
            mPaint.setColor(0xFF000000 | mTheme.background);
            mRect.set(density, density, width - density, height - density);
            canvas.drawRoundRect(mRect, radius - density, radius - density, mPaint);

            float margin = width * 0.1f;
            float barHeight = height * 0.12f;
            float gap = height * 0.11f;
            float top = height * 0.17f;
            int foreground = 0xFF000000 | mTheme.foreground;

            // A line of output, a selected line and a prompt with the cursor
            drawBar(canvas, margin, top, width - margin, barHeight, foreground);
            top += barHeight + gap;
            mPaint.setColor((foreground & 0x00FFFFFF) | 0x55000000);
            mRect.set(margin * 0.7f, top - gap * 0.3f, width - margin * 0.7f, top + barHeight + gap * 0.3f);
            canvas.drawRoundRect(mRect, barHeight / 2, barHeight / 2, mPaint);
            drawBar(canvas, margin, top, width * 0.52f, barHeight, foreground);
            drawBar(canvas, width * 0.58f, top, width - margin, barHeight, foreground);
            top += barHeight + gap;
            drawBar(canvas, margin, top, width * 0.32f, barHeight, 0xFF000000 | mTheme.colors[2]);
            drawBar(canvas, width * 0.37f, top, width * 0.58f, barHeight, foreground);
            drawBar(canvas, width * 0.63f, top, width * 0.74f, barHeight, foreground);
            mPaint.setColor(0xFF000000 | mTheme.cursor);
            canvas.drawRect(width * 0.78f, top - barHeight * 0.3f, width * 0.83f, top + barHeight * 1.3f, mPaint);
        }

        private void drawBar(@NonNull Canvas canvas, float left, float top, float right, float height, int color) {
            mPaint.setColor(color);
            mRect.set(left, top, right, top + height);
            canvas.drawRoundRect(mRect, height / 2, height / 2, mPaint);
        }

    }

}
