package com.termux.app.terminow;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.mcp.McpException;
import com.termux.app.terminow.mcp.McpSession;
import com.termux.app.terminow.mcp.McpTool;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One MCP server: connect and disconnect, what the server is, why it failed and what to do next,
 * and its tools, with a search. Tapping a tool opens {@link TerminowMcpToolScreen}.
 */
final class TerminowMcpServerScreen implements TerminowMcpSessions.Listener {

    private final TerminowMcpPage mPage;
    private final TermuxActivity mActivity;
    private final TerminowMcpSessions mSessions;
    private final TerminowMcpProfile mProfile;
    private final LinearLayout mState;
    private final LinearLayout mTools;
    private final EditText mSearch;
    private String mQuery = "";

    private TerminowMcpServerScreen(@NonNull TerminowMcpPage page, @NonNull TerminowMcpProfile profile) {
        mPage = page;
        mActivity = page.getActivity();
        mSessions = page.getSessions();
        mProfile = profile;
        mState = new LinearLayout(mActivity);
        mState.setOrientation(LinearLayout.VERTICAL);
        mTools = new LinearLayout(mActivity);
        mTools.setOrientation(LinearLayout.VERTICAL);
        mSearch = new EditText(mActivity);
    }

    static void show(@NonNull TerminowMcpPage page, @NonNull TerminowMcpProfile profile) {
        new TerminowMcpServerScreen(page, profile).show();
    }

