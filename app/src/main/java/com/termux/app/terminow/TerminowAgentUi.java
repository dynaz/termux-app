package com.termux.app.terminow;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.TermuxActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The AI Agent chat: the person types in Thai or English to add SSH hosts and open terminals. The
 * model, provider and API key live on the TermiNow backend — this screen never holds a key. Every
 * request carries the account's Firebase ID token (the same one Premium uses), and the backend
 * decides the plan, the limits and the model. In agent mode the backend returns tool calls that run
 * here on the device: new hosts are saved through the existing host store and connecting uses the
 * existing connect flow, and each one is confirmed by the person first. This screen never stores a
 * host itself, runs a shell, deletes a host, or sends a credential to the model.
 */
public final class TerminowAgentUi {

    /** How the chat reaches the Hosts screen: the host store, connecting, and viewing a host. */
    public interface Delegate {
        @NonNull TerminowAgentTools.Hosts hostStore();

        void connect(@NonNull TerminowStore.Host host);

        void viewHost(@NonNull TerminowStore.Host host);
    }

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final Delegate mDelegate;
    private final TerminowAccount mAccount;
    private final TerminowAiService mService;

    private TerminowAgent mAgent;
    private final JSONArray mChatMessages = new JSONArray();
    private boolean mAgentMode;
    private boolean mEnabled;
    private LinearLayout mThread;
    private AlertDialog mDialog;
    private boolean mSending;

    public TerminowAgentUi(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull Delegate delegate) {
        this.mActivity = activity;
        this.mStore = store;
        this.mDelegate = delegate;
        this.mAccount = new TerminowAccount(BuildConfig.TERMINOW_FIREBASE_API_KEY, activity.getPackageName());
        this.mService = new TerminowAiService(this::idToken);
    }

