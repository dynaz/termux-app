package com.termux.app.terminow;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.terminal.TerminalSession;
import com.termux.shared.view.KeyboardUtils;
import com.termux.view.TerminalView;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The key bar below the terminal: a strip of actions (attach, paste, commands and shortcuts,
 * the keys of the tab, the keyboard) and a strip of keys that scrolls. The keys follow the tab
 * that is shown: those of Claude Code while it runs in it, else those of a terminal. Nothing is
 * sent to a tab without a tap of the user; a command is only put on the prompt.
 */
public class TerminowKeyBar {

    /** How often a tab is looked at for Claude Code that was started in it, like from bash. */
    private static final long SCAN_INTERVAL_MS = 2000;

    private final TermuxActivity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    /** What the user chose with the mode button, for each tab. */
    private final Map<TerminalSession, TerminowKeyBarModel.Choice> mChoices = new WeakHashMap<>();
    /** Whether Claude Code was found running in a tab, the last time it was looked at. */
    private final Map<TerminalSession, Boolean> mFound = new WeakHashMap<>();
    @Nullable private TerminalSession mShownSession;
    @Nullable private TerminowKeyBarModel.Mode mShownMode;
    private boolean mShownUserKeys;
    private boolean mScanning;
    private boolean mStarted;
    @Nullable private Dialog mDialog;

    /** Ctrl and Alt of the bar, read by the terminal with {@link #readCtrl()} and {@link #readAlt()}. */
    private static final TerminowKeyBarModel.Modifier CTRL = new TerminowKeyBarModel.Modifier();
    private static final TerminowKeyBarModel.Modifier ALT = new TerminowKeyBarModel.Modifier();
    /** The bar that shows the modifiers, so that it shows them used up. */
    @Nullable private static TerminowKeyBar sShown;

    @Nullable private LinearLayout mBar;
    @Nullable private LinearLayout mRow;
    @Nullable private TextView mCtrlCap;
    @Nullable private TextView mAltCap;
    @Nullable private ImageButton mKeyboardButton;
    /** Whether the software keyboard is shown, as the insets of the window say, {@code null} until known. */
    @Nullable private Boolean mKeyboardShown;
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener mKeyboardWatcher = this::updateKeyboardButton;

    private final Runnable mScan = this::scan;

