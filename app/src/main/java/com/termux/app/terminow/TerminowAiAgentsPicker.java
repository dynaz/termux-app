package com.termux.app.terminow;

import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.TerminowToolInstaller.Tool;
import com.termux.app.terminow.TerminowAiAgentsModel.Conversation;

import java.util.List;

/**
 * "AI Agents in Ubuntu": the picker opened from the AI button on an environment's card. It shows only
 * the AI CLIs actually detected installed in <b>this</b> environment (never another distro's), with
 * name, icon and version, lets the user pick one to open in a new tab, and remembers the choice per
 * environment. Claude Code keeps its verified conversation options (new / continue / resume); other
 * agents start a new conversation only. If nothing is installed, it offers the Tools Library at the AI
 * Agents category. Premium is required and re-checked at the moment of launch.
 */
final class TerminowAiAgentsPicker {

    private final TermuxActivity mActivity;
    private final TerminowLauncher mLauncher;
    private final TerminowStore mStore;
    @Nullable private Dialog mDialog;
    @Nullable private LinearLayout mContent;
    private TerminowLauncher.Environment mEnv;

    TerminowAiAgentsPicker(@NonNull TermuxActivity activity, @NonNull TerminowLauncher launcher, @NonNull TerminowStore store) {
        mActivity = activity;
        mLauncher = launcher;
        mStore = store;
    }

    void open(@NonNull TerminowLauncher.Environment environment) {
        mEnv = environment;
        mContent = openSheet(mActivity.getString(R.string.terminow_ai_agents_title));
        mLauncher.detectClaude(environment);               // for Claude's conversation options
        mLauncher.detectAiAgents(environment, this::render); // list of all installed AI CLIs
        render();
    }

    private void render() {
        LinearLayout content = mContent;
        if (content == null || mDialog == null || !mDialog.isShowing()) return;
        content.removeAllViews();

        section(content, R.string.terminow_ai_agents_source);
        info(content, mActivity.getString(R.string.terminow_ai_agents_env, mEnv.label));

        TerminowToolInstaller.Detection detection = mLauncher.aiAgentsOf(mEnv);
        if (detection == null) {
            loading(content, mActivity.getString(R.string.terminow_ai_agents_detecting));
            return;
        }

        List<Tool> installed = TerminowAiAgentsModel.installed(detection);
        String rememberedId = mStore.getRememberedAiAgent(mEnv.key);
        Tool remembered = TerminowAiAgentsModel.resolveRemembered(rememberedId, installed);
        if (remembered == null && !rememberedId.isEmpty()) mStore.clearRememberedAiAgent(mEnv.key);

        if (installed.isEmpty()) {
            info(content, mActivity.getString(R.string.terminow_ai_agents_empty));
            primaryRow(content, R.drawable.ic_terminow_agent, mActivity.getString(R.string.terminow_ai_agents_open_library), () -> {
                dismiss();
                mLauncher.showAiTools(mEnv);
            });
            return;
        }

        section(content, R.string.terminow_ai_agents_installed);
        for (Tool tool : installed) {
            String version = TerminowAiAgentsModel.versionOf(detection, tool);
            String subtitle = version.isEmpty() ? "" : version;
            boolean isRemembered = tool == remembered;
            if (isRemembered)
                subtitle = (subtitle.isEmpty() ? "" : subtitle + "  ·  ") + mActivity.getString(R.string.terminow_ai_agents_remembered);
            final Tool picked = tool;
            agentRow(content, iconOf(tool), mLauncher.aiLabel(tool), subtitle, () -> onPick(picked));
        }
    }