    /** The account's Firebase ID token for the backend, refreshed and kept like the other screens. */
    @NonNull
    private String idToken() throws IOException {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null) throw new IOException(mActivity.getString(R.string.terminow_agent_sign_in));
        try {
            String token = mAccount.getIdToken(session);
            mStore.setAccount(session);
            return token;
        } catch (TerminowAccount.AccountException e) {
            throw new IOException(String.valueOf(e.getMessage()));
        }
    }

    public void show() {
        if (mStore.getAccount() == null) {
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_agent_title)
                .setMessage(R.string.terminow_agent_sign_in)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        openChat();
    }

    private void openChat() {
        mAgent = new TerminowAgent(mService::chatAgent, mDelegate.hostStore(), mConfirmer);

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, dp(8), pad, 0);

        mThread = new LinearLayout(mActivity);
        mThread.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(mThread);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360));
        root.addView(scroll, scrollLp);

        LinearLayout inputRow = new LinearLayout(mActivity);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(8), 0, dp(4));
        EditText input = new EditText(mActivity);
        input.setHint(R.string.terminow_agent_input_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        inputRow.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = new TextView(mActivity);
        send.setText(R.string.terminow_agent_send);
        send.setTypeface(Typeface.DEFAULT_BOLD);
        send.setTextColor(color(R.color.terminow_panel_accent));
        send.setPadding(dp(14), dp(10), dp(6), dp(10));
        send.setMinHeight(dp(48));
        send.setGravity(Gravity.CENTER_VERTICAL);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty() || mSending || !mEnabled) return;
            input.setText("");
            sendMessage(text);
        });
        inputRow.addView(send);
        root.addView(inputRow);

        bubble(mActivity.getString(R.string.terminow_agent_welcome), false);

        mDialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_agent_title)
            .setView(root)
            .setNegativeButton(R.string.terminow_agent_close, null)
            .create();
        mDialog.show();

        checkReadiness();
    }

    /** Ask the backend what this account may do, and set chat vs agent mode. */
    private void checkReadiness() {
        TextView status = bubble(mActivity.getString(R.string.terminow_agent_checking), false);
        new Thread(() -> {
            TerminowAiService.Config config = null;
            String error = null;
            try {
                config = mService.config();
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            TerminowAiService.Config finalConfig = config;
            String finalError = error;
            mActivity.runOnUiThread(() -> {
                if (mThread != null) mThread.removeView(status);
                if (finalConfig == null) {
                    bubble(mActivity.getString(R.string.terminow_agent_error, finalError), false);
                    return;
                }
                mEnabled = finalConfig.enabled;
                mAgentMode = finalConfig.agent;
                if (!finalConfig.enabled) {
                    String message = finalConfig.message.isEmpty()
                        ? mActivity.getString(R.string.terminow_agent_not_ready) : finalConfig.message;
                    bubble(message, false);
                } else if (!finalConfig.agent) {
                    bubble(mActivity.getString(R.string.terminow_agent_chat_only), false);
                }
            });
        }, "TerminowAgentConfig").start();
    }

    private void sendMessage(@NonNull String text) {
        bubble(text, true);
        mSending = true;
        TextView thinking = bubble(mActivity.getString(R.string.terminow_agent_thinking), false);
        new Thread(() -> {
            TerminowAgent.Turn turn = null;
            String reply = null;
            String error = null;
            try {
                if (mAgentMode) turn = mAgent.send(text);
                else reply = plainChat(text);
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            TerminowAgent.Turn finalTurn = turn;
            String finalReply = reply;
            String finalError = error;
            mActivity.runOnUiThread(() -> {
                mSending = false;
                if (mThread != null) mThread.removeView(thinking);
                if (finalError != null) bubble(mActivity.getString(R.string.terminow_agent_error, finalError), false);
                else if (finalTurn != null) showTurn(finalTurn);
                else if (finalReply != null && !finalReply.trim().isEmpty()) bubble(finalReply, false);
            });
        }, "TerminowAgent").start();
    }

    /** Plain chat mode (no tools), keeping its own transcript. Used when the plan allows chat only. */
    @NonNull
    private String plainChat(@NonNull String text) throws IOException {
        try {
            mChatMessages.put(new JSONObject().put("role", "user").put("content", text));
        } catch (JSONException ignored) {
            // text is a valid string
        }
        TerminowAiService.Reply reply = mService.chat(mChatMessages, false);
        mChatMessages.put(reply.assistantMessage);
        return reply.content;
    }

    private void showTurn(@NonNull TerminowAgent.Turn turn) {
        if (!turn.reply.trim().isEmpty()) bubble(turn.reply, false);
        for (TerminowStore.Host host : turn.added) hostCard(host);
        for (TerminowStore.Host host : turn.connect) {
            bubble(mActivity.getString(R.string.terminow_agent_connecting, host.getTitle()), false);
            if (mDialog != null) mDialog.dismiss();
            mDelegate.connect(host);
        }
    }

    // --- confirmation (runs on the agent thread, blocks until the person answers) ----------------

    private final TerminowAgent.Confirmer mConfirmer = new TerminowAgent.Confirmer() {
        @Override
        public boolean confirmAddHost(@NonNull TerminowStore.Host proposed) {
            return confirm(mActivity.getString(R.string.terminow_agent_confirm_add_title),
                details(proposed), mActivity.getString(R.string.terminow_agent_confirm_add));
        }

        @Override
        public boolean confirmConnect(@NonNull TerminowStore.Host host) {
            return confirm(mActivity.getString(R.string.terminow_agent_confirm_connect_title),
                details(host), mActivity.getString(R.string.terminow_agent_confirm_connect));
        }
    };

    @NonNull
    private String details(@NonNull TerminowStore.Host host) {
        String line = (host.username.isEmpty() ? "" : host.username + "@") + host.address + ":" + host.port;
        return host.getTitle() + "\n" + line;
    }

    private boolean confirm(@NonNull String title, @NonNull String message, @NonNull String positive) {
        final BlockingQueue<Boolean> answer = new ArrayBlockingQueue<>(1);
        mActivity.runOnUiThread(() -> new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, (d, w) -> answer.offer(false))
            .setPositiveButton(positive, (d, w) -> answer.offer(true))
            .setOnCancelListener(d -> answer.offer(false))
            .show());
        try {
            Boolean decision = answer.poll(120, TimeUnit.SECONDS);
            return decision != null && decision;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // --- views ---------------------------------------------------------------------------------

    @NonNull
    private TextView bubble(@NonNull String text, boolean fromUser) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextColor(color(R.color.terminow_text));
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(color(fromUser ? R.color.terminow_panel_accent : R.color.terminow_quick_card));
        view.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.gravity = fromUser ? Gravity.END : Gravity.START;
        view.setLayoutParams(lp);
        if (mThread != null) {
            mThread.addView(view);
            scrollDown();
        }
        return view;
    }

    private void hostCard(@NonNull TerminowStore.Host host) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(rounded(color(R.color.terminow_quick_card)));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(6);
        card.setLayoutParams(cardLp);

        TextView title = new TextView(mActivity);
        title.setText(host.getTitle());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.terminow_text));
        card.addView(title);
        TextView sub = new TextView(mActivity);
        sub.setText((host.username.isEmpty() ? "" : host.username + "@") + host.address + ":" + host.port);
        sub.setTextColor(color(R.color.terminow_text_secondary));
        sub.setTextSize(12);
        card.addView(sub);

        LinearLayout actions = new LinearLayout(mActivity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(action(R.string.terminow_agent_open_terminal, () -> {
            if (mDialog != null) mDialog.dismiss();
            mDelegate.connect(host);
        }));
        actions.addView(action(R.string.terminow_agent_view_host, () -> {
            if (mDialog != null) mDialog.dismiss();
            mDelegate.viewHost(host);
        }));
        card.addView(actions);

        if (mThread != null) {
            mThread.addView(card);
            scrollDown();
        }
    }

    @NonNull
    private TextView action(int labelRes, @NonNull Runnable onClick) {
        TextView view = new TextView(mActivity);
        view.setText(labelRes);
        view.setTextColor(color(R.color.terminow_panel_accent));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(6), dp(20), dp(6));
        view.setMinHeight(dp(44));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setOnClickListener(v -> onClick.run());
        return view;
    }

    private void scrollDown() {
        View parent = (View) mThread.getParent();
        if (parent instanceof ScrollView) parent.post(() -> ((ScrollView) parent).fullScroll(View.FOCUS_DOWN));
    }

    @NonNull
    private GradientDrawable rounded(int fill) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(12));
        drawable.setColor(fill);
        return drawable;
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
