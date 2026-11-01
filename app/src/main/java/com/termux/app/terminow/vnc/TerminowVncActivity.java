package com.termux.app.terminow.vnc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.termux.R;
import com.termux.app.terminow.TerminowStore;

import java.util.ArrayList;
import java.util.List;

/**
 * The built-in VNC viewer: the desktop of a {@link TerminowVncSession} over the whole screen, a
 * toolbar that folds away (Back, Keyboard, Input mode, Fit screen, Paste, Disconnect) and the keys
 * a phone keyboard lacks. Authentication happens in the RFB handshake with the host's saved
 * password, so there are no certificate or credential prompts here.
 *
 * <p>The connection belongs to the session, not to this screen, so rotating, folding or leaving it
 * keeps the connection while the app runs. Leaving never disconnects; Disconnect closes only this
 * session's socket.</p>
 */
public class TerminowVncActivity extends Activity implements TerminowVncSession.Ui, TerminowVncView.Listener {

    public static final String EXTRA_SESSION = "com.terminow.vnc.SESSION";

    public static void show(@NonNull Context context, @NonNull TerminowVncSession session) {
        Intent intent = new Intent(context, TerminowVncActivity.class).putExtra(EXTRA_SESSION, session.id);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Nullable private TerminowVncSession mSession;
    private TerminowVncView mDesktop;
    private LinearLayout mToolbar;
    private TextView mToolbarHandle;
    private HorizontalScrollView mKeys;
    private LinearLayout mOverlay;
    private TextView mOverlayTitle;
    private TextView mOverlayText;
    private ProgressBar mOverlaySpinner;
    private LinearLayout mOverlayButtons;
    private TextView mModeButton;
    private boolean mKeyboardShown;
    private final List<Integer> mHeld = new ArrayList<>();
    private final List<TextView> mModifierViews = new ArrayList<>();
    @Nullable private String mLastClipboard;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra(EXTRA_SESSION);
        mSession = id != null ? TerminowVncSession.get(id) : null;
        if (mSession == null) {
            Toast.makeText(this, R.string.terminow_vnc_gone, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat insets = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insets.hide(WindowInsetsCompat.Type.systemBars());
        insets.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        mDesktop = new TerminowVncView(this);
        mDesktop.setSession(mSession);
        mDesktop.setListener(this);
        column.addView(mDesktop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        mKeys = extraKeys();
        mKeys.setVisibility(View.GONE);
        column.addView(mKeys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mToolbar = toolbar();
        root.addView(mToolbar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        mToolbarHandle = chip(getString(R.string.terminow_vnc_tools));
        mToolbarHandle.setOnClickListener(v -> setToolbarShown(true));
        mToolbarHandle.setVisibility(View.GONE);
        FrameLayout.LayoutParams handle = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        handle.topMargin = dp(6);
        root.addView(mToolbarHandle, handle);

        mOverlay = overlay();
        root.addView(mOverlay, new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(460)),
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        mSession.attach(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mSession != null) mSession.attach(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSession != null) mSession.attach(null);
    }

    @Override
    public void onBackPressed() {
        leave();
    }

    /** Back to TermiNow: the connection stays and can be resumed. */
    private void leave() {
        if (mSession != null && mSession.isOpen())
            Toast.makeText(this, R.string.terminow_vnc_still_connected, Toast.LENGTH_SHORT).show();
        finish();
    }

    // ---------------------------------------------------------------------------------------
    // The session

    @Override
    public void onStateChanged(@NonNull TerminowVncSession session) {
        if (session != mSession) return;
        TerminowVncSession.State state = session.getState();
        boolean connected = state == TerminowVncSession.State.CONNECTED;
        if (connected) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mOverlayButtons.removeAllViews();
        switch (state) {
            case CONNECTING:
                showOverlay(getString(R.string.terminow_vnc_connecting, session.target.title),
                    session.target.address + ":" + session.target.port, true);
                mOverlayButtons.addView(button(getString(android.R.string.cancel), false, this::disconnect));
                break;
            case AUTHENTICATING:
                showOverlay(getString(R.string.terminow_vnc_authenticating), session.target.title, true);
                mOverlayButtons.addView(button(getString(android.R.string.cancel), false, this::disconnect));
                break;
            case CONNECTED:
                mOverlay.setVisibility(View.GONE);
                break;
            case FAILED:
                showOverlay(getString(R.string.terminow_vnc_failed), session.getError(), false);
                mOverlayButtons.addView(button(getString(R.string.terminow_vnc_close), false, this::finish));
                mOverlayButtons.addView(button(getString(R.string.terminow_vnc_reconnect), true, this::reconnect));
                break;
            default:
                showOverlay(getString(R.string.terminow_vnc_disconnected),
                    session.getError().isEmpty() ? getString(R.string.terminow_vnc_disconnected_text) : session.getError(), false);
                mOverlayButtons.addView(button(getString(R.string.terminow_vnc_close), false, this::finish));
                mOverlayButtons.addView(button(getString(R.string.terminow_vnc_reconnect), true, this::reconnect));
                break;
        }
    }

    @Override
    public void onDesktop(@NonNull Bitmap bitmap) {
        mDesktop.setBitmap(bitmap);
    }

    @Override
    public void onFrame(int x, int y, int width, int height) {
        mDesktop.onFrame(x, y, width, height);
    }

    @Override
    public void onBell() {
        mDesktop.performHapticFeedback(View.HAPTIC_FEEDBACK_ENABLED);
    }

    @Override
    public void onCutText(@NonNull String text) {
        if (text.isEmpty() || text.equals(mLastClipboard)) return;
        mLastClipboard = text;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("VNC", text));
    }

    // ---------------------------------------------------------------------------------------
    // Actions

    private void disconnect() {
        TerminowVncSession session = mSession;
        if (session != null) session.disconnect();
    }

    private void confirmDisconnect() {
        new AlertDialog.Builder(this, R.style.TerminowAlertDialog)
            .setMessage(getString(R.string.terminow_vnc_disconnect_confirm, mSession != null ? mSession.target.title : ""))
            .setPositiveButton(R.string.terminow_vnc_disconnect, (d, w) -> { disconnect(); finish(); })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Connect again to the saved host with its current settings: a new connection. */
    private void reconnect() {
        TerminowVncSession old = mSession;
        if (old == null) return;
        TerminowStore store = new TerminowStore(this);
        TerminowStore.Host host = TerminowStore.findHost(store.getHosts(), old.target.hostKey);
        if (host == null) {
            Toast.makeText(this, R.string.terminow_vnc_host_gone, Toast.LENGTH_LONG).show();
            return;
        }
        TerminowVncSession.Target target = TerminowVncBridge.vncTarget(host);
        old.attach(null);
        mSession = TerminowVncSession.start(target);
        mDesktop.setSession(mSession);
        mDesktop.setBitmap(null);
        mSession.attach(this);
    }

    private void toggleKeyboard() {
        if (mSession != null && mSession.isViewOnly()) {
            Toast.makeText(this, R.string.terminow_vnc_view_only_note, Toast.LENGTH_SHORT).show();
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        mKeyboardShown = !mKeyboardShown;
        mDesktop.requestFocus();
        if (mKeyboardShown) imm.showSoftInput(mDesktop, InputMethodManager.SHOW_IMPLICIT);
        else imm.hideSoftInputFromWindow(mDesktop.getWindowToken(), 0);
        mKeys.setVisibility(mKeyboardShown ? View.VISIBLE : View.GONE);
    }

    private void toggleMode() {
        mDesktop.setPointerMode(!mDesktop.isPointerMode());
        mModeButton.setText(mDesktop.isPointerMode() ? R.string.terminow_vnc_mode_pointer : R.string.terminow_vnc_mode_touch);
        Toast.makeText(this, mDesktop.isPointerMode() ? R.string.terminow_vnc_mode_pointer_help : R.string.terminow_vnc_mode_touch_help,
            Toast.LENGTH_LONG).show();
    }

    /** Type the phone's clipboard into the remote desktop. */
    private void paste() {
        TerminowVncSession session = mSession;
        if (session == null || session.isViewOnly()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        if (text == null || text.length() == 0) {
            Toast.makeText(this, R.string.terminow_vnc_clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        session.text(text);
        Toast.makeText(this, R.string.terminow_vnc_clipboard_sent, Toast.LENGTH_SHORT).show();
    }

    private void setToolbarShown(boolean shown) {
        mToolbar.setVisibility(shown ? View.VISIBLE : View.GONE);
        mToolbarHandle.setVisibility(shown ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onTouchStarted() {
        if (mToolbar.getVisibility() == View.VISIBLE) setToolbarShown(false);
    }

    @Override
    public void onKeySent() {
        releaseHeld();
    }

    // ---------------------------------------------------------------------------------------
    // The extra keys

    private void tapExtra(int keysym) {
        mDesktop.tapKeysym(keysym);
        releaseHeld();
    }

    private void toggleHeld(int keysym, @NonNull TextView view) {
        if (mHeld.contains(keysym)) {
            mHeld.remove((Integer) keysym);
            mDesktop.holdKeysym(keysym, false);
            view.setBackground(keyBackground(false));
        } else {
            mHeld.add(keysym);
            mDesktop.holdKeysym(keysym, true);
            view.setBackground(keyBackground(true));
        }
    }

    private void releaseHeld() {
        if (mHeld.isEmpty()) return;
        for (Integer keysym : mHeld) mDesktop.holdKeysym(keysym, false);
        mHeld.clear();
        for (TextView view : mModifierViews) view.setBackground(keyBackground(false));
    }

    @NonNull
    private HorizontalScrollView extraKeys() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.terminow_keys_bar));
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, dp(4), 0);
        Object[][] modifiers = {{"Ctrl", TerminowVncKeys.XK_Control_L}, {"Alt", TerminowVncKeys.XK_Alt_L},
            {"Shift", TerminowVncKeys.XK_Shift_L}, {"Super", TerminowVncKeys.XK_Super_L}};
        for (Object[] modifier : modifiers) {
            TextView key = key((String) modifier[0]);
            int keysym = (Integer) modifier[1];
            key.setOnClickListener(v -> toggleHeld(keysym, key));
            mModifierViews.add(key);
            row.addView(key, keyParams());
        }
        Object[][] keys = {{"Tab", TerminowVncKeys.XK_Tab}, {"Esc", TerminowVncKeys.XK_Escape}, {"←", TerminowVncKeys.XK_Left},
            {"↑", TerminowVncKeys.XK_Up}, {"↓", TerminowVncKeys.XK_Down}, {"→", TerminowVncKeys.XK_Right},
            {"Home", TerminowVncKeys.XK_Home}, {"End", TerminowVncKeys.XK_End}, {"PgUp", TerminowVncKeys.XK_Page_Up},
            {"PgDn", TerminowVncKeys.XK_Page_Down}, {"Ins", TerminowVncKeys.XK_Insert}, {"Del", TerminowVncKeys.XK_Delete}};
        for (Object[] entry : keys) {
            TextView key = key((String) entry[0]);
            int keysym = (Integer) entry[1];
            key.setOnClickListener(v -> tapExtra(keysym));
            row.addView(key, keyParams());
        }
        for (int i = 0; i < 12; i++) {
            TextView key = key("F" + (i + 1));
            int keysym = TerminowVncKeys.XK_F1 + i;
            key.setOnClickListener(v -> tapExtra(keysym));
            row.addView(key, keyParams());
        }
        scroll.addView(row);
        return scroll;
    }

    @NonNull
    private TextView key(@NonNull String label) {
        TextView key = text(label, 14, ContextCompat.getColor(this, R.color.terminow_keys_text));
        key.setGravity(Gravity.CENTER);
        key.setTypeface(Typeface.DEFAULT_BOLD);
        key.setMinWidth(dp(48));
        key.setPadding(dp(10), 0, dp(10), 0);
        key.setBackground(keyBackground(false));
        return key;
    }

    @NonNull
    private GradientDrawable keyBackground(boolean held) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(8));
        shape.setColor(held ? ContextCompat.getColor(this, R.color.terminow_quick_primary) : ContextCompat.getColor(this, R.color.terminow_keys_active));
        return shape;
    }

    @NonNull
    private LinearLayout.LayoutParams keyParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        params.setMarginEnd(dp(4));
        return params;
    }

    // ---------------------------------------------------------------------------------------
    // Views

    @NonNull
    private LinearLayout toolbar() {
        boolean viewOnly = mSession != null && mSession.isViewOnly();
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xE61B2236);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(toolButton(getString(R.string.terminow_vnc_back), this::leave));
        if (!viewOnly) buttons.addView(toolButton(getString(R.string.terminow_vnc_keyboard), this::toggleKeyboard));
        mModeButton = toolButton(getString(R.string.terminow_vnc_mode_pointer), this::toggleMode);
        buttons.addView(mModeButton);
        buttons.addView(toolButton(getString(R.string.terminow_vnc_fit), () -> mDesktop.fitScreen()));
        if (!viewOnly) buttons.addView(toolButton(getString(R.string.terminow_vnc_paste), this::paste));
        buttons.addView(toolButton(getString(R.string.terminow_vnc_disconnect), this::confirmDisconnect));
        scroll.addView(buttons);
        bar.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView hide = toolButton("▴", () -> setToolbarShown(false));
        hide.setContentDescription(getString(R.string.terminow_vnc_hide_tools));
        bar.addView(hide);
        return bar;
    }

    @NonNull
    private TextView toolButton(@NonNull String label, @NonNull Runnable onClick) {
        TextView button = text(label, 14, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setMinWidth(dp(44));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    @NonNull
    private TextView chip(@NonNull String label) {
        TextView chip = text(label, 13, Color.WHITE);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(18));
        shape.setColor(0xB31B2236);
        chip.setBackground(shape);
        return chip;
    }

    @NonNull
    private LinearLayout overlay() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(16));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(16));
        shape.setColor(ContextCompat.getColor(this, R.color.terminow_quick_card));
        card.setBackground(shape);
        mOverlaySpinner = new ProgressBar(this);
        card.addView(mOverlaySpinner, new LinearLayout.LayoutParams(dp(36), dp(36)));
        mOverlayTitle = text("", 18, Color.WHITE);
        mOverlayTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mOverlayTitle.setGravity(Gravity.CENTER);
        mOverlayTitle.setPadding(0, dp(12), 0, dp(4));
        card.addView(mOverlayTitle);
        mOverlayText = text("", 14, ContextCompat.getColor(this, R.color.terminow_text_secondary));
        mOverlayText.setGravity(Gravity.CENTER);
        mOverlayText.setTextIsSelectable(true);
        card.addView(mOverlayText);
        mOverlayButtons = new LinearLayout(this);
        mOverlayButtons.setGravity(Gravity.END);
        mOverlayButtons.setPadding(0, dp(14), 0, 0);
        card.addView(mOverlayButtons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private void showOverlay(@NonNull String title, @NonNull String text, boolean busy) {
        mOverlay.setVisibility(View.VISIBLE);
        mOverlayTitle.setText(title);
        mOverlayText.setText(text);
        mOverlaySpinner.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    @NonNull
    private TextView button(@NonNull String label, boolean primary, @NonNull Runnable onClick) {
        TextView button = text(label, 14, Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(primary ? ContextCompat.getColor(this, R.color.terminow_quick_primary) : 0x00000000);
        button.setBackground(shape);
        button.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        params.setMarginStart(dp(8));
        button.setLayoutParams(params);
        return button;
    }

    @NonNull
    private TextView text(@NonNull String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
