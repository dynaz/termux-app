package com.termux.app.terminow;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The connection and security UI of a terminal tab: a compact animated connecting panel, and a
 * yellow non-blocking banner plus a shield when a security advisory is seen. State is kept per
 * {@link TerminalSession} so a banner never shows on the wrong tab; only the current session's
 * panel, banner and shield are shown. Connection status and security status are kept separate.
 *
 * <p>Nothing here changes the SSH configuration, closes a connection, or decides success on its own:
 * {@link TerminowHome#watchConnection} drives the phase from real output signals and feeds output to
 * the {@link TerminowSshSecurity} detector. The host-key confirmation and host-key-changed stop are
 * left to the existing flow; this only reports the post-quantum advisory.
 */
public class TerminowConnectionUi {

    /** The canonical OpenSSH advisory shown in the details dialog; the live one stays in the terminal. */
    private static final String PQ_ADVISORY =
        "** WARNING: connection is not using a post-quantum key exchange algorithm.\n"
            + "This session may be vulnerable to \"store now, decrypt later\" attacks.\n"
            + "The server may need to be upgraded. See https://www.openssh.com/pq.html for details.";
    private static final String PQ_URL = "https://www.openssh.com/pq.html";

    private final TermuxActivity activity;
    private final FrameLayout overlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<TerminalSession, State> states = new WeakHashMap<>();
    @Nullable private TerminalSession current;

    // Reused views, added to the overlay lazily.
    @Nullable private View panelView;
    @Nullable private TerminowConnectingView anim;
    @Nullable private TextView panelTitle, panelSub, panelStatus;
    @Nullable private TextView cancelButton, retryButton, editButton;
    @Nullable private View bannerView;
    @Nullable private ImageView shieldView;

    private static final class State {
        @NonNull TerminowConnectionPhase phase = TerminowConnectionPhase.CONNECTING;
        final TerminowSshSecurity security = new TerminowSshSecurity();
        boolean active;            // a connect attempt is in progress (panel is relevant)
        boolean ssh;               // an SSH session that has a security status (shield shown)
        boolean securityKnown;     // we have output to judge from; else "not checked"
        boolean pqAdvisory;        // the post-quantum advisory was seen
        boolean acknowledged;      // the banner was dismissed
        int fedLen;                // transcript length already fed to the detector (delta feeding)
        @Nullable String title, sub, failureReason;
        @Nullable Runnable cancel, retry, edit;
    }

    public TerminowConnectionUi(@NonNull TermuxActivity activity, @NonNull FrameLayout overlay) {
        this.activity = activity;
        this.overlay = overlay;
    }

    @NonNull private State state(@NonNull TerminalSession s) {
        State st = states.get(s);
        if (st == null) { st = new State(); states.put(s, st); }
        return st;
    }

    // ---- Called by TerminowHome ----

    /** A connect attempt started for this session. */
    void onConnectStart(@NonNull TerminalSession session, @NonNull String title, @NonNull String subHostUserPort,
                        boolean ssh, @NonNull Runnable cancel, @NonNull Runnable retry, @NonNull Runnable edit) {
        State st = state(session);
        st.phase = TerminowConnectionPhase.CONNECTING;
        st.active = true;
        st.ssh = ssh;
        st.failureReason = null;
        st.title = title;
        st.sub = subHostUserPort;
        st.cancel = cancel;
        st.retry = retry;
        st.edit = edit;
        if (session == current) render();
    }

    /** The connection phase advanced (from real output signals in watchConnection). */
    void onPhase(@NonNull TerminalSession session, @NonNull TerminowConnectionPhase phase) {
        State st = state(session);
        if (st.phase == phase) return;
        st.phase = phase;
        if (phase == TerminowConnectionPhase.CONNECTED) {
            // Plugs join, a short check mark, then the panel hides.
            if (session == current) render();
            handler.postDelayed(() -> { st.active = false; if (session == current) render(); }, 900);
            return;
        }
        if (phase == TerminowConnectionPhase.DISCONNECTED) st.active = false;
        if (session == current) render();
    }

    /** A connection failure with a human-readable reason; keeps the panel with a Retry button. */
    void onFailed(@NonNull TerminalSession session, @NonNull String reason) {
        State st = state(session);
        st.phase = TerminowConnectionPhase.FAILED;
        st.active = true;
        st.failureReason = reason;
        if (session == current) render();
    }

    /**
     * Feed the current transcript of an SSH session. Only the part not seen before is passed to the
     * detector, so the advisory is caught even once it has scrolled off; no output is stored.
     */
    void onOutput(@NonNull TerminalSession session, @NonNull CharSequence transcript) {
        State st = state(session);
        st.ssh = true;
        st.securityKnown = true;
        int len = transcript.length();
        if (len < st.fedLen) st.fedLen = 0;             // the transcript was cleared/reset
        if (len <= st.fedLen) return;
        CharSequence delta = transcript.subSequence(st.fedLen, len);
        st.fedLen = len;
        if (!st.pqAdvisory && st.security.onOutput(delta)) {
            st.pqAdvisory = true;
            st.acknowledged = false;
            if (session == current) render();
        }
    }

    /**
     * The attempt completed but without a real connected signal (only the bounded fallback elapsed):
     * hide the panel quietly rather than claim success with the check-mark animation.
     */
    void onConnectDone(@NonNull TerminalSession session) {
        State st = state(session);
        st.active = false;
        if (session == current) render();
    }

    /** The shown session changed: render that session's panel, banner and shield. */
    void onShowSession(@Nullable TerminalSession session) {
        current = session;
        render();
    }

    /** The session's tab was closed: drop its state and clean up. */
    void onCloseSession(@NonNull TerminalSession session) {
        State st = states.remove(session);
        if (st != null) { st.cancel = null; st.retry = null; }
        if (session == current) { current = null; render(); }
    }

    // ---- Rendering ----

    private void render() {
        State st = current != null ? states.get(current) : null;
        renderPanel(st);
        renderBanner(st);
        renderShield(st);
    }

    private void renderPanel(@Nullable State st) {
        boolean show = st != null && st.active;
        if (!show) { if (panelView != null) panelView.setVisibility(View.GONE); return; }
        ensurePanel();
        anim.setPhase(st.phase);
        panelTitle.setText(!TextUtils.isEmpty(st.title) ? st.title : activity.getString(R.string.terminow_conn_connecting));
        panelSub.setText(st.sub != null ? st.sub : "");
        panelSub.setVisibility(TextUtils.isEmpty(st.sub) ? View.GONE : View.VISIBLE);

        boolean failed = st.phase == TerminowConnectionPhase.FAILED;
        boolean connected = st.phase == TerminowConnectionPhase.CONNECTED;
        String status;
        switch (st.phase) {
            case AUTHENTICATING: status = activity.getString(R.string.terminow_conn_authenticating); break;
            case CONNECTED: status = activity.getString(R.string.terminow_conn_connected); break;
            case FAILED: status = st.failureReason != null ? st.failureReason : activity.getString(R.string.terminow_conn_failed); break;
            case DISCONNECTED: status = activity.getString(R.string.terminow_conn_disconnected); break;
            default: status = activity.getString(R.string.terminow_conn_connecting);
        }
        panelStatus.setText(status);
        panelStatus.setTextColor(failed ? 0xFFFF7B86 : connected ? 0xFF21C17A : 0xFFA8B0BF);

        cancelButton.setVisibility(!failed && !connected ? View.VISIBLE : View.GONE);
        retryButton.setVisibility(failed ? View.VISIBLE : View.GONE);
        editButton.setVisibility(failed ? View.VISIBLE : View.GONE);
        panelView.setVisibility(View.VISIBLE);
    }

    private void renderBanner(@Nullable State st) {
        boolean show = st != null && st.pqAdvisory && !st.acknowledged;
        if (!show) { if (bannerView != null) bannerView.setVisibility(View.GONE); return; }
        ensureBanner();
        bannerView.setVisibility(View.VISIBLE);
    }

    private void renderShield(@Nullable State st) {
        boolean show = st != null && st.ssh;
        if (!show) { if (shieldView != null) shieldView.setVisibility(View.GONE); return; }
        ensureShield();
        int tint;
        String desc;
        if (!st.securityKnown) { tint = 0xFF8A93A6; desc = activity.getString(R.string.terminow_security_unchecked); }
        else if (st.pqAdvisory) { tint = 0xFFF2C14E; desc = activity.getString(R.string.terminow_security_advisory); }
        else { tint = 0xFF21C17A; desc = activity.getString(R.string.terminow_security_ok); }
        shieldView.setColorFilter(tint);
        shieldView.setContentDescription(desc);
        shieldView.setVisibility(View.VISIBLE);
    }

    // ---- View construction ----

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics()));
    }

    private void ensurePanel() {
        if (panelView != null) return;
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(roundedBg(0xF21A1D25, dp(16)));
        card.setPadding(dp(20), dp(16), dp(20), dp(16));
        // Does not steal focus or pop the keyboard.
        card.setFocusable(false);
        card.setClickable(true); // absorb taps so they do not reach the terminal under it

        anim = new TerminowConnectingView(activity);
        card.addView(anim, new LinearLayout.LayoutParams(dp(150), dp(52)));

        panelTitle = text(16, 0xFFF5F7FA, true);
        panelTitle.setPadding(0, dp(10), 0, 0);
        card.addView(panelTitle);
        panelSub = text(12, 0xFFA8B0BF, false);
        card.addView(panelSub);
        panelStatus = text(13, 0xFFA8B0BF, false);
        panelStatus.setPadding(0, dp(6), 0, 0);
        card.addView(panelStatus);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, 0);
        cancelButton = pillButton(activity.getString(R.string.terminow_conn_cancel), 0xFF2C313D, 0xFFF5F7FA);
        cancelButton.setOnClickListener(v -> { State st = currentState(); if (st != null && st.cancel != null) st.cancel.run(); });
        retryButton = pillButton(activity.getString(R.string.terminow_conn_retry), 0xFF21C17A, 0xFF0B1F17);
        retryButton.setOnClickListener(v -> { State st = currentState(); if (st != null && st.retry != null) st.retry.run(); });
        editButton = pillButton(activity.getString(R.string.terminow_connection_edit_host), 0xFF2C313D, 0xFFF5F7FA);
        editButton.setOnClickListener(v -> { State st = currentState(); if (st != null && st.edit != null) st.edit.run(); });
        buttons.addView(cancelButton);
        buttons.addView(editButton);
        buttons.addView(retryButton);
        card.addView(buttons);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lp.setMargins(dp(24), dp(24), dp(24), dp(24));
        panelView = card;
        overlay.addView(panelView, lp);
    }

    private void ensureBanner() {
        if (bannerView != null) return;
        LinearLayout banner = new LinearLayout(activity);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setBackground(roundedBg(0xFF3A3524, dp(12)));
        banner.setPadding(dp(14), dp(10), dp(10), dp(10));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_terminow_shield);
        icon.setColorFilter(0xFFF2C14E);
        banner.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, dp(10), 0);
        TextView title = text(14, 0xFFF5F7FA, true);
        title.setText(R.string.terminow_security_banner_title);
        TextView body = text(12, 0xFFD6C9A3, false);
        body.setText(R.string.terminow_security_banner_body);
        texts.addView(title);
        texts.addView(body);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        banner.addView(texts, tlp);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        TextView details = pillButton(activity.getString(R.string.terminow_security_details), 0xFF4A4330, 0xFFF2C14E);
        details.setOnClickListener(v -> showDetails());
        TextView ack = pillButton(activity.getString(R.string.terminow_security_ack), 0x00000000, 0xFFD6C9A3);
        ack.setOnClickListener(v -> { State st = currentState(); if (st != null) { st.acknowledged = true; render(); } });
        actions.addView(details);
        actions.addView(ack);
        banner.addView(actions);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        lp.setMargins(dp(8), dp(8), dp(8), 0);
        bannerView = banner;
        overlay.addView(bannerView, lp);
    }

    private void ensureShield() {
        if (shieldView != null) return;
        shieldView = new ImageView(activity);
        shieldView.setImageResource(R.drawable.ic_terminow_shield);
        shieldView.setPadding(dp(6), dp(6), dp(6), dp(6));
        shieldView.setOnClickListener(v -> { State st = currentState(); if (st != null && st.pqAdvisory) showDetails(); });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(6), dp(8), 0);
        overlay.addView(shieldView, lp);
    }

    private void showDetails() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView warn = text(13, 0xFFF5F7FA, false);
        warn.setText(PQ_ADVISORY);
        warn.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(warn);
        TextView link = text(13, 0xFF21C17A, false);
        link.setText(PQ_URL);
        link.setPadding(0, dp(12), 0, 0);
        link.setOnClickListener(v -> openUrl(PQ_URL));
        box.addView(link);
        scroll.addView(box);
        new android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.terminow_security_details_title)
            .setView(scroll)
            .setPositiveButton(R.string.terminow_security_open_link, (d, w) -> openUrl(PQ_URL))
            .setNegativeButton(android.R.string.ok, null)
            .show();
    }

    private void openUrl(@NonNull String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            activity.showToast(url, false);
        }
    }

    @Nullable private State currentState() {
        return current != null ? states.get(current) : null;
    }

    private TextView text(float sizeSp, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pillButton(@NonNull String label, int bg, int fg) {
        TextView b = new TextView(activity);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        if ((bg >>> 24) != 0) b.setBackground(roundedBg(bg, dp(20)));
        b.setClickable(true);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private android.graphics.drawable.GradientDrawable roundedBg(int color, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
}
