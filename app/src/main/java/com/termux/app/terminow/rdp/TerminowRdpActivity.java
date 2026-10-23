package com.termux.app.terminow.rdp;

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
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.termux.R;
import com.termux.app.terminow.TerminowRdpBridge;
import com.termux.app.terminow.TerminowStore;

import java.util.ArrayList;
import java.util.List;

/**
 * The built-in remote desktop: the desktop of a {@link TerminowRdpSession} over the whole
 * screen, a toolbar that folds away (Back, Keyboard, Mouse mode, Fit screen, Clipboard,
 * Ctrl+Alt+Delete, Disconnect), and the keys that a phone keyboard has not.
 *
 * The connection belongs to the session, not to this screen: rotating, folding or leaving it
 * keeps the connection while the app runs. It can not survive Android ending the app.
 */
public class TerminowRdpActivity extends Activity implements TerminowRdpSession.Ui, TerminowRdpView.Listener {

    public static final String EXTRA_SESSION = "com.terminow.rdp.SESSION";

    /** Show a connection that runs. */
    public static void show(@NonNull Context context, @NonNull TerminowRdpSession session) {
        Intent intent = new Intent(context, TerminowRdpActivity.class).putExtra(EXTRA_SESSION, session.id);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Nullable private TerminowRdpSession mSession;
    private TerminowRdpView mDesktop;
    private LinearLayout mToolbar;
    private TextView mToolbarHandle;
    private HorizontalScrollView mKeys;
    private LinearLayout mOverlay;
    private TextView mOverlayTitle;
    private TextView mOverlayText;
    private ProgressBar mOverlaySpinner;
    private LinearLayout mOverlayButtons;
    private TextView mBanner;
    private TextView mModeButton;
    private boolean mKeyboardShown;
    /** Ctrl, Alt, Shift and Win of the extra keys that are held for the next key. */
    private final List<Integer> mHeld = new ArrayList<>();
    private final List<TextView> mModifierViews = new ArrayList<>();
    @Nullable private ConnectivityManager.NetworkCallback mNetworkCallback;
    @Nullable private String mLastClipboard;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra(EXTRA_SESSION);
        mSession = id != null ? TerminowRdpSession.get(id) : null;
        if (mSession == null) {
            // The app was ended since: the connection is gone with it
            Toast.makeText(this, R.string.terminow_rdp_gone, Toast.LENGTH_LONG).show();
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
        mDesktop = new TerminowRdpView(this);
        mDesktop.setSession(mSession);
        mDesktop.setListener(this);
        column.addView(mDesktop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        mKeys = extraKeys();
        mKeys.setVisibility(View.GONE);
        column.addView(mKeys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mToolbar = toolbar();
        root.addView(mToolbar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        mToolbarHandle = chip(getString(R.string.terminow_rdp_tools));
        mToolbarHandle.setOnClickListener(v -> setToolbarShown(true));
        mToolbarHandle.setVisibility(View.GONE);
        FrameLayout.LayoutParams handle = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        handle.topMargin = dp(6);
        root.addView(mToolbarHandle, handle);

        mBanner = text("", 13, Color.WHITE);
        mBanner.setBackgroundColor(0xCC8A5A00);
        mBanner.setPadding(dp(14), dp(8), dp(14), dp(8));
        mBanner.setGravity(Gravity.CENTER);
        mBanner.setVisibility(View.GONE);
        FrameLayout.LayoutParams banner = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(mBanner, banner);

        mOverlay = overlay();
        root.addView(mOverlay, new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(460)),
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        mSession.attach(this);
        watchNetwork();
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
        if (mNetworkCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(mNetworkCallback);
            } catch (RuntimeException e) {
                // Not registered
            }
        }
    }

    @Override
    public void onBackPressed() {
        leave();
    }

    /** Back to TermiNow: the connection stays, and is resumed from Quick Launch. */
    private void leave() {
        if (mSession != null && mSession.isOpen())
            Toast.makeText(this, R.string.terminow_rdp_still_connected, Toast.LENGTH_SHORT).show();
        finish();
    }

    // ---------------------------------------------------------------------------------------
    // The session

    @Override
    public void onStateChanged(@NonNull TerminowRdpSession session) {
        if (session != mSession) return;
        TerminowRdpSession.State state = session.getState();
        boolean connected = state == TerminowRdpSession.State.CONNECTED;
        if (connected) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mOverlayButtons.removeAllViews();
        switch (state) {
            case STARTING_TUNNEL:
                showOverlay(getString(R.string.terminow_rdp_tunnel, session.target.tunnelTitle), session.target.title, true);
                mOverlayButtons.addView(button(getString(android.R.string.cancel), false, this::disconnect));
                break;
            case CONNECTING:
                showOverlay(getString(R.string.terminow_rdp_connecting, session.target.title),
                    session.target.address + ":" + session.target.port, true);
                mOverlayButtons.addView(button(getString(android.R.string.cancel), false, this::disconnect));
                break;
            case RECONNECTING:
                showOverlay(getString(R.string.terminow_rdp_reconnecting), getString(R.string.terminow_rdp_reconnecting_text), true);
                mOverlayButtons.addView(button(getString(R.string.terminow_rdp_disconnect), false, this::disconnect));
                break;
            case CONNECTED:
                mOverlay.setVisibility(View.GONE);
                break;
            case FAILED:
                showOverlay(getString(R.string.terminow_rdp_failed), describeError(session.getError()), false);
                mOverlayButtons.addView(button(getString(R.string.terminow_rdp_close), false, this::finish));
                mOverlayButtons.addView(button(getString(R.string.terminow_rdp_reconnect), true, this::reconnect));
                break;
            default:
                showOverlay(getString(R.string.terminow_rdp_disconnected), session.getError().isEmpty() ? getString(R.string.terminow_rdp_disconnected_text) :
                    describeError(session.getError()), false);
                mOverlayButtons.addView(button(getString(R.string.terminow_rdp_close), false, this::finish));
                mOverlayButtons.addView(button(getString(R.string.terminow_rdp_reconnect), true, this::reconnect));
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
    public void askCertificate(@NonNull TerminowRdpSession.Certificate certificate, @NonNull java.util.function.IntConsumer answer) {
        TerminowRdpSession session = mSession;
        String shown = session != null && !session.target.tunnelCommand.isEmpty() ?
            getString(R.string.terminow_rdp_cert_tunnel_host, session.target.address, session.target.port, session.target.tunnelTitle) :
            certificate.host + ":" + certificate.port;
        StringBuilder details = new StringBuilder();
        details.append(getString(certificate.changed ? R.string.terminow_rdp_cert_changed_message : R.string.terminow_rdp_cert_untrusted_message, shown));
        details.append("\n\n").append(getString(R.string.terminow_rdp_cert_common_name)).append(": ").append(certificate.commonName);
        details.append("\n").append(getString(R.string.terminow_rdp_cert_subject)).append(": ").append(certificate.subject);
        details.append("\n").append(getString(R.string.terminow_rdp_cert_issuer)).append(": ").append(certificate.issuer);
        details.append("\n").append(getString(R.string.terminow_rdp_cert_fingerprint)).append(":\n").append(certificate.fingerprint);
        if (certificate.changed) {
            details.append("\n\n").append(getString(R.string.terminow_rdp_cert_before));
            details.append("\n").append(getString(R.string.terminow_rdp_cert_subject)).append(": ").append(certificate.oldSubject);
            details.append("\n").append(getString(R.string.terminow_rdp_cert_issuer)).append(": ").append(certificate.oldIssuer);
            details.append("\n").append(getString(R.string.terminow_rdp_cert_fingerprint)).append(":\n").append(certificate.oldFingerprint);
        }
        TextView body = text(details.toString(), 14, ContextCompat.getColor(this, R.color.terminow_text));
        body.setTextIsSelectable(true);
        body.setPadding(dp(22), dp(8), dp(22), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        boolean[] answered = {false};
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.TerminowAlertDialog)
            .setTitle(certificate.changed ? R.string.terminow_rdp_cert_changed_title : R.string.terminow_rdp_cert_untrusted_title)
            .setView(scroll)
            .setCancelable(false)
            .setNegativeButton(R.string.terminow_rdp_cert_refuse, (d, w) -> {
                answered[0] = true;
                answer.accept(0);
            })
            .setNeutralButton(R.string.terminow_rdp_cert_once, (d, w) -> {
                answered[0] = true;
                answer.accept(2);
            })
            .setPositiveButton(R.string.terminow_rdp_cert_remember, (d, w) -> {
                answered[0] = true;
                answer.accept(1);
            })
            .create();
        dialog.setOnDismissListener(d -> {
            if (!answered[0]) answer.accept(0);
        });
        dialog.show();
    }

    @Override
    public void askCredentials(@NonNull String username, @NonNull String domain, @NonNull java.util.function.Consumer<String[]> answer) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(6), dp(22), 0);
        EditText user = field(getString(R.string.terminow_rdp_username), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        user.setText(username);
        EditText dom = field(getString(R.string.terminow_rdp_domain), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        dom.setText(domain);
        EditText password = field(getString(R.string.terminow_rdp_password), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox save = new CheckBox(this);
        save.setText(R.string.terminow_rdp_save_credentials);
        save.setTextColor(ContextCompat.getColor(this, R.color.terminow_text));
        form.addView(user);
        form.addView(dom);
        form.addView(password);
        form.addView(save);
        boolean[] answered = {false};
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_rdp_credentials_title)
            .setView(form)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, (d, w) -> {
                answered[0] = true;
                answer.accept(null);
            })
            .setPositiveButton(R.string.terminow_rdp_sign_in, (d, w) -> {
                answered[0] = true;
                String[] value = {user.getText().toString().trim(), dom.getText().toString().trim(), password.getText().toString()};
                TerminowRdpSession session = mSession;
                if (save.isChecked() && session != null &&
                    !TerminowRdpBridge.saveCredentials(this, session.target.hostKey, value[0], value[1], value[2]))
                    Toast.makeText(this, R.string.terminow_rdp_not_saved, Toast.LENGTH_LONG).show();
                answer.accept(value);
            })
            .create();
        dialog.setOnDismissListener(d -> {
            if (!answered[0]) answer.accept(null);
        });
        dialog.show();
    }

    @Override
    public void onRemoteClipboard(@NonNull String text) {
        if (text.equals(mLastClipboard)) return;
        mLastClipboard = text;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Windows", text));
    }

    // ---------------------------------------------------------------------------------------
    // Actions

    private void disconnect() {
        TerminowRdpSession session = mSession;
        if (session != null) session.disconnect();
    }

    private void confirmDisconnect() {
        new AlertDialog.Builder(this, R.style.TerminowAlertDialog)
            .setMessage(getString(R.string.terminow_rdp_disconnect_confirm, mSession != null ? mSession.target.title : ""))
            .setPositiveButton(R.string.terminow_rdp_disconnect, (d, w) -> {
                disconnect();
                finish();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Connect again to the saved host, with what it has now: a new connection, not the old one resumed. */
    private void reconnect() {
        TerminowRdpSession old = mSession;
        if (old == null) return;
        TerminowStore store = new TerminowStore(this);
        List<TerminowStore.Host> hosts = store.getHosts();
        TerminowStore.Host host = TerminowStore.findHost(hosts, old.target.hostKey);
        if (host == null) {
            Toast.makeText(this, R.string.terminow_rdp_host_gone, Toast.LENGTH_LONG).show();
            return;
        }
        TerminowRdpSession.Target target;
        try {
            target = TerminowRdpBridge.rdpTarget(store, host, hosts, old.target.width, old.target.height);
        } catch (TerminowRdpBridge.TargetException e) {
            Toast.makeText(this, getString(R.string.terminow_rdp_host_invalid, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        old.attach(null);
        mSession = TerminowRdpSession.start(this, target);
        mDesktop.setSession(mSession);
        mDesktop.setBitmap(null);
        mSession.attach(this);
    }

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        mKeyboardShown = !mKeyboardShown;
        mDesktop.requestFocus();
        if (mKeyboardShown) imm.showSoftInput(mDesktop, InputMethodManager.SHOW_IMPLICIT);
        else imm.hideSoftInputFromWindow(mDesktop.getWindowToken(), 0);
        mKeys.setVisibility(mKeyboardShown ? View.VISIBLE : View.GONE);
    }

    private void toggleMode() {
        mDesktop.setPointerMode(!mDesktop.isPointerMode());
        mModeButton.setText(mDesktop.isPointerMode() ? R.string.terminow_rdp_mode_pointer : R.string.terminow_rdp_mode_touch);
        Toast.makeText(this, mDesktop.isPointerMode() ? R.string.terminow_rdp_mode_pointer_help : R.string.terminow_rdp_mode_touch_help,
            Toast.LENGTH_LONG).show();
    }

    /** Text of the phone to Windows: only when the clipboard is shared for the host. */
    private void clipboard() {
        TerminowRdpSession session = mSession;
        if (session == null) return;
        if (!session.target.clipboard) {
            new AlertDialog.Builder(this, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_rdp_clipboard)
                .setMessage(R.string.terminow_rdp_clipboard_off)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        if (text == null || text.length() == 0) {
            Toast.makeText(this, R.string.terminow_rdp_clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        session.setClipboard(text.toString());
        Toast.makeText(this, R.string.terminow_rdp_clipboard_sent, Toast.LENGTH_SHORT).show();
    }

    private void ctrlAltDelete() {
        TerminowRdpSession session = mSession;
        if (session == null) return;
        session.sendKey(TerminowRdpKeys.VK_LCONTROL, true);
        session.sendKey(TerminowRdpKeys.VK_LMENU, true);
        session.sendKey(TerminowRdpKeys.VK_DELETE, true);
        session.sendKey(TerminowRdpKeys.VK_DELETE, false);
        session.sendKey(TerminowRdpKeys.VK_LMENU, false);
        session.sendKey(TerminowRdpKeys.VK_LCONTROL, false);
    }

    private void setToolbarShown(boolean shown) {
        mToolbar.setVisibility(shown ? View.VISIBLE : View.GONE);
        mToolbarHandle.setVisibility(shown ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onTouchStarted() {
        // The desktop gets all the room while it is used
        if (mToolbar.getVisibility() == View.VISIBLE) setToolbarShown(false);
    }

    @Override
    public void onKeySent() {
        releaseHeld();
    }

    /** The network changed: the engine reconnects by itself, this says why the desktop waits. */
    private void watchNetwork() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return;
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(@NonNull Network network) {
                runOnUiThread(() -> {
                    if (manager.getActiveNetwork() != null) return;
                    mBanner.setText(R.string.terminow_rdp_network_lost);
                    mBanner.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onAvailable(@NonNull Network network) {
                runOnUiThread(() -> mBanner.setVisibility(View.GONE));
            }
        };
        try {
            manager.registerDefaultNetworkCallback(mNetworkCallback);
        } catch (RuntimeException e) {
            mNetworkCallback = null;
        }
    }

    /** "NAME|text" of the engine as what it means for the user. */
    @NonNull
    private String describeError(@NonNull String error) {
        String name = error.contains("|") ? error.substring(0, error.indexOf('|')) : error;
        String detail = error.contains("|") ? error.substring(error.indexOf('|') + 1) : "";
        int res;
        if (name.contains("AUTHENTICATION") || name.contains("LOGON_FAILURE") || name.contains("WRONG_PASSWORD") ||
            name.contains("ACCOUNT")) res = R.string.terminow_rdp_error_auth;
        else if (name.contains("DNS") || name.contains("CONNECT_FAILED") || name.contains("TRANSPORT")) res = R.string.terminow_rdp_error_network;
        else if (name.contains("CANCELLED")) res = R.string.terminow_rdp_error_cancelled;
        else if (name.contains("TLS") || name.contains("SECURITY") || name.contains("NEGO")) res = R.string.terminow_rdp_error_security;
        else if (name.equals("TUNNEL")) res = R.string.terminow_rdp_error_tunnel;
        else if (name.equals("NO_ENGINE")) res = R.string.terminow_rdp_error_engine;
        else res = R.string.terminow_rdp_error_other;
        String text = getString(res);
        if (!detail.isEmpty()) text += "\n\n" + detail;
        if (!name.isEmpty() && !name.equals("TUNNEL") && !name.equals("NO_ENGINE")) text += "\n(" + name + ")";
        return text;
    }

    // ---------------------------------------------------------------------------------------
    // The extra keys

    private void tapExtra(int virtualKey) {
        mDesktop.tapKey(virtualKey);
        releaseHeld();
    }

    private void toggleHeld(int virtualKey, @NonNull TextView view) {
        TerminowRdpSession session = mSession;
        if (session == null) return;
        if (mHeld.contains(virtualKey)) {
            mHeld.remove((Integer) virtualKey);
            session.sendKey(virtualKey, false);
            view.setSelected(false);
            view.setBackground(keyBackground(false));
        } else {
            mHeld.add(virtualKey);
            session.sendKey(virtualKey, true);
            view.setBackground(keyBackground(true));
        }
    }

    private void releaseHeld() {
        TerminowRdpSession session = mSession;
        if (session == null || mHeld.isEmpty()) return;
        for (Integer key : mHeld) session.sendKey(key, false);
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
        int[][] modifiers = {{R.string.terminow_rdp_key_ctrl, TerminowRdpKeys.VK_LCONTROL}, {R.string.terminow_rdp_key_alt, TerminowRdpKeys.VK_LMENU},
            {R.string.terminow_rdp_key_shift, TerminowRdpKeys.VK_LSHIFT}, {R.string.terminow_rdp_key_win, TerminowRdpKeys.VK_LWIN}};
        for (int[] modifier : modifiers) {
            TextView key = key(getString(modifier[0]));
            key.setOnClickListener(v -> toggleHeld(modifier[1], key));
            mModifierViews.add(key);
            row.addView(key, keyParams());
        }
        Object[][] keys = {{"Tab", TerminowRdpKeys.VK_TAB}, {"Esc", TerminowRdpKeys.VK_ESCAPE}, {"←", TerminowRdpKeys.VK_LEFT},
            {"↑", TerminowRdpKeys.VK_UP}, {"↓", TerminowRdpKeys.VK_DOWN}, {"→", TerminowRdpKeys.VK_RIGHT}, {"Home", TerminowRdpKeys.VK_HOME},
            {"End", TerminowRdpKeys.VK_END}, {"PgUp", TerminowRdpKeys.VK_PRIOR}, {"PgDn", TerminowRdpKeys.VK_NEXT},
            {"Del", TerminowRdpKeys.VK_DELETE}};
        for (Object[] entry : keys) {
            TextView key = key((String) entry[0]);
            int code = (Integer) entry[1];
            key.setOnClickListener(v -> tapExtra(code));
            row.addView(key, keyParams());
        }
        for (int i = 0; i < 12; i++) {
            TextView key = key("F" + (i + 1));
            int code = TerminowRdpKeys.VK_F1 + i;
            key.setOnClickListener(v -> tapExtra(code));
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
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xE61B2236);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(toolButton(getString(R.string.terminow_rdp_back), this::leave));
        buttons.addView(toolButton(getString(R.string.terminow_rdp_keyboard), this::toggleKeyboard));
        mModeButton = toolButton(getString(R.string.terminow_rdp_mode_pointer), this::toggleMode);
        buttons.addView(mModeButton);
        buttons.addView(toolButton(getString(R.string.terminow_rdp_fit), () -> mDesktop.fitScreen()));
        buttons.addView(toolButton(getString(R.string.terminow_rdp_clipboard), this::clipboard));
        buttons.addView(toolButton(getString(R.string.terminow_rdp_ctrl_alt_del), this::ctrlAltDelete));
        buttons.addView(toolButton(getString(R.string.terminow_rdp_disconnect), this::confirmDisconnect));
        scroll.addView(buttons);
        bar.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView hide = toolButton("▴", () -> setToolbarShown(false));
        hide.setContentDescription(getString(R.string.terminow_rdp_hide_tools));
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
    private EditText field(@NonNull String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setTextColor(ContextCompat.getColor(this, R.color.terminow_text));
        field.setHintTextColor(ContextCompat.getColor(this, R.color.terminow_text_secondary));
        return field;
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