    public TerminowKeyBar(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    /** The height of the bar: one row of keys, or the rows of keys the user set in termux.properties. */
    public int heightOf(int userKeysHeight) {
        int row = mActivity.getResources().getDimensionPixelSize(R.dimen.terminow_keybar_height);
        TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
        return extraKeys != null && extraKeys.hasUserKeys() ? Math.max(row, userKeysHeight) : row;
    }

    /**
     * Use the page of the toolbar: tools, keys, arrows and More in a row that scrolls, and the
     * keyboard button fixed at its right, which never scrolls away.
     */
    public void bind(@NonNull LinearLayout bar) {
        if (mBar != null) mBar.getViewTreeObserver().removeOnGlobalLayoutListener(mKeyboardWatcher);
        mBar = bar;
        mRow = bar.findViewById(R.id.terminow_keybar_row);
        mKeyboardButton = bar.findViewById(R.id.terminow_keybar_keyboard);
        mKeyboardButton.setBackground(keycap(false, false));
        mKeyboardButton.setImageTintList(ColorStateList.valueOf(color(R.color.terminow_text)));
        mKeyboardButton.setOnClickListener(v -> toggleKeyboard());
        mKeyboardShown = null;
        // The keyboard shows and hides with a new layout of the window, which is resized for it
        bar.getViewTreeObserver().addOnGlobalLayoutListener(mKeyboardWatcher);
        updateKeyboardButton();
        sShown = this;
        mShownMode = null;
        refresh();
    }

    @Nullable
    private TerminalSession currentRunning() {
        TerminalSession session = mActivity.getCurrentSession();
        return session != null && session.isRunning() ? session : null;
    }

    /** The keys for the tab that is shown, after it changed, ended or what it runs changed. */
    public void refresh() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session != mShownSession) {
            // CTRL and ALT never carry over to another tab
            CTRL.reset();
            ALT.reset();
            ExtraKeysView keys = mActivity.getExtraKeysView();
            if (keys != null) keys.resetSpecialButtons();
            mShownSession = session;
            updateModifiers();
        }
        TerminowKeyBarModel.Mode mode = modeOf(session);
        TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
        boolean userKeys = mode == TerminowKeyBarModel.Mode.TERMINAL && extraKeys != null && extraKeys.hasUserKeys();
        if (mRow != null && (mode != mShownMode || userKeys != mShownUserKeys)) fillRow(mode, userKeys);
        mShownMode = mode;
        mShownUserKeys = userKeys;
    }

    /**
     * The row: tools (attach, paste, the commands and shortcuts), keys, the arrows together, and
     * More. Every button is at least 48dp to tap; the keycaps in them are smaller.
     */
    private void fillRow(@NonNull TerminowKeyBarModel.Mode mode, boolean userKeys) {
        LinearLayout row = mRow;
        if (row == null) return;
        ExtraKeysView extraKeysView = mActivity.getExtraKeysView();
        // The keys of termux.properties stay in the row, the others are made again
        for (int i = row.getChildCount() - 1; i >= 0; i--) if (row.getChildAt(i) != extraKeysView) row.removeViewAt(i);
        mCtrlCap = null;
        mAltCap = null;
        int index = 0;
        row.addView(iconCap(R.drawable.ic_terminow_attach, R.string.terminow_keybar_attach, () -> {
            TerminowHome home = mActivity.getTerminowHome();
            if (home != null && currentRunning() != null) home.attachFiles();
        }), index++);
        row.addView(iconCap(R.drawable.ic_terminow_paste, R.string.terminow_keybar_paste, () -> {
            if (currentRunning() != null && mActivity.getTermuxTerminalSessionClient() != null)
                mActivity.getTermuxTerminalSessionClient().onPasteTextFromClipboard(null);
        }), index++);
        boolean claude = mode == TerminowKeyBarModel.Mode.CLAUDE;
        row.addView(iconCap(claude ? R.drawable.ic_terminow_tool_claude : R.drawable.ic_terminow_commands,
            claude ? R.string.terminow_keybar_claude_shortcuts : R.string.terminow_keybar_commands, this::showCommands), index++);
        row.addView(gap(), index++);
        if (extraKeysView != null) extraKeysView.setVisibility(userKeys ? View.VISIBLE : View.GONE);
        if (userKeys && extraKeysView != null) {
            // The keys of the user, in the place of the keys of the bar
            extraKeysView.reload(mActivity.getTermuxTerminalExtraKeys().getExtraKeysInfo(), mActivity.getTerminalToolbarDefaultHeight());
            ViewGroup.LayoutParams params = extraKeysView.getLayoutParams();
            params.width = extraKeysView.getMinimumKeysWidth();
            extraKeysView.setLayoutParams(params);
            index = row.indexOfChild(extraKeysView) + 1;
        } else {
            for (String key : TerminowKeyBarModel.keysOf(mode)) row.addView(keyCap(key, false), index++);
            row.addView(gap(), index++);
            for (String key : TerminowKeyBarModel.NAVIGATION_KEYS) row.addView(keyCap(key, true), index++);
        }
        row.addView(gap(), index++);
        row.addView(iconCap(R.drawable.ic_terminow_more, R.string.terminow_keybar_more, this::showMore), index);
        updateModifiers();
    }

    // ---------------------------------------------------------------------------------------
    // Keycaps

    /**
     * A rounded keycap with a thin border, 38dp high inside a 48dp button, with the ripple of a
     * tap. A modifier that is on has a thicker outline, and a locked one is filled.
     */
    @NonNull
    private android.graphics.drawable.Drawable keycap(boolean on, boolean locked) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(8));
        int accent = color(R.color.terminow_panel_accent);
        shape.setColor(locked ? accent : on ? (accent & 0x00FFFFFF) | 0x40000000 : color(R.color.terminow_keycap));
        // Not told by color alone: the outline is thicker while it is on
        shape.setStroke(on ? dp(2) : Math.max(1, dp(1)), on ? accent : color(R.color.terminow_keycap_border));
        int insetX = dp(3), insetY = (dp(48) - dp(38)) / 2;
        android.graphics.drawable.InsetDrawable cap = new android.graphics.drawable.InsetDrawable(shape, insetX, insetY, insetX, insetY);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(8));
        mask.setColor(0xFFFFFFFF);
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), cap,
            new android.graphics.drawable.InsetDrawable(mask, insetX, insetY, insetX, insetY));
    }

    @NonNull
    private View gap() {
        View gap = new View(mActivity);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1));
        return gap;
    }

    @NonNull
    private ImageButton iconCap(@DrawableRes int icon, @StringRes int label, @NonNull Runnable action) {
        ImageButton button = new ImageButton(mActivity);
        button.setImageResource(icon);
        // The Claude mark has its own color
        if (icon != R.drawable.ic_terminow_tool_claude) button.setImageTintList(ColorStateList.valueOf(color(R.color.terminow_text)));
        button.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        int pad = dp(14);
        button.setPadding(pad, pad, pad, pad);
        button.setBackground(keycap(false, false));
        String text = mActivity.getString(label);
        button.setContentDescription(text);
        TooltipCompat.setTooltipText(button, text);
        button.setOnClickListener(v -> action.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    /**
     * A key: sent through the input of the terminal as a key or a character, never its name.
     * Ctrl and Alt are on for the next key instead, and lock when held. Arrows repeat while held.
     */
    @NonNull
    private TextView keyCap(@NonNull String key, boolean arrow) {
        TextView cap = new TextView(mActivity);
        cap.setText(labelOf(key));
        cap.setGravity(Gravity.CENTER);
        cap.setTextColor(color(R.color.terminow_text));
        cap.setTypeface(Typeface.MONOSPACE);
        cap.setMaxLines(1);
        cap.setIncludeFontPadding(false);
        cap.setPadding(dp(10), dp(9), dp(10), dp(9));
        // Larger text shrinks to fit the keycap instead of being cut
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(cap, 9, arrow ? 18 : 14, 1, TypedValue.COMPLEX_UNIT_SP);
        cap.setMinWidth(dp(48));
        cap.setBackground(keycap(false, false));
        String description = mActivity.getString(descriptionOf(key));
        cap.setContentDescription(description);
        TooltipCompat.setTooltipText(cap, description);
        cap.setFocusable(true);
        cap.setClickable(true);
        cap.setLayoutParams(new LinearLayout.LayoutParams(arrow ? dp(48) : dp(56), dp(48)));
        if (TerminowKeyBarModel.isModifier(key)) {
            TerminowKeyBarModel.Modifier modifier = key.equals("CTRL") ? CTRL : ALT;
            if (modifier == CTRL) mCtrlCap = cap;
            else mAltCap = cap;
            cap.setOnClickListener(v -> {
                modifier.tap();
                updateModifiers();
            });
            cap.setOnLongClickListener(v -> {
                modifier.hold();
                updateModifiers();
                return true;
            });
            return cap;
        }
        cap.setOnClickListener(v -> sendKey(key));
        if (arrow) repeatWhileHeld(cap, key);
        return cap;
    }

    /** Send a key of the bar to the tab that is shown, with Ctrl and Alt of the bar if they are on. */
    private void sendKey(@NonNull String key) {
        if (currentRunning() == null) return;
        TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
        if (extraKeys != null) extraKeys.sendMacro(mActivity.getTerminalView(), key);
        updateModifiers();
    }

    /** Held, an arrow is sent again and again, like a key of a keyboard; a tap sends it once. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void repeatWhileHeld(@NonNull View cap, @NonNull String key) {
        final boolean[] repeated = {false};
        Runnable repeat = new Runnable() {
            @Override
            public void run() {
                repeated[0] = true;
                sendKey(key);
                mHandler.postDelayed(this, 60);
            }
        };
        cap.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    repeated[0] = false;
                    v.setPressed(true);
                    mHandler.postDelayed(repeat, android.view.ViewConfiguration.getLongPressTimeout());
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    mHandler.removeCallbacks(repeat);
                    v.setPressed(false);
                    // A tap is a click, which TalkBack also makes
                    if (!repeated[0]) v.performClick();
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    mHandler.removeCallbacks(repeat);
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
    }

    /** Show Ctrl and Alt as they are: off, on for the next key, or locked, also for TalkBack. */
    private void updateModifiers() {
        updateModifier(mCtrlCap, CTRL);
        updateModifier(mAltCap, ALT);
    }

    private void updateModifier(@Nullable TextView cap, @NonNull TerminowKeyBarModel.Modifier modifier) {
        if (cap == null) return;
        boolean on = modifier.isOn(), locked = modifier.isLocked();
        cap.setBackground(keycap(on, locked));
        cap.setTextColor(locked ? color(R.color.terminow_keycap_on_accent) : color(R.color.terminow_text));
        cap.setTypeface(Typeface.MONOSPACE, on ? Typeface.BOLD : Typeface.NORMAL);
        cap.setSelected(on);
        ViewCompat.setStateDescription(cap, mActivity.getString(locked ? R.string.terminow_keybar_modifier_locked :
            on ? R.string.terminow_keybar_modifier_on : R.string.terminow_keybar_modifier_off));
    }

    /** Whether Ctrl of the bar applies to the key that the terminal sends now; it is used up unless locked. */
    public static boolean readCtrl() {
        boolean on = CTRL.consume();
        if (on && sShown != null) sShown.updateModifiers();
        return on;
    }

    public static boolean readAlt() {
        boolean on = ALT.consume();
        if (on && sShown != null) sShown.updateModifiers();
        return on;
    }

    @NonNull
    static String labelOf(@NonNull String key) {
        switch (key) {
            case "ESC": return "Esc";
            case "TAB": return "Tab";
            case "SHIFT TAB": return "⇧Tab";
            case "CTRL": return "Ctrl";
            case "ALT": return "Alt";
            case "LEFT": return "←";
            case "DOWN": return "↓";
            case "UP": return "↑";
            case "RIGHT": return "→";
            default: return key;
        }
    }

    @StringRes
    static int descriptionOf(@NonNull String key) {
        switch (key) {
            case "ESC": return R.string.terminow_keybar_key_esc;
            case "TAB": return R.string.terminow_keybar_key_tab;
            case "SHIFT TAB": return R.string.terminow_keybar_key_shift_tab;
            case "CTRL": return R.string.terminow_keybar_key_ctrl;
            case "ALT": return R.string.terminow_keybar_key_alt;
            case "LEFT": return R.string.terminow_keybar_key_left;
            case "DOWN": return R.string.terminow_keybar_key_down;
            case "UP": return R.string.terminow_keybar_key_up;
            case "RIGHT": return R.string.terminow_keybar_key_right;
            default: return R.string.terminow_keybar_key_slash;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Keyboard

    /** The keyboard button shows what a tap does: show the keyboard while it is hidden, hide it while it is shown. */
    private void updateKeyboardButton() {
        ImageButton button = mKeyboardButton;
        if (button == null) return;
        boolean shown = KeyboardUtils.isSoftKeyboardVisible(mActivity);
        if (mKeyboardShown != null && mKeyboardShown == shown) return;
        mKeyboardShown = shown;
        button.setImageResource(shown ? R.drawable.ic_terminow_keyboard_hide : R.drawable.ic_terminow_keyboard);
        String label = mActivity.getString(shown ? R.string.terminow_keybar_keyboard_hide : R.string.terminow_keybar_keyboard_show);
        button.setContentDescription(label);
        TooltipCompat.setTooltipText(button, label);
    }

    /**
     * Show or hide the keyboard as it is now, not as it was last asked for. The settings that
     * turn the keyboard off instead of hiding it keep doing so.
     */
    private void toggleKeyboard() {
        com.termux.app.terminal.TermuxTerminalViewClient client = mActivity.getTermuxTerminalViewClient();
        if (client == null) return;
        if (mActivity.getProperties().shouldEnableDisableSoftKeyboardOnToggle() || !mActivity.getPreferences().isSoftKeyboardEnabled()) {
            client.onToggleSoftKeyboardRequest();
            return;
        }
        TerminalView view = mActivity.getTerminalView();
        if (KeyboardUtils.isSoftKeyboardVisible(mActivity)) {
            KeyboardUtils.hideSoftKeyboard(mActivity, view);
        } else {
            KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
            // The keyboard types into the terminal, which keeps the focus
            view.requestFocus();
            KeyboardUtils.showSoftKeyboard(mActivity, view);
        }
    }

    // ---------------------------------------------------------------------------------------
    // More

    /** What is used less often: the keys of the tab, more keys, snippets and the password. */
    private void showMore() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;
        LinearLayout content = openSheet(mActivity.getString(R.string.terminow_keybar_more));
        section(content, R.string.terminow_keybar_mode);
        TerminowKeyBarModel.Choice choice = choiceOf(session);
        TerminowKeyBarModel.Choice[] choices = TerminowKeyBarModel.Choice.values();
        int[] labels = {R.string.terminow_keybar_choice_auto, R.string.terminow_keybar_choice_terminal, R.string.terminow_keybar_choice_claude};
        for (int i = 0; i < choices.length; i++) {
            TerminowKeyBarModel.Choice option = choices[i];
            // A mark, not only a color, says which one is chosen
            LinearLayout row = row(content, (option == choice ? "● " : "○ ") + mActivity.getString(labels[i]), "", () -> {
                mChoices.put(session, option);
                refresh();
            });
            ViewCompat.setStateDescription(row, mActivity.getString(option == choice ? R.string.terminow_keybar_chosen : R.string.terminow_keybar_not_chosen));
        }
        addMore(content);
    }

    @NonNull
    private TerminowKeyBarModel.Mode modeOf(@Nullable TerminalSession session) {
        if (session == null) return TerminowKeyBarModel.Mode.TERMINAL;
        TerminowKeyBarModel.Choice choice = choiceOf(session);
        TerminowHome home = mActivity.getTerminowHome();
        boolean launched = home != null && home.isClaudeTab(session);
        Boolean found = mFound.get(session);
        return TerminowKeyBarModel.resolve(choice, session.isRunning(), launched, found != null && found);
    }

    @NonNull
    private TerminowKeyBarModel.Choice choiceOf(@NonNull TerminalSession session) {
        TerminowKeyBarModel.Choice choice = mChoices.get(session);
        return choice != null ? choice : TerminowKeyBarModel.Choice.AUTO;
    }

    /** Look at the tabs again and again while the terminal can be seen. */
    public void start() {
        mStarted = true;
        mHandler.removeCallbacks(mScan);
        mHandler.post(mScan);
    }

    public void stop() {
        mStarted = false;
        mHandler.removeCallbacks(mScan);
    }

    /**
     * Find out off the main thread whether Claude Code runs in the tab that is shown, which is
     * only needed when the keys are found out and TermiNow did not start it there itself.
     */
    private void scan() {
        if (!mStarted) return;
        mHandler.postDelayed(mScan, SCAN_INTERVAL_MS);
        TerminalSession session = currentRunning();
        TerminowHome home = mActivity.getTerminowHome();
        if (session == null || mScanning || choiceOf(session) != TerminowKeyBarModel.Choice.AUTO ||
            (home != null && home.isClaudeTab(session)) || mActivity.isHomeVisible()) return;
        int pid = session.getPid();
        mScanning = true;
        mExecutor.execute(() -> {
            boolean found = TerminowKeyBarModel.isClaudeRunningUnder(new File("/proc"), pid);
            mHandler.post(() -> {
                mScanning = false;
                Boolean before = mFound.put(session, found);
                if (before == null || before != found) refresh();
            });
        });
    }

    /**
     * The commands and shortcuts for what the tab runs. A command of Claude Code is put on its
     * prompt for the user to send; a shortcut sends its keys. Also opens with Ctrl+Alt+S on a
     * hardware keyboard.
     */
    public void showCommands() {
        TerminalSession session = currentRunning();
        if (session == null) return;
        boolean claude = modeOf(session) == TerminowKeyBarModel.Mode.CLAUDE;
        LinearLayout content = openSheet(mActivity.getString(claude ? R.string.terminow_keybar_commands_claude : R.string.terminow_keybar_commands_terminal));
        if (claude) {
            mFilter = "";
            mContent = content;
            mPanelSession = session;
            fillClaude(content, session);
        } else {
            section(content, R.string.terminow_keybar_section_shortcuts);
            for (TerminowKeyBarModel.Shortcut shortcut : TerminowKeyBarModel.TERMINAL_SHORTCUTS)
                row(content, shortcut.label, describe(shortcut.descriptionName), () -> sendKeys(session, shortcut.keys));
        }
    }

    /**
     * A sheet over the bottom of the terminal, a column in the middle of a tablet or an unfolded
     * phone, never taller than most of the screen. The terminal keeps its text and selection.
     *
     * @return Where its content goes.
     */
    @NonNull
    private LinearLayout openSheet(@NonNull String titleText) {
        if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
        Dialog dialog = new Dialog(mActivity, R.style.TerminowPanelDialog);
        mDialog = dialog;
        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(color(R.color.terminow_panel_scrim));
        root.setOnClickListener(v -> dialog.dismiss());

        LinearLayout sheet = new LinearLayout(mActivity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(R.color.terminow_panel));
        float radius = dp(20);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        sheet.setBackground(background);
        sheet.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView title = text(titleText, 18, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        ViewCompat.setAccessibilityHeading(title, true);
        sheet.addView(title);

        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sheet, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left, dp(560));
            int scrollMax = Math.round((bottom - top) * 0.8f) - dp(60);
            ViewGroup.LayoutParams params = sheet.getLayoutParams();
            ViewGroup.LayoutParams scrollParams = scroll.getLayoutParams();
            int scrollHeight = content.getHeight() > scrollMax ? scrollMax : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (params.width != width || scrollParams.height != scrollHeight) {
                params.width = width;
                scrollParams.height = scrollHeight;
                sheet.post(() -> {
                    sheet.setLayoutParams(params);
                    scroll.setLayoutParams(scrollParams);
                });
            }
        });
        dialog.setContentView(root);
        // The keyboard only opens when a field of the sheet is tapped
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnDismissListener(d -> {
            if (mDialog == dialog) {
                mDialog = null;
                mContent = null;
                mPanelSession = null;
            }
        });
        dialog.show();
        return content;
    }

    /** Snippets, more keys and the password, for a terminal. */
    private void addMore(@NonNull LinearLayout content) {
        section(content, R.string.terminow_keybar_section_more);
        TerminowHome home = mActivity.getTerminowHome();
        if (home != null) {
            row(content, mActivity.getString(R.string.terminow_keybar_snippets), mActivity.getString(R.string.terminow_keybar_snippets_description),
                home::showSnippetsPicker);
            row(content, mActivity.getString(R.string.terminow_keybar_more_keys), mActivity.getString(R.string.terminow_keybar_more_keys_description),
                home::toggleKeyPanel);
        }
        row(content, mActivity.getString(R.string.terminow_keybar_password), mActivity.getString(R.string.terminow_keybar_password_description), () -> {
            TerminalView view = mActivity.getTerminalView();
            if (view != null && view.isAutoFillEnabled()) view.requestAutoFillPassword();
            else mActivity.showToast(mActivity.getString(R.string.terminow_autofill_unavailable), true);
        });
    }

    /** The content of the panel that is open, filled again when a favorite changes. */
    @Nullable private LinearLayout mContent;
    @Nullable private TerminalSession mPanelSession;

    /**
     * Favorites, the commands of the installed Claude Code, its keyboard shortcuts, and its
     * conversations, which start in a new tab. Nothing is sent with Enter, and no permission
     * of Claude Code is answered from here.
     */
    private void fillClaude(@NonNull LinearLayout content, @NonNull TerminalSession session) {
        content.removeAllViews();
        TerminowHome home = mActivity.getTerminowHome();
        TerminowLauncher launcher = home != null ? home.getLauncher() : null;
        TerminowLauncher.SessionInfo info = launcher != null ? launcher.infoOf(session) : null;
        TerminowLauncher.Environment environment = launcher != null && info != null && info.environmentKey != null ?
            launcher.environmentFor(info.environmentKey) : null;
        TerminowLauncher.ClaudeState state = launcher != null && environment != null ? launcher.claudeOf(environment) : null;
        String version = state != null && state.error == null ? state.version : null;
        List<TerminowKeyBarModel.Command> commands = TerminowKeyBarModel.commandsFor(version);
        TerminowStore store = home != null ? home.getStore() : null;
        List<String> favorites = store != null ? store.getClaudeFavorites() : new java.util.ArrayList<>();

        section(content, R.string.terminow_keybar_section_favorites);
        boolean anyFavorite = false;
        for (String text : favorites) {
            TerminowKeyBarModel.Command command = TerminowKeyBarModel.commandOf(text);
            if (command == null || !commands.contains(command)) continue;
            commandRow(content, session, command, true);
            anyFavorite = true;
        }
        if (!anyFavorite) content.addView(text(mActivity.getString(R.string.terminow_keybar_favorites_empty), 12, R.color.terminow_text_secondary));

        section(content, R.string.terminow_keybar_section_commands);
        content.addView(text(mActivity.getString(R.string.terminow_keybar_commands_note), 12, R.color.terminow_text_secondary));
        if (version == null || TerminowKeyBarModel.parseVersion(version) == null)
            content.addView(text(mActivity.getString(R.string.terminow_keybar_commands_unchecked), 12, R.color.terminow_mcp_warn));
        else
            content.addView(text(mActivity.getString(R.string.terminow_keybar_commands_checked, version), 12, R.color.terminow_text_secondary));
        // A filter, since there are many commands; the list below follows what is typed
        android.widget.EditText filter = new android.widget.EditText(mActivity);
        filter.setHint(R.string.terminow_keybar_filter_hint);
        filter.setSingleLine(true);
        filter.setTextColor(color(R.color.terminow_text));
        filter.setHintTextColor(color(R.color.terminow_text_secondary));
        filter.setMinHeight(dp(48));
        filter.setText(mFilter);
        content.addView(filter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout list = new LinearLayout(mActivity);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fillCommands(list, session, commands, favorites);
        filter.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                mFilter = s.toString();
                fillCommands(list, session, commands, favorites);
            }
        });

        section(content, R.string.terminow_keybar_section_keyboard);
        for (TerminowKeyBarModel.Shortcut shortcut : TerminowKeyBarModel.CLAUDE_SHORTCUTS)
            row(content, shortcut.label, describe(shortcut.descriptionName), () -> sendKeys(session, shortcut.keys));

        section(content, R.string.terminow_keybar_section_conversations);
        content.addView(text(mActivity.getString(R.string.terminow_keybar_conversations_note), 12, R.color.terminow_text_secondary));
        if (environment == null || launcher == null) {
            content.addView(text(mActivity.getString(R.string.terminow_keybar_conversations_unknown), 12, R.color.terminow_text_secondary));
        } else if (state == null) {
            content.addView(text(mActivity.getString(R.string.terminow_keybar_conversations_checking), 12, R.color.terminow_text_secondary));
            // The flags of the installed version are found out, then the panel is filled again
            launcher.whenClaudeChecked(environment, () -> {
                if (mContent == content && mPanelSession == session) fillClaude(content, session);
            });
        } else {
            TerminowLauncher.Environment env = environment;
            if (state.supportsContinue)
                row(content, mActivity.getString(R.string.terminow_picker_claude_continue), mActivity.getString(R.string.terminow_picker_claude_continue_summary),
                    () -> launcher.launchClaudeInNewTab(env, new String[]{"--continue"}));
            if (state.supportsResume)
                row(content, mActivity.getString(R.string.terminow_picker_claude_pick), mActivity.getString(R.string.terminow_picker_claude_pick_summary),
                    () -> launcher.launchClaudeInNewTab(env, new String[]{"--resume"}));
            row(content, mActivity.getString(R.string.terminow_picker_claude_new), mActivity.getString(R.string.terminow_picker_claude_new_summary),
                () -> launcher.launchClaudeInNewTab(env, new String[0]));
            if (!state.supportsContinue && !state.supportsResume)
                content.addView(text(mActivity.getString(R.string.terminow_picker_claude_flags_missing), 12, R.color.terminow_text_secondary));
        }
    }

    /** What was typed in the filter of the commands, kept while the panel is filled again. */
    private String mFilter = "";

    private static final int[] CATEGORY_LABELS = {R.string.terminow_keybar_cat_conversation, R.string.terminow_keybar_cat_model,
        R.string.terminow_keybar_cat_project, R.string.terminow_keybar_cat_tools, R.string.terminow_keybar_cat_account};

    /** The commands that match the filter, under the name of their group. */
    private void fillCommands(@NonNull LinearLayout list, @NonNull TerminalSession session, @NonNull List<TerminowKeyBarModel.Command> commands,
                              @NonNull List<String> favorites) {
        list.removeAllViews();
        boolean any = false;
        for (TerminowKeyBarModel.Category category : TerminowKeyBarModel.Category.values()) {
            boolean header = false;
            for (TerminowKeyBarModel.Command command : commands) {
                if (command.category != category || !TerminowKeyBarModel.matches(command, describe(command.descriptionName), mFilter)) continue;
                if (!header) {
                    TextView label = text(mActivity.getString(CATEGORY_LABELS[category.ordinal()]), 12, R.color.terminow_text_secondary);
                    label.setTypeface(Typeface.DEFAULT_BOLD);
                    label.setPadding(dp(4), dp(10), 0, dp(2));
                    ViewCompat.setAccessibilityHeading(label, true);
                    list.addView(label);
                    header = true;
                }
                commandRow(list, session, command, favorites.contains(command.text));
                any = true;
            }
        }
        if (!any) list.addView(text(mActivity.getString(R.string.terminow_keybar_filter_none), 13, R.color.terminow_text_secondary));
    }

    /** A command: a tap puts it on the prompt, a long press copies it, the star pins or unpins it. */
    private void commandRow(@NonNull LinearLayout content, @NonNull TerminalSession session, @NonNull TerminowKeyBarModel.Command command,
                            boolean favorite) {
        LinearLayout line = new LinearLayout(mActivity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        String name = command.argument.isEmpty() ? command.text : command.text + " " + command.argument;
        String description = describe(command.descriptionName);
        if (command.needsArgument()) description += " · " + mActivity.getString(R.string.terminow_keybar_argument_needed, command.argument);
        LinearLayout row = row(null, name, description, () -> typeCommand(session, command));
        row.setOnLongClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) mActivity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText(command.text, TerminowKeyBarModel.promptText(command)));
            mActivity.showToast(mActivity.getString(R.string.terminow_keybar_command_copied, command.text), false);
            return true;
        });
        line.addView(row, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton star = new ImageButton(mActivity);
        star.setImageResource(favorite ? R.drawable.ic_terminow_star : R.drawable.ic_terminow_star_outline);
        star.setImageTintList(ColorStateList.valueOf(favorite ? TerminowPremiumGate.PREMIUM_GOLD : color(R.color.terminow_text_secondary)));
        TypedValue ripple = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        star.setBackgroundResource(ripple.resourceId);
        String label = mActivity.getString(favorite ? R.string.terminow_keybar_unpin : R.string.terminow_keybar_pin, command.text);
        star.setContentDescription(label);
        TooltipCompat.setTooltipText(star, label);
        star.setOnClickListener(v -> {
            TerminowHome home = mActivity.getTerminowHome();
            if (home == null) return;
            TerminowStore store = home.getStore();
            store.setClaudeFavorites(TerminowKeyBarModel.toggleFavorite(store.getClaudeFavorites(), command.text));
            if (mContent != null && mPanelSession != null) fillClaude(mContent, mPanelSession);
        });
        line.addView(star, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Put a command on the prompt of Claude Code, with no Enter: the user sends it. */
    private void typeCommand(@NonNull TerminalSession session, @NonNull TerminowKeyBarModel.Command command) {
        if (!session.isRunning() || session != mActivity.getCurrentSession()) return;
        // CTRL or ALT that is on would change the characters
        ExtraKeysView keys = mActivity.getExtraKeysView();
        if (keys != null) keys.resetSpecialButtons();
        TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
        if (extraKeys != null) extraKeys.sendText(TerminowKeyBarModel.promptText(command));
    }

    private void sendKeys(@NonNull TerminalSession session, @NonNull String macro) {
        if (!session.isRunning() || session != mActivity.getCurrentSession()) return;
        ExtraKeysView keys = mActivity.getExtraKeysView();
        if (keys != null) keys.resetSpecialButtons();
        TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
        if (extraKeys != null) extraKeys.sendMacro(mActivity.getTerminalView(), macro);
    }

    /** The strings that say what commands and keys do, named in {@link TerminowKeyBarModel}; by reference, so that shrinking keeps them. */
    private static final Map<String, Integer> DESCRIPTIONS = new java.util.HashMap<>();
    static {
        DESCRIPTIONS.put("terminow_keybar_cmd_add_dir", R.string.terminow_keybar_cmd_add_dir);
        DESCRIPTIONS.put("terminow_keybar_cmd_agents", R.string.terminow_keybar_cmd_agents);
        DESCRIPTIONS.put("terminow_keybar_cmd_branch", R.string.terminow_keybar_cmd_branch);
        DESCRIPTIONS.put("terminow_keybar_cmd_btw", R.string.terminow_keybar_cmd_btw);
        DESCRIPTIONS.put("terminow_keybar_cmd_bug", R.string.terminow_keybar_cmd_bug);
        DESCRIPTIONS.put("terminow_keybar_cmd_cd", R.string.terminow_keybar_cmd_cd);
        DESCRIPTIONS.put("terminow_keybar_cmd_clear", R.string.terminow_keybar_cmd_clear);
        DESCRIPTIONS.put("terminow_keybar_cmd_compact", R.string.terminow_keybar_cmd_compact);
        DESCRIPTIONS.put("terminow_keybar_cmd_config", R.string.terminow_keybar_cmd_config);
        DESCRIPTIONS.put("terminow_keybar_cmd_context", R.string.terminow_keybar_cmd_context);
        DESCRIPTIONS.put("terminow_keybar_cmd_copy", R.string.terminow_keybar_cmd_copy);
        DESCRIPTIONS.put("terminow_keybar_cmd_debug", R.string.terminow_keybar_cmd_debug);
        DESCRIPTIONS.put("terminow_keybar_cmd_diff", R.string.terminow_keybar_cmd_diff);
        DESCRIPTIONS.put("terminow_keybar_cmd_doctor", R.string.terminow_keybar_cmd_doctor);
        DESCRIPTIONS.put("terminow_keybar_cmd_effort", R.string.terminow_keybar_cmd_effort);
        DESCRIPTIONS.put("terminow_keybar_cmd_exit", R.string.terminow_keybar_cmd_exit);
        DESCRIPTIONS.put("terminow_keybar_cmd_export", R.string.terminow_keybar_cmd_export);
        DESCRIPTIONS.put("terminow_keybar_cmd_fast", R.string.terminow_keybar_cmd_fast);
        DESCRIPTIONS.put("terminow_keybar_cmd_goal", R.string.terminow_keybar_cmd_goal);
        DESCRIPTIONS.put("terminow_keybar_cmd_help", R.string.terminow_keybar_cmd_help);
        DESCRIPTIONS.put("terminow_keybar_cmd_hooks", R.string.terminow_keybar_cmd_hooks);
        DESCRIPTIONS.put("terminow_keybar_cmd_init", R.string.terminow_keybar_cmd_init);
        DESCRIPTIONS.put("terminow_keybar_cmd_keybindings", R.string.terminow_keybar_cmd_keybindings);
        DESCRIPTIONS.put("terminow_keybar_cmd_login", R.string.terminow_keybar_cmd_login);
        DESCRIPTIONS.put("terminow_keybar_cmd_logout", R.string.terminow_keybar_cmd_logout);
        DESCRIPTIONS.put("terminow_keybar_cmd_mcp", R.string.terminow_keybar_cmd_mcp);
        DESCRIPTIONS.put("terminow_keybar_cmd_memory", R.string.terminow_keybar_cmd_memory);
        DESCRIPTIONS.put("terminow_keybar_cmd_model", R.string.terminow_keybar_cmd_model);
        DESCRIPTIONS.put("terminow_keybar_cmd_output_style", R.string.terminow_keybar_cmd_output_style);
        DESCRIPTIONS.put("terminow_keybar_cmd_permissions", R.string.terminow_keybar_cmd_permissions);
        DESCRIPTIONS.put("terminow_keybar_cmd_plan", R.string.terminow_keybar_cmd_plan);
        DESCRIPTIONS.put("terminow_keybar_cmd_plugin", R.string.terminow_keybar_cmd_plugin);
        DESCRIPTIONS.put("terminow_keybar_cmd_recap", R.string.terminow_keybar_cmd_recap);
        DESCRIPTIONS.put("terminow_keybar_cmd_release_notes", R.string.terminow_keybar_cmd_release_notes);
        DESCRIPTIONS.put("terminow_keybar_cmd_rename", R.string.terminow_keybar_cmd_rename);
        DESCRIPTIONS.put("terminow_keybar_cmd_resume", R.string.terminow_keybar_cmd_resume);
        DESCRIPTIONS.put("terminow_keybar_cmd_review", R.string.terminow_keybar_cmd_review);
        DESCRIPTIONS.put("terminow_keybar_cmd_rewind", R.string.terminow_keybar_cmd_rewind);
        DESCRIPTIONS.put("terminow_keybar_cmd_security_review", R.string.terminow_keybar_cmd_security_review);
        DESCRIPTIONS.put("terminow_keybar_cmd_simplify", R.string.terminow_keybar_cmd_simplify);
        DESCRIPTIONS.put("terminow_keybar_cmd_skills", R.string.terminow_keybar_cmd_skills);
        DESCRIPTIONS.put("terminow_keybar_cmd_status", R.string.terminow_keybar_cmd_status);
        DESCRIPTIONS.put("terminow_keybar_cmd_statusline", R.string.terminow_keybar_cmd_statusline);
        DESCRIPTIONS.put("terminow_keybar_cmd_tasks", R.string.terminow_keybar_cmd_tasks);
        DESCRIPTIONS.put("terminow_keybar_cmd_theme", R.string.terminow_keybar_cmd_theme);
        DESCRIPTIONS.put("terminow_keybar_cmd_tui", R.string.terminow_keybar_cmd_tui);
        DESCRIPTIONS.put("terminow_keybar_cmd_upgrade", R.string.terminow_keybar_cmd_upgrade);
        DESCRIPTIONS.put("terminow_keybar_cmd_usage", R.string.terminow_keybar_cmd_usage);
        DESCRIPTIONS.put("terminow_keybar_key_claude_esc", R.string.terminow_keybar_key_claude_esc);
        DESCRIPTIONS.put("terminow_keybar_key_claude_esc_esc", R.string.terminow_keybar_key_claude_esc_esc);
        DESCRIPTIONS.put("terminow_keybar_key_claude_mode", R.string.terminow_keybar_key_claude_mode);
        DESCRIPTIONS.put("terminow_keybar_key_claude_tab", R.string.terminow_keybar_key_claude_tab);
        DESCRIPTIONS.put("terminow_keybar_key_ctrl_c", R.string.terminow_keybar_key_ctrl_c);
        DESCRIPTIONS.put("terminow_keybar_key_ctrl_d", R.string.terminow_keybar_key_ctrl_d);
        DESCRIPTIONS.put("terminow_keybar_key_ctrl_l", R.string.terminow_keybar_key_ctrl_l);
        DESCRIPTIONS.put("terminow_keybar_key_ctrl_r", R.string.terminow_keybar_key_ctrl_r);
        DESCRIPTIONS.put("terminow_keybar_key_ctrl_z", R.string.terminow_keybar_key_ctrl_z);
        DESCRIPTIONS.put("terminow_keybar_key_end", R.string.terminow_keybar_key_end);
        DESCRIPTIONS.put("terminow_keybar_key_home", R.string.terminow_keybar_key_home);
        DESCRIPTIONS.put("terminow_keybar_key_pgdn", R.string.terminow_keybar_key_pgdn);
        DESCRIPTIONS.put("terminow_keybar_key_pgup", R.string.terminow_keybar_key_pgup);
    }

    @NonNull
    private String describe(@NonNull String name) {
        Integer id = DESCRIPTIONS.get(name);
        return id != null ? mActivity.getString(id) : "";
    }

    private void section(@NonNull LinearLayout content, @StringRes int label) {
        TextView view = text(mActivity.getString(label), 13, R.color.terminow_panel_accent);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(14), 0, dp(4));
        ViewCompat.setAccessibilityHeading(view, true);
        content.addView(view);
    }

    @NonNull
    private LinearLayout row(@Nullable LinearLayout content, @NonNull String name, @NonNull String description, @NonNull Runnable action) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        TypedValue ripple = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        TextView title = text(name, 15, R.color.terminow_text);
        title.setTypeface(Typeface.MONOSPACE);
        row.addView(title);
        if (!description.isEmpty()) row.addView(text(description, 12, R.color.terminow_text_secondary));
        row.setContentDescription(description.isEmpty() ? name : name + ", " + description);
        row.setOnClickListener(v -> {
            if (mDialog != null) mDialog.dismiss();
            action.run();
        });
        if (content != null) content.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    @NonNull
    private TextView text(@NonNull String value, int sp, int color) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(color));
        return view;
    }

    private int color(int id) {
        return ContextCompat.getColor(mActivity, id);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
