package com.termux.app.terminow;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import java.util.List;

/**
 * QuickCut: save frequently-typed text and insert it into an open terminal session. Kept apart from
 * Snippets (startup commands) — its own store, icon (⚡) and screens. Insertion uses the terminal
 * paste path (bracketed paste when the program enabled it), never a shell-execution API, and never
 * adds Enter; content with a line break must be confirmed first since a newline can run a command.
 */
public final class TerminowQuickCutUi {

    private final TermuxActivity mActivity;
    private final TerminowQuickCuts mStore;

    public TerminowQuickCutUi(@NonNull TermuxActivity activity) {
        this.mActivity = activity;
        this.mStore = new TerminowQuickCuts(activity);
    }

    // --- Save from a terminal selection --------------------------------------------------------

    /** Save the selected text (passed in, so the selection may already be gone) as a QuickCut. */
    public void saveFromSelection(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_quickcut_empty), false);
            return;
        }
        editDialog(null, text);
    }

    private void editDialog(@Nullable TerminowQuickCut existing, @NonNull String content) {
        LinearLayout form = new LinearLayout(mActivity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);

        EditText name = new EditText(mActivity);
        name.setHint(R.string.terminow_quickcut_name_hint);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setSingleLine(true);
        if (existing != null) name.setText(existing.name);
        form.addView(name);

        EditText body = new EditText(mActivity);
        body.setHint(R.string.terminow_quickcut_content_hint);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        body.setText(content);
        body.setMaxLines(8);
        form.addView(body);

        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(form);

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(existing == null ? R.string.terminow_quickcut_save_title : R.string.terminow_quickcut_edit_title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.terminow_quickcut_save, (d, w) ->
                commitSave(existing, name.getText().toString().trim(), body.getText().toString()))
            .show();
    }

    /** Save, warning instead of silently overwriting when the name is already used by another entry. */
    private void commitSave(@Nullable TerminowQuickCut existing, @NonNull String name, @NonNull String content) {
        if (content.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_quickcut_empty), false);
            return;
        }
        TerminowQuickCut clash = name.isEmpty() ? null : TerminowQuickCuts.findByName(mStore.all(), name);
        if (clash != null && (existing == null || !clash.id.equals(existing.id))) {
            TerminowQuickCut target = existing;
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_quickcut_name_in_use_title)
                .setMessage(mActivity.getString(R.string.terminow_quickcut_name_in_use, name))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.terminow_quickcut_keep_both, (d, w) -> store(target, "", content))
                .setPositiveButton(R.string.terminow_quickcut_overwrite, (d, w) -> store(clash, name, content))
                .show();
            return;
        }
        store(existing, name, content);
    }

    private void store(@Nullable TerminowQuickCut existing, @NonNull String name, @NonNull String content) {
        TerminowQuickCut cut = existing != null ? existing : new TerminowQuickCut();
        cut.name = name;
        cut.content = content;
        mStore.save(cut);
        mActivity.showToast(mActivity.getString(R.string.terminow_quickcut_saved), false);
    }

    // --- The picker ----------------------------------------------------------------------------

    /** Open the picker to insert into {@code target}; null uses the current session. */
    public void showPicker(@Nullable TerminalSession target) {
        TerminalSession session = target != null ? target : mActivity.getCurrentSession();
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, dp(8), pad, 0);

        EditText search = new EditText(mActivity);
        search.setHint(R.string.terminow_quickcut_search);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setSingleLine(true);
        content.addView(search);

        LinearLayout rows = new LinearLayout(mActivity);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView rowScroll = new ScrollView(mActivity);
        rowScroll.addView(rows);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320));
        rowLp.topMargin = dp(8);
        content.addView(rowScroll, rowLp);

        ScrollView outer = new ScrollView(mActivity);
        outer.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_quickcut_title)
            .setView(outer)
            .setNeutralButton(R.string.terminow_quickcut_new, (d, w) -> editDialog(null, ""))
            .setNegativeButton(android.R.string.cancel, null)
            .create();

        Runnable rebuild = () -> fillPicker(rows, search.getText().toString(), session, dialog);
        search.addTextChangedListener(new SimpleWatcher(s -> rebuild.run()));
        rebuild.run();
        dialog.show();
    }

    private void fillPicker(@NonNull LinearLayout rows, @NonNull String query, @Nullable TerminalSession session, @NonNull AlertDialog dialog) {
        rows.removeAllViews();
        List<TerminowQuickCut> all = mStore.all();
        if (all.isEmpty()) {
            rows.addView(hint(mActivity.getString(R.string.terminow_quickcut_none)));
            return;
        }
        if (query.trim().isEmpty()) {
            addSection(rows, R.string.terminow_quickcut_pinned, TerminowQuickCuts.pinned(all), session, dialog);
            addSection(rows, R.string.terminow_quickcut_recent, TerminowQuickCuts.recent(all, 5), session, dialog);
            addSection(rows, R.string.terminow_quickcut_all, TerminowQuickCuts.ordered(all), session, dialog);
        } else {
            List<TerminowQuickCut> found = TerminowQuickCuts.search(all, query);
            if (found.isEmpty()) rows.addView(hint(mActivity.getString(R.string.terminow_quickcut_no_match)));
            else for (TerminowQuickCut cut : found) rows.addView(cutRow(cut, session, dialog));
        }
    }

    private void addSection(@NonNull LinearLayout rows, int titleRes, @NonNull List<TerminowQuickCut> list,
                            @Nullable TerminalSession session, @NonNull AlertDialog dialog) {
        if (list.isEmpty()) return;
        TextView title = new TextView(mActivity);
        title.setText(titleRes);
        title.setTextColor(color(R.color.terminow_text_secondary));
        title.setPadding(0, dp(12), 0, dp(4));
        rows.addView(title);
        for (TerminowQuickCut cut : list) rows.addView(cutRow(cut, session, dialog));
    }

    private View cutRow(@NonNull TerminowQuickCut cut, @Nullable TerminalSession session, @NonNull AlertDialog dialog) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setMinimumHeight(dp(48));

        TextView title = new TextView(mActivity);
        title.setText((cut.pinned ? "★ " : "") + cut.getTitle());
        title.setTextColor(color(R.color.terminow_text));
        title.setTextSize(15);
        row.addView(title);

        String preview = TerminowQuickCuts.preview(cut.content, 60);
        if (!preview.equals(cut.getTitle())) {
            TextView sub = new TextView(mActivity);
            sub.setText(preview);
            sub.setTextColor(color(R.color.terminow_text_secondary));
            sub.setTextSize(12);
            sub.setSingleLine(true);
            row.addView(sub);
        }

        row.setOnClickListener(v -> {
            dialog.dismiss();
            // Insert into the session the picker was opened for; if it has closed meanwhile, let the
            // person pick another open session rather than sending to the wrong one.
            if (stillOpen(session)) insert(cut, session);
            else insertWithSessionPicker(cut);
        });
        row.setOnLongClickListener(v -> {
            dialog.dismiss();
            rowActions(cut);
            return true;
        });
        return row;
    }

    private void rowActions(@NonNull TerminowQuickCut cut) {
        CharSequence[] items = {
            mActivity.getString(cut.pinned ? R.string.terminow_quickcut_unpin : R.string.terminow_quickcut_pin),
            mActivity.getString(R.string.terminow_quickcut_edit),
            mActivity.getString(R.string.terminow_quickcut_delete)
        };
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(cut.getTitle())
            .setItems(items, (d, which) -> {
                if (which == 0) mStore.setPinned(cut.id, !cut.pinned);
                else if (which == 1) editDialog(cut, cut.content);
                else confirmDelete(cut);
            })
            .show();
    }

    private void confirmDelete(@NonNull TerminowQuickCut cut) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_quickcut_delete)
            .setMessage(mActivity.getString(R.string.terminow_quickcut_delete_confirm, cut.getTitle()))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.terminow_quickcut_delete, (d, w) -> mStore.delete(cut.id))
            .show();
    }

    // --- Insertion -----------------------------------------------------------------------------

    /** Insert into a chosen session from the management list: pick a running session first. */
    public void insertWithSessionPicker(@NonNull TerminowQuickCut cut) {
        TermuxService service = mActivity.getTermuxService();
        List<TermuxSession> sessions = service != null ? service.getTermuxSessions() : null;
        if (sessions == null || sessions.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_quickcut_no_session), true);
            return;
        }
        List<TerminalSession> running = new java.util.ArrayList<>();
        List<CharSequence> labels = new java.util.ArrayList<>();
        for (TermuxSession s : sessions) {
            TerminalSession session = s.getTerminalSession();
            if (session == null || !session.isRunning()) continue;
            running.add(session);
            String name = session.mSessionName != null && !session.mSessionName.isEmpty() ? session.mSessionName : session.getTitle();
            labels.add(name == null || name.isEmpty() ? session.mHandle : name);
        }
        if (running.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_quickcut_no_session), true);
            return;
        }
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_quickcut_choose_session)
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> insert(cut, running.get(which)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Insert the content into the session, confirming first when it could run a command. */
    private void insert(@NonNull TerminowQuickCut cut, @Nullable TerminalSession session) {
        if (!stillOpen(session)) {
            mActivity.showToast(mActivity.getString(R.string.terminow_quickcut_session_gone), true);
            return;
        }
        if (TerminowQuickCuts.needsConfirmation(cut.content)) {
            LinearLayout box = new LinearLayout(mActivity);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(20);
            box.setPadding(pad, dp(8), pad, 0);
            TextView warn = new TextView(mActivity);
            warn.setText(R.string.terminow_quickcut_newline_warn);
            warn.setTextColor(color(R.color.terminow_mcp_warn));
            box.addView(warn);
            TextView body = new TextView(mActivity);
            body.setText(cut.content);
            body.setTextIsSelectable(true);
            body.setTypeface(android.graphics.Typeface.MONOSPACE);
            body.setTextSize(12);
            body.setPadding(0, dp(10), 0, 0);
            box.addView(body);
            ScrollView scroll = new ScrollView(mActivity);
            scroll.addView(box);
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_quickcut_confirm_title)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.terminow_quickcut_insert, (d, w) -> paste(cut, session))
                .show();
            return;
        }
        paste(cut, session);
    }

    /** The actual paste: the content only, with no Enter, through the terminal's own paste path. */
    private void paste(@NonNull TerminowQuickCut cut, @Nullable TerminalSession session) {
        if (!stillOpen(session)) {
            mActivity.showToast(mActivity.getString(R.string.terminow_quickcut_session_gone), true);
            return;
        }
        TerminalEmulator emulator = session.getEmulator();
        if (emulator == null) {
            mActivity.showToast(mActivity.getString(R.string.terminow_quickcut_session_gone), true);
            return;
        }
        // Bring the target session to the front so the person sees the inserted text.
        if (mActivity.getTermuxTerminalSessionClient() != null) mActivity.getTermuxTerminalSessionClient().setCurrentSession(session);
        emulator.paste(cut.content);
        // Count the use only now that the terminal has taken the text.
        mStore.recordUse(cut.id, System.currentTimeMillis());
        mActivity.showToast(mActivity.getString(R.string.terminow_quickcut_inserted, cut.getTitle()), false);
    }

    /** Whether the session is still one of the service's running sessions (not closed meanwhile). */
    private boolean stillOpen(@Nullable TerminalSession session) {
        if (session == null || !session.isRunning()) return false;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return false;
        for (TermuxSession s : service.getTermuxSessions()) if (s.getTerminalSession() == session) return true;
        return false;
    }

    // --- helpers -------------------------------------------------------------------------------

    @NonNull
    private TextView hint(@NonNull String text) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextColor(color(R.color.terminow_text_secondary));
        view.setPadding(0, dp(16), 0, dp(16));
        return view;
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final java.util.function.Consumer<String> onChanged;

        SimpleWatcher(java.util.function.Consumer<String> onChanged) {
            this.onChanged = onChanged;
        }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { onChanged.accept(s.toString()); }
    }
}