    private void show() {
        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(mPage.dp(22), mPage.dp(4), mPage.dp(22), mPage.dp(8));
        TextView endpoint = mPage.text(mPage.getTransportLabel(mProfile) + " · " + TerminowMcpPage.getEndpointLabel(mProfile) +
            (mProfile.isStdio() ? " · " + mPage.getTargetLabel(mProfile) : ""), 13, R.color.terminow_text_secondary);
        endpoint.setMaxLines(2);
        endpoint.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(endpoint, TerminowMcpPage.matchWrap());
        root.addView(mState, TerminowMcpPage.matchWrap());

        TextView toolsTitle = mPage.text(mActivity.getString(R.string.terminow_mcp_server_tools), 15, R.color.terminow_text);
        toolsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        toolsTitle.setPadding(0, mPage.dp(16), 0, mPage.dp(4));
        root.addView(toolsTitle, TerminowMcpPage.matchWrap());
        mPage.styleField(mSearch, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_FILTER);
        mSearch.setHint(R.string.terminow_mcp_server_search);
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                mQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                renderTools();
            }
        });
        root.addView(mSearch, TerminowMcpPage.matchWrap());
        root.addView(mTools, TerminowMcpPage.matchWrap());

        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(root);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mProfile.name)
            .setView(scroll)
            .setPositiveButton(R.string.terminow_mcp_server_done, null)
            .create();
        dialog.setOnDismissListener(d -> mSessions.removeListener(this));
        mSessions.addListener(this);
        render();
        dialog.show();
    }

    @Override
    public void onChanged(@NonNull String profileId, @NonNull McpSession.Snapshot snapshot) {
        if (profileId.equals(mProfile.id)) render();
    }

    private void render() {
        renderState();
        renderTools();
    }

    @Nullable
    private McpSession.Snapshot snapshot() {
        return mSessions.getSnapshot(mProfile.id);
    }

    private void renderState() {
        mState.removeAllViews();
        McpSession.Snapshot snapshot = snapshot();
        McpSession.State state = snapshot != null ? snapshot.state : McpSession.State.IDLE;
        mPage.addStatus(mState, TerminowMcpPage.statusOf(state), mPage.getSessionLabel(snapshot));

        if (state == McpSession.State.READY) {
            TextView server = mPage.text(mActivity.getString(R.string.terminow_mcp_server_info,
                snapshot.serverName.isEmpty() ? "?" : snapshot.serverName, snapshot.serverVersion, snapshot.protocolVersion), 13,
                R.color.terminow_text_secondary);
            server.setPadding(0, mPage.dp(4), 0, 0);
            server.setTextIsSelectable(true);
            mState.addView(server, TerminowMcpPage.matchWrap());
        }
        if (state == McpSession.State.FAILED && snapshot.error != null) addError(snapshot.error);
        if (state == McpSession.State.CONNECTING && mProfile.isStdio()) {
            TextView note = mPage.text(mActivity.getString(R.string.terminow_mcp_server_starting), 12, R.color.terminow_text_secondary);
            note.setPadding(0, mPage.dp(4), 0, 0);
            mState.addView(note, TerminowMcpPage.matchWrap());
        }

        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setPadding(0, mPage.dp(12), 0, 0);
        if (state == McpSession.State.READY || state == McpSession.State.CONNECTING) {
            TextView disconnect = mPage.actionButton(mActivity.getString(R.string.terminow_mcp_server_disconnect), false);
            disconnect.setOnClickListener(v -> mSessions.remove(mProfile.id));
            buttons.addView(disconnect, mPage.actionParams());
        } else {
            TextView connect = mPage.actionButton(mActivity.getString(R.string.terminow_mcp_server_connect), true);
            connect.setOnClickListener(v -> connect(false));
            buttons.addView(connect, mPage.actionParams());
        }
        if (state == McpSession.State.READY || state == McpSession.State.FAILED) {
            TextView reconnect = mPage.actionButton(mActivity.getString(R.string.terminow_mcp_server_reconnect), false);
            reconnect.setOnClickListener(v -> connect(true));
            buttons.addView(reconnect, mPage.actionParams());
        }
        mState.addView(buttons, TerminowMcpPage.matchWrap());
    }

    /** What went wrong in a sentence, what to do next, and the message of the server. */
    private void addError(@NonNull McpException error) {
        String[] explained = TerminowMcpPage.explain(mActivity, error.kind);
        LinearLayout box = mPage.card();
        TextView title = mPage.text(explained[0], 14, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title, TerminowMcpPage.matchWrap());
        if (!explained[1].isEmpty()) box.addView(mPage.text(explained[1], 13, R.color.terminow_text), TerminowMcpPage.matchWrap());
        TextView details = mPage.text(String.valueOf(error.getMessage()), 12, R.color.terminow_text_secondary);
        details.setTypeface(Typeface.MONOSPACE);
        details.setTextIsSelectable(true);
        details.setPadding(0, mPage.dp(6), 0, 0);
        box.addView(details, TerminowMcpPage.matchWrap());
        LinearLayout.LayoutParams params = TerminowMcpPage.matchWrap();
        params.topMargin = mPage.dp(10);
        mState.addView(box, params);
    }

    private void connect(boolean reconnect) {
        mPage.requirePremium(() -> {
            if (mProfile.isHttp()) {
                String urlError = TerminowMcpClient.validateUrl(mProfile.url, mPage.getStore().isMcpLocalHttpAllowed());
                if (urlError != null) {
                    mPage.showMessage(mProfile.name, urlError);
                    return;
                }
            }
            McpSession session = mSessions.open(mProfile);
            new Thread(() -> {
                try {
                    if (reconnect) session.reconnect();
                    else session.connect();
                } catch (McpException e) {
                    // The snapshot shows why
                }
            }).start();
        });
    }

    private void renderTools() {
        mTools.removeAllViews();
        McpSession.Snapshot snapshot = snapshot();
        boolean ready = snapshot != null && snapshot.state == McpSession.State.READY;
        // The page keeps the tools of each connection on the stored profile
        TerminowMcpProfile stored = mPage.findProfile(mProfile.id);
        if (stored == null) stored = mProfile;
        List<McpTool> tools = ready ? snapshot.tools : stored.cachedTools;
        mSearch.setVisibility(tools.size() > 5 ? View.VISIBLE : View.GONE);
        if (!ready && !tools.isEmpty()) {
            TextView cached = mPage.text(mActivity.getString(R.string.terminow_mcp_server_cached,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(stored.cachedToolsTime))), 12,
                R.color.terminow_text_secondary);
            cached.setPadding(0, mPage.dp(4), 0, mPage.dp(4));
            mTools.addView(cached, TerminowMcpPage.matchWrap());
        }
        if (tools.isEmpty()) {
            TextView none = mPage.text(mActivity.getString(ready ? (snapshot.hasTools ? R.string.terminow_mcp_server_no_tools :
                R.string.terminow_mcp_result_no_tools) : R.string.terminow_mcp_server_connect_for_tools), 13, R.color.terminow_text_secondary);
            none.setPadding(0, mPage.dp(8), 0, mPage.dp(8));
            mTools.addView(none, TerminowMcpPage.matchWrap());
            return;
        }
        int shown = 0;
        for (McpTool tool : tools) {
            if (!mQuery.isEmpty() && !(tool.name.toLowerCase(Locale.ROOT).contains(mQuery) ||
                tool.title.toLowerCase(Locale.ROOT).contains(mQuery) || tool.description.toLowerCase(Locale.ROOT).contains(mQuery)))
                continue;
            mTools.addView(createToolRow(tool, ready), TerminowMcpPage.matchWrap());
            shown++;
        }
        if (shown == 0) {
            TextView none = mPage.text(mActivity.getString(R.string.terminow_no_results), 13, R.color.terminow_text_secondary);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, mPage.dp(12), 0, mPage.dp(12));
            mTools.addView(none, TerminowMcpPage.matchWrap());
        }
        if (ready && snapshot.toolsTruncated) {
            TextView more = mPage.text(mActivity.getString(R.string.terminow_mcp_server_truncated), 12, R.color.terminow_text_secondary);
            mTools.addView(more, TerminowMcpPage.matchWrap());
        }
    }

    @NonNull
    private View createToolRow(@NonNull McpTool tool, boolean ready) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(mPage.dp(4), mPage.dp(10), mPage.dp(4), mPage.dp(10));
        row.setBackgroundResource(R.drawable.terminow_action);
        row.setAlpha(ready ? 1f : 0.6f);
        LinearLayout line = new LinearLayout(mActivity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = mPage.text(tool.getDisplayName(), 14, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TerminowMcpPage.addToolBadges(mPage, line, tool);
        row.addView(line, TerminowMcpPage.matchWrap());
        if (!tool.title.isEmpty() && !tool.title.equals(tool.name)) {
            TextView id = mPage.text(tool.name, 12, R.color.terminow_text_secondary);
            id.setTypeface(Typeface.MONOSPACE);
            row.addView(id, TerminowMcpPage.matchWrap());
        }
        if (!tool.description.isEmpty()) {
            TextView description = mPage.text(tool.description.trim(), 13, R.color.terminow_text_secondary);
            description.setMaxLines(2);
            description.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(description, TerminowMcpPage.matchWrap());
        }
        row.setOnClickListener(v -> {
            McpSession.Snapshot snapshot = snapshot();
            if (snapshot == null || snapshot.state != McpSession.State.READY) {
                mActivity.showToast(mActivity.getString(R.string.terminow_mcp_server_connect_first), false);
                return;
            }
            TerminowMcpToolScreen.show(mPage, mProfile, tool);
        });
        return row;
    }

}
