package com.termux.app.terminow.winrm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.terminow.TerminowRdpBridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The PowerShell console of a WinRM host: commands, their output and errors, the state of the
 * connection and Disconnect. WinRM runs in the Linux environment; what it needs there is only
 * installed after the user said yes.
 */
public class TerminowWinrmActivity extends Activity implements TerminowWinrmSession.Listener {

    public static final String EXTRA_SESSION = "com.terminow.winrm.SESSION";

    public static void show(@NonNull Context context, @NonNull TerminowWinrmSession session) {
        Intent intent = new Intent(context, TerminowWinrmActivity.class).putExtra(EXTRA_SESSION, session.id);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Nullable private TerminowWinrmSession mSession;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mStatus;
    private TextView mOutput;
    private ScrollView mScroll;
    private EditText mInput;
    private TextView mRun;
    private final SpannableStringBuilder mText = new SpannableStringBuilder();
    private boolean mOfferedInstall;
    private boolean mInstalling;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra(EXTRA_SESSION);
        mSession = id != null ? TerminowWinrmSession.get(id) : null;
        if (mSession == null) {
            Toast.makeText(this, R.string.terminow_rdp_gone, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.terminow_bar_terminal));
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.setBackgroundColor(ContextCompat.getColor(this, R.color.terminow_quick_card));
        TextView back = button("‹", false, this::finish);
        back.setContentDescription(getString(R.string.terminow_rdp_back));
        bar.addView(back);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8), 0, dp(8), 0);
        TextView title = text(getString(R.string.terminow_winrm_console_title, mSession.target.title), 16, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        titles.addView(title);
        mStatus = text("", 12, ContextCompat.getColor(this, R.color.terminow_text_secondary));
        titles.addView(mStatus);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(button(getString(R.string.terminow_rdp_disconnect), false, this::confirmDisconnect));
        root.addView(bar);

        mScroll = new ScrollView(this);
        mOutput = text("", 13, ContextCompat.getColor(this, R.color.terminow_text));
        mOutput.setTypeface(Typeface.MONOSPACE);
        mOutput.setTextIsSelectable(true);
        mOutput.setPadding(dp(12), dp(10), dp(12), dp(10));
        mScroll.addView(mOutput);
        root.addView(mScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout prompt = new LinearLayout(this);
        prompt.setGravity(Gravity.CENTER_VERTICAL);
        prompt.setPadding(dp(8), dp(6), dp(8), dp(6));
        prompt.setBackgroundColor(ContextCompat.getColor(this, R.color.terminow_quick_card));
        TextView ps = text("PS>", 14, ContextCompat.getColor(this, R.color.terminow_quick_ready));
        ps.setTypeface(Typeface.MONOSPACE);
        prompt.addView(ps);
        mInput = new EditText(this);
        mInput.setTypeface(Typeface.MONOSPACE);
        mInput.setTextColor(Color.WHITE);
        mInput.setHint(R.string.terminow_winrm_hint);
        mInput.setHintTextColor(ContextCompat.getColor(this, R.color.terminow_text_secondary));
        mInput.setMaxLines(5);
        mInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        mInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                event.getAction() == KeyEvent.ACTION_DOWN)) {
                runInput();
                return true;
            }
            return false;
        });
        prompt.addView(mInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mRun = button(getString(R.string.terminow_winrm_run), true, this::runInput);
        prompt.addView(mRun);
        root.addView(prompt);
        setContentView(root);

        String transcript = mSession.getTranscript();
        if (!transcript.isEmpty()) append(transcript, 0, false);
        mSession.setListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSession != null) mSession.setListener(null);
    }

    // ---------------------------------------------------------------------------------------

    @Override
    public void onState(@NonNull TerminowWinrmSession session) {
        if (session != mSession) return;
        boolean connected = session.getState() == TerminowWinrmSession.State.CONNECTED;
        mRun.setEnabled(connected);
        mRun.setAlpha(connected ? 1f : 0.4f);
        switch (session.getState()) {
            case STARTING:
                mStatus.setText(getString(R.string.terminow_winrm_connecting, endpoint(session)));
                break;
            case CONNECTED:
                mStatus.setText(getString(R.string.terminow_winrm_connected, endpoint(session)));
                break;
            case FAILED: {
                String error = session.getError();
                mStatus.setText(R.string.terminow_rdp_failed);
                if (error.startsWith("MISSING|")) {
                    offerInstall(session);
                } else {
                    String detail = error.contains("|") ? error.substring(error.indexOf('|') + 1) : error;
                    append(getString(R.string.terminow_winrm_failed, detail) + "\n", 0xFFF2585B, true);
                    if (detail.contains("CERTIFICATE_VERIFY_FAILED") || detail.contains("SSLError"))
                        append(getString(R.string.terminow_winrm_cert_help) + "\n", 0xFFF5A623, true);
                }
                break;
            }
            default:
                mStatus.setText(R.string.terminow_rdp_disconnected);
                break;
        }
    }

    @NonNull
    private static String endpoint(@NonNull TerminowWinrmSession session) {
        return (session.target.https ? "https://" : "http://") + session.target.address + ":" + session.target.port;
    }

    @Override
    public void onResult(int id, @NonNull String stdout, @NonNull String stderr, int code) {
        if (!stdout.isEmpty()) append(stdout.endsWith("\n") ? stdout : stdout + "\n", 0, true);
        if (!stderr.trim().isEmpty()) append(stderr.trim() + "\n", 0xFFF2585B, true);
        if (code != 0) append(getString(R.string.terminow_winrm_exit, code) + "\n", 0xFF94A3B8, true);
    }

    @Override
    public void onCommandError(int id, @NonNull String message) {
        append(getString(R.string.terminow_winrm_command_failed, message) + "\n", 0xFFF2585B, true);
    }

    private void runInput() {
        TerminowWinrmSession session = mSession;
        String script = mInput.getText().toString();
        if (session == null || script.trim().isEmpty() || session.getState() != TerminowWinrmSession.State.CONNECTED) return;
        mInput.setText("");
        append("PS> " + script + "\n", 0xFF22C55E, true);
        session.run(script);
    }

    private void confirmDisconnect() {
        TerminowWinrmSession session = mSession;
        if (session == null || !session.isOpen()) {
            finish();
            return;
        }
        new AlertDialog.Builder(this, R.style.TerminowAlertDialog)
            .setMessage(getString(R.string.terminow_rdp_disconnect_confirm, session.target.title))
            .setPositiveButton(R.string.terminow_rdp_disconnect, (d, w) -> {
                session.disconnect();
                finish();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** pywinrm is not in the environment: install it only when the user says yes. */
    private void offerInstall(@NonNull TerminowWinrmSession session) {
        if (mOfferedInstall || mInstalling) return;
        mOfferedInstall = true;
        String command = "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y python3-winrm";
        new AlertDialog.Builder(this, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_winrm_install_title)
            .setMessage(getString(R.string.terminow_winrm_install_message, session.target.container, command))
            .setPositiveButton(R.string.terminow_winrm_install, (d, w) -> install(session, command))
            .setNegativeButton(android.R.string.cancel, (d, w) -> append(getString(R.string.terminow_winrm_install_skipped) + "\n", 0xFFF5A623, true))
            .show();
    }

    private void install(@NonNull TerminowWinrmSession session, @NonNull String command) {
        mInstalling = true;
        append("# " + command + "\n", 0xFF94A3B8, true);
        new Thread(() -> {
            int exit;
            try {
                Process process = TerminowRdpBridge.startInContainer(this, session.target.container, Arrays.asList("/bin/sh", "-c", command));
                process.getOutputStream().close();
                Thread errors = new Thread(() -> pipe(process.getErrorStream()), "TerminowWinrmInstallErrors");
                errors.start();
                pipe(process.getInputStream());
                exit = process.waitFor();
                errors.join(5000);
            } catch (IOException | InterruptedException e) {
                exit = -1;
                String message = String.valueOf(e.getMessage());
                mHandler.post(() -> append(message + "\n", 0xFFF2585B, true));
            }
            int result = exit;
            mHandler.post(() -> {
                mInstalling = false;
                if (result == 0) {
                    append(getString(R.string.terminow_winrm_installed) + "\n", 0xFF22C55E, true);
                    reconnect(session);
                } else {
                    append(getString(R.string.terminow_winrm_install_failed, result) + "\n", 0xFFF2585B, true);
                }
            });
        }, "TerminowWinrmInstall").start();
    }

    private void pipe(@NonNull java.io.InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String shown = line;
                mHandler.post(() -> append(shown + "\n", 0xFF94A3B8, true));
            }
        } catch (IOException e) {
            // Ended
        }
    }

    /** After the install: a new connection to the same host, asking for its password again from its record. */
    private void reconnect(@NonNull TerminowWinrmSession old) {
        TerminowWinrmSession.Target target = TerminowWinrmHosts.targetFor(this, old.target.hostKey);
        if (target == null) {
            Toast.makeText(this, R.string.terminow_rdp_host_gone, Toast.LENGTH_LONG).show();
            return;
        }
        old.setListener(null);
        mSession = TerminowWinrmSession.start(this, target);
        mOfferedInstall = false;
        mSession.setListener(this);
    }

    private void append(@NonNull String text, int color, boolean keep) {
        int start = mText.length();
        mText.append(text);
        if (color != 0) mText.setSpan(new ForegroundColorSpan(color), start, mText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (mText.length() > 200_000) mText.delete(0, mText.length() - 150_000);
        mOutput.setText(mText);
        if (keep && mSession != null) mSession.appendTranscript(text);
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    @NonNull
    private TextView button(@NonNull String label, boolean primary, @NonNull Runnable onClick) {
        TextView button = text(label, 14, Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setMinWidth(dp(44));
        button.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(primary ? ContextCompat.getColor(this, R.color.terminow_quick_primary) : 0x00000000);
        button.setBackground(shape);
        button.setOnClickListener(v -> onClick.run());
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