    private void onPick(@NonNull Tool tool) {
        // Premium is re-checked here, so a remembered shortcut can never launch for a free user.
        TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.AI_AGENTS, 1, () -> {
            mStore.setRememberedAiAgent(mEnv.key, tool.id);
            if (tool == Tool.CLAUDE) {
                TerminowLauncher.ClaudeState state = mLauncher.claudeOf(mEnv);
                List<Conversation> options = TerminowAiAgentsModel.conversations(tool,
                    state != null && state.supportsContinue, state != null && state.supportsResume);
                if (options.size() <= 1) {
                    dismiss();
                    mLauncher.launchClaudeConversation(mEnv, Conversation.NEW);
                } else {
                    showClaudeConversations(options);
                }
            } else {
                dismiss();
                mLauncher.launchAiInNewTab(mEnv, tool, new String[0]);
            }
        });
    }

    private void showClaudeConversations(@NonNull List<Conversation> options) {
        LinearLayout content = mContent;
        if (content == null) return;
        content.removeAllViews();
        section(content, R.string.terminow_ai_agents_claude_conversation);
        for (Conversation c : options) {
            int label;
            switch (c) {
                case CONTINUE: label = R.string.terminow_launcher_claude_continue; break;
                case RESUME: label = R.string.terminow_launcher_claude_resume; break;
                default: label = R.string.terminow_launcher_claude_new; break;
            }
            final Conversation chosen = c;
            agentRow(content, R.drawable.ic_terminow_tool_claude, mActivity.getString(label), "", () -> {
                dismiss();
                mLauncher.launchClaudeConversation(mEnv, chosen);
            });
        }
    }

    private int iconOf(@NonNull Tool tool) {
        switch (tool) {
            case CLAUDE: return R.drawable.ic_terminow_tool_claude;
            case CURSOR: return R.drawable.ic_terminow_tool_cursor;
            case CODEX: return R.drawable.ic_terminow_tool_codex;
            case GEMINI: return R.drawable.ic_terminow_tool_gemini;
            case GROK: return R.drawable.ic_terminow_tool_grok;
            case OPENCODE: return R.drawable.ic_terminow_tool_opencode;
            case AIDER: return R.drawable.ic_terminow_tool_aider;
            case HERMES: return R.drawable.ic_terminow_tool_hermes;
            default: return R.drawable.ic_terminow_agent;
        }
    }

    private void dismiss() {
        if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
    }

    // ---- sheet scaffolding ----

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
        TextView title = new TextView(mActivity);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(color(R.color.terminow_text));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        ViewCompat.setAccessibilityHeading(title, true);
        sheet.addView(title);

        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sheet, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addOnLayoutChangeListener((v, left, top, right, bottom, ol, ot, or, ob) -> {
            int width = Math.min(right - left, dp(560));
            int scrollMax = Math.round((bottom - top) * 0.8f) - dp(60);
            ViewGroup.LayoutParams p = sheet.getLayoutParams();
            ViewGroup.LayoutParams sp = scroll.getLayoutParams();
            int h = content.getHeight() > scrollMax ? scrollMax : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (p.width != width || sp.height != h) {
                p.width = width;
                sp.height = h;
                sheet.post(() -> {
                    sheet.setLayoutParams(p);
                    scroll.setLayoutParams(sp);
                });
            }
        });
        dialog.setContentView(root);
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnDismissListener(d -> {
            if (mDialog == dialog) {
                mDialog = null;
                mContent = null;
            }
        });
        dialog.show();
        return content;
    }

    private void section(@NonNull LinearLayout content, @StringRes int label) {
        TextView v = new TextView(mActivity);
        v.setText(mActivity.getString(label));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTextColor(color(R.color.terminow_panel_accent));
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setAllCaps(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);
        content.addView(v);
    }

    private void info(@NonNull LinearLayout content, @NonNull String text) {
        TextView v = new TextView(mActivity);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setTextColor(color(R.color.terminow_text_secondary));
        v.setPadding(dp(4), dp(6), dp(4), dp(6));
        content.addView(v);
    }

    private void loading(@NonNull LinearLayout content, @NonNull String text) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        ProgressBar bar = new ProgressBar(mActivity);
        int s = dp(22);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(s, s);
        blp.rightMargin = dp(12);
        row.addView(bar, blp);
        TextView v = new TextView(mActivity);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setTextColor(color(R.color.terminow_text_secondary));
        row.addView(v);
        content.addView(row);
    }

    private void agentRow(@NonNull LinearLayout content, int iconRes, @NonNull String name,
                          @NonNull String subtitle, @NonNull Runnable action) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(10), dp(6), dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        TypedValue ripple = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setOnClickListener(v -> action.run());

        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        icon.setContentDescription(name);
        int s = dp(32);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
        ilp.rightMargin = dp(12);
        row.addView(icon, ilp);

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(mActivity);
        title.setText(name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(color(R.color.terminow_text));
        texts.addView(title);
        if (!subtitle.isEmpty()) {
            TextView sub = new TextView(mActivity);
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setTextColor(color(R.color.terminow_text_secondary));
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void primaryRow(@NonNull LinearLayout content, int iconRes, @NonNull String name, @NonNull Runnable action) {
        agentRow(content, iconRes, name, "", action);
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
