package com.termux.app.terminow;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The key panel of the terminal, like the one of Termius: groups of keys, the snippets of the
 * vault, the terminal theme, and the keyboard. On a wide screen, like an unfolded phone, it is
 * beside the terminal; on a narrow one it opens over the bottom of the terminal. Keys only go to
 * the tab that is shown, when the user taps them.
 */
public class TerminowKeyPanel {

    private static final int TAB_KEYS = 0;
    private static final int TAB_SNIPPETS = 1;

    /** CTRL and ALT of the panel, for the next key of the panel or of the keyboard. */
    private static boolean sCtrl;
    private static boolean sAlt;
    @Nullable private static Runnable sOnModifiersChanged;

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final FrameLayout mSide;
    private int mTab = TAB_KEYS;
    @Nullable private Dialog mDialog;
    @Nullable private LinearLayout mContent;

    public TerminowKeyPanel(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull FrameLayout side) {
        mActivity = activity;
        mStore = store;
        mSide = side;
    }

    /** Whether CTRL of the panel is on, which is then used up, for the keyboard. */
    public static boolean readCtrl() {
        if (!sCtrl) return false;
        sCtrl = false;
        notifyModifiers();
        return true;
    }

    public static boolean readAlt() {
        if (!sAlt) return false;
        sAlt = false;
        notifyModifiers();
        return true;
    }

    private static void notifyModifiers() {
        Runnable listener = sOnModifiersChanged;
        if (listener != null) listener.run();
    }

    private boolean isWide() {
        Configuration configuration = mActivity.getResources().getConfiguration();
        return configuration.screenWidthDp >= 600;
    }

    /** Whether the panel is shown beside the terminal, see {@link #update(boolean)}. */
    private boolean isSideOn() {
        return mStore.isKeyPanelShown();
    }

    /**
     * Show or hide the panel beside the terminal, after the terminal was shown or hidden or the
     * screen was folded or unfolded.
     */
    public void update(boolean terminalShown) {
        boolean side = terminalShown && isWide() && isSideOn();
        if (side && mSide.getVisibility() != View.VISIBLE) {
            if (mDialog != null) mDialog.dismiss();
            mSide.removeAllViews();
            mSide.addView(build(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mSide.setVisibility(View.VISIBLE);
        } else if (!side && mSide.getVisibility() != View.GONE) {
            mSide.setVisibility(View.GONE);
            mSide.removeAllViews();
            mContent = null;
        }
    }

    /** The button of the toolbar: the panel beside the terminal on and off, or over it on a narrow screen. */
    public void toggle() {
        if (isWide()) {
            mStore.setKeyPanelShown(!isSideOn());
            update(true);
            return;
        }
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
            return;
        }
        Dialog dialog = new Dialog(mActivity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(build());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(mActivity.getResources().getDisplayMetrics().heightPixels * 0.45f));
            // The terminal above stays usable, and the panel does not take the keyboard
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        dialog.setOnDismissListener(d -> {
            mDialog = null;
            mContent = null;
        });
        mDialog = dialog;
        dialog.show();
    }

    // ---------------------------------------------------------------------------------------
    // The panel
    // ---------------------------------------------------------------------------------------

    @NonNull
    private View build() {
        LinearLayout panel = vertical();
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(18));
        background.setColor(color(R.color.terminow_bar_home));
        panel.setBackground(background);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout tabs = new LinearLayout(mActivity);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.addView(tab(R.drawable.ic_terminow_grid, R.string.terminow_keys_tab_keys, mTab == TAB_KEYS, () -> setTab(TAB_KEYS)), tabParams());
        tabs.addView(tab(R.drawable.ic_terminow_snippets, R.string.terminow_snippets, mTab == TAB_SNIPPETS, () -> setTab(TAB_SNIPPETS)), tabParams());
        tabs.addView(tab(R.drawable.ic_terminow_theme, R.string.terminow_keys_tab_theme, false,
            () -> new TerminowThemeDialog(mActivity, mStore, null).show()), tabParams());
        tabs.addView(tab(R.drawable.ic_terminow_keyboard, R.string.terminow_keys_tab_keyboard, false,
            () -> mActivity.getTermuxTerminalViewClient().onToggleSoftKeyboardRequest()), tabParams());
        panel.addView(tabs, matchWrap());

        ScrollView scroll = new ScrollView(mActivity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = vertical();
        content.setPadding(0, dp(10), 0, 0);
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        mContent = content;
        sOnModifiersChanged = this::render;
        render();
        return panel;
    }

    private void setTab(int tab) {
        mTab = tab;
        View root = mSide.getVisibility() == View.VISIBLE ? mSide : null;
        if (root != null) {
            mSide.removeAllViews();
            mSide.addView(build(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (mDialog != null) {
            mDialog.setContentView(build());
        }
    }

    private void render() {
        LinearLayout content = mContent;
        if (content == null) return;
        content.removeAllViews();
        if (mTab == TAB_SNIPPETS) {
            renderSnippets(content);
            return;
        }
        TextView customize = keyButton("⚙  " + mActivity.getString(R.string.terminow_keys_customize), false);
        customize.setOnClickListener(v -> showCustomize());
        content.addView(customize, rowParams());
        for (List<String> group : TerminowKeyGroups.fromJson(mStore.getKeyGroups())) {
            LinearLayout row = new LinearLayout(mActivity);
            for (String key : group) {
                boolean active = ("CTRL".equals(key) && sCtrl) || ("ALT".equals(key) && sAlt);
                TextView button = keyButton(TerminowKeyGroups.label(key), active);
                attach(button, key);
                // Keys with longer names get more room, like "shift+tab" and "ctrl+B, %"
                float weight = Math.max(1, Math.min(2, TerminowKeyGroups.label(key).length() / 4f));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), weight);
                params.setMargins(dp(3), 0, dp(3), 0);
                row.addView(button, params);
            }
            content.addView(row, rowParams());
        }
    }

    private void renderSnippets(@NonNull LinearLayout content) {
        List<TerminowStore.Snippet> snippets = mStore.getSnippets();
        if (snippets.isEmpty()) {
            TextView empty = text(mActivity.getString(R.string.terminow_keys_no_snippets), 13, R.color.terminow_text_secondary);
            empty.setPadding(dp(6), dp(10), dp(6), dp(10));
            content.addView(empty, matchWrap());
            return;
        }
        TextView hint = text(mActivity.getString(R.string.terminow_keys_snippets_hint), 12, R.color.terminow_text_secondary);
        hint.setPadding(dp(6), 0, dp(6), dp(8));
        content.addView(hint, matchWrap());
        for (TerminowStore.Snippet snippet : snippets) {
            LinearLayout row = vertical();
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(10));
            shape.setColor(color(R.color.terminow_field));
            row.setBackground(shape);
            TextView name = text(snippet.getTitle(), 14, R.color.terminow_text);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(name, matchWrap());
            TextView command = text(snippet.command, 12, R.color.terminow_text_secondary);
            command.setTypeface(Typeface.MONOSPACE);
            command.setMaxLines(2);
            row.addView(command, matchWrap());
            row.setOnClickListener(v -> paste(snippet.command, false));
            row.setOnLongClickListener(v -> {
                paste(snippet.command, true);
                return true;
            });
            content.addView(row, rowParams());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Sending keys to the tab that is shown
    // ---------------------------------------------------------------------------------------

    /** Sends through the extra keys of Termux, which know the cursor modes of the terminal. */
    private static class Sender extends TerminalExtraKeys {
        Sender(@NonNull TerminalView view) {
            super(view);
        }

        void send(@NonNull View view, @NonNull String key, boolean ctrl, boolean alt) {
            onTerminalExtraKeyButtonClick(view, key, ctrl, alt, false, false);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attach(@NonNull TextView button, @NonNull String key) {
        button.setOnClickListener(v -> press(v, key));
        // Arrows and similar repeat while held
        String[] tokens = TerminowKeyGroups.tokens(key);
        if (tokens.length == 1 && (tokens[0].equals("UP") || tokens[0].equals("DOWN") || tokens[0].equals("LEFT") ||
            tokens[0].equals("RIGHT") || tokens[0].equals("BKSP") || tokens[0].equals("DEL"))) {
            Runnable[] repeat = new Runnable[1];
            repeat[0] = () -> {
                press(button, key);
                button.postDelayed(repeat[0], 80);
            };
            button.setOnLongClickListener(v -> {
                button.postDelayed(repeat[0], 0);
                return true;
            });
            button.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL)
                    button.removeCallbacks(repeat[0]);
                return false;
            });
        }
    }

    private void press(@NonNull View view, @NonNull String key) {
        String[] tokens = TerminowKeyGroups.tokens(key);
        if (tokens.length == 1 && tokens[0].equals("CTRL")) {
            sCtrl = !sCtrl;
            render();
            return;
        }
        if (tokens.length == 1 && tokens[0].equals("ALT")) {
            sAlt = !sAlt;
            render();
            return;
        }
        TerminalView terminalView = mActivity.getTerminalView();
        TerminalSession session = mActivity.getCurrentSession();
        if (terminalView == null || session == null || !session.isRunning()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_keys_no_session), false);
            return;
        }
        Sender sender = new Sender(terminalView);
        // The terminal reads the modifiers of the panel for every key, so they are used up here
        boolean ctrl = sCtrl, alt = sAlt;
        boolean hadModifiers = ctrl || alt;
        sCtrl = false;
        sAlt = false;
        for (String token : tokens) {
            if (token.equals("CTRL")) {
                ctrl = true;
            } else if (token.equals("ALT")) {
                alt = true;
            } else if (token.equals("S-TAB")) {
                session.write("\033[Z");
                ctrl = alt = false;
            } else if (token.length() == 2 && token.charAt(0) == '^') {
                int code = TerminowKeyGroups.controlCode(token.charAt(1));
                session.write((alt ? "\033" : "") + (char) code);
                ctrl = alt = false;
            } else {
                sender.send(view, token, ctrl, alt);
                ctrl = alt = false;
            }
        }
        if (hadModifiers) render();
    }

    /** Paste a snippet into the tab, and press Enter only when asked to by a long press. */
    private void paste(@NonNull String command, boolean run) {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null || !session.isRunning() || session.getEmulator() == null) {
            mActivity.showToast(mActivity.getString(R.string.terminow_keys_no_session), false);
            return;
        }
        session.getEmulator().paste(command);
        if (run) session.write("\r");
    }

    // ---------------------------------------------------------------------------------------
    // Customize keyboard
    // ---------------------------------------------------------------------------------------

    private void showCustomize() {
        List<List<String>> groups = TerminowKeyGroups.fromJson(mStore.getKeyGroups());
        Dialog dialog = new Dialog(mActivity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = vertical();
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(20));
        background.setColor(color(R.color.terminow_bar_home));
        root.setBackground(background);
        root.setPadding(dp(14), dp(12), dp(14), dp(14));

        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView close = icon(R.drawable.ic_terminow_close, R.string.terminow_profiles_close);
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text(mActivity.getString(R.string.terminow_keys_customize_title), 19, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView reset = icon(R.drawable.ic_terminow_reset, R.string.terminow_keys_reset);
        head.addView(reset, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(head, matchWrap());
        TextView subtitle = text(mActivity.getString(R.string.terminow_keys_customize_message), 13, R.color.terminow_text_secondary);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle, matchWrap());

        RecyclerView list = new RecyclerView(mActivity);
        list.setLayoutManager(new LinearLayoutManager(mActivity));
        Runnable save = () -> {
            mStore.setKeyGroups(TerminowKeyGroups.toJson(groups));
            render();
        };
        GroupAdapter adapter = new GroupAdapter(groups, save);
        list.setAdapter(adapter);
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
                int a = from.getAdapterPosition(), b = to.getAdapterPosition();
                if (a < 0 || b < 0) return false;
                Collections.swap(groups, a, b);
                adapter.notifyItemMoved(a, b);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                save.run();
            }
        });
        helper.attachToRecyclerView(list);
        adapter.helper = helper;

        FrameLayout listFrame = new FrameLayout(mActivity);
        listFrame.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView add = text("+", 30, R.color.terminow_bar_home);
        add.setGravity(Gravity.CENTER);
        add.setContentDescription(mActivity.getString(R.string.terminow_keys_add_group));
        GradientDrawable fab = new GradientDrawable();
        fab.setShape(GradientDrawable.OVAL);
        fab.setColor(color(R.color.terminow_panel_accent));
        add.setBackground(fab);
        add.setElevation(dp(6));
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM | Gravity.END);
        fabParams.setMargins(0, 0, dp(8), dp(8));
        listFrame.addView(add, fabParams);
        root.addView(listFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        add.setOnClickListener(v -> editGroup(null, keys -> {
            if (groups.size() >= TerminowKeyGroups.MAX_GROUPS) return;
            groups.add(keys);
            adapter.notifyItemInserted(groups.size() - 1);
            list.scrollToPosition(groups.size() - 1);
            save.run();
        }));
        reset.setOnClickListener(v -> new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_keys_reset)
            .setMessage(R.string.terminow_keys_reset_message)
            .setPositiveButton(R.string.terminow_keys_reset, (d, w) -> {
                groups.clear();
                groups.addAll(TerminowKeyGroups.defaults());
                adapter.notifyDataSetChanged();
                mStore.setKeyGroups(null);
                render();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show());

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(mActivity.getResources().getDisplayMetrics().widthPixels - dp(24), dp(720));
            window.setLayout(width, Math.round(mActivity.getResources().getDisplayMetrics().heightPixels * 0.85f));
        }
        dialog.show();
    }

    private interface GroupEdited {
        void onEdited(@NonNull List<String> keys);
    }

    /** Edit the keys of a group, one key a line. */
    private void editGroup(@Nullable List<String> group, @NonNull GroupEdited edited) {
        LinearLayout box = vertical();
        box.setPadding(dp(24), dp(6), dp(24), 0);
        box.addView(text(mActivity.getString(R.string.terminow_keys_group_help, TerminowKeyGroups.MAX_KEYS_PER_GROUP), 12,
            R.color.terminow_text_secondary), matchWrap());
        EditText field = new EditText(mActivity);
        field.setTypeface(Typeface.MONOSPACE);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setMinLines(4);
        field.setGravity(Gravity.TOP);
        field.setTextColor(color(R.color.terminow_text));
        if (group != null) field.setText(android.text.TextUtils.join("\n", group));
        else field.setHint("^C\n^B %\nESC\n|");
        field.setHintTextColor(color(R.color.terminow_text_secondary));
        box.addView(field, matchWrap());
        TextView error = text("", 13, R.color.terminow_mcp_warn);
        box.addView(error, matchWrap());
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(group == null ? R.string.terminow_keys_add_group : R.string.terminow_keys_edit_group)
            .setView(box)
            .setPositiveButton(R.string.terminow_profiles_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            StringBuilder bad = new StringBuilder();
            List<String> keys = TerminowKeyGroups.parseGroup(field.getText().toString(), bad);
            if (bad.length() > 0) {
                error.setText(mActivity.getString(R.string.terminow_keys_bad_key, bad.toString()));
                return;
            }
            if (keys.isEmpty() || keys.size() > TerminowKeyGroups.MAX_KEYS_PER_GROUP) {
                error.setText(mActivity.getString(R.string.terminow_keys_group_size, TerminowKeyGroups.MAX_KEYS_PER_GROUP));
                return;
            }
            dialog.dismiss();
            edited.onEdited(keys);
        }));
        dialog.show();
    }

    private class GroupAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<List<String>> groups;
        final Runnable save;
        @Nullable ItemTouchHelper helper;

        GroupAdapter(@NonNull List<List<String>> groups, @NonNull Runnable save) {
            this.groups = groups;
            this.save = save;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(6), dp(6), dp(6));
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(12));
            shape.setColor(color(R.color.terminow_surface));
            row.setBackground(shape);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
            params.setMargins(0, dp(4), 0, dp(4));
            row.setLayoutParams(params);
            return new RecyclerView.ViewHolder(row) {};
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            LinearLayout row = (LinearLayout) holder.itemView;
            row.removeAllViews();
            List<String> group = groups.get(position);
            ImageView delete = icon(R.drawable.ic_terminow_delete, R.string.terminow_profiles_delete);
            delete.setOnClickListener(v -> {
                int at = holder.getAdapterPosition();
                if (at < 0) return;
                groups.remove(at);
                notifyItemRemoved(at);
                save.run();
            });
            row.addView(delete, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout keys = new LinearLayout(mActivity);
            keys.setGravity(Gravity.CENTER);
            for (String key : group) {
                TextView chip = keyButton(TerminowKeyGroups.label(key), false);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                chip.setPadding(dp(8), 0, dp(8), 0);
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
                chipParams.setMargins(dp(3), 0, dp(3), 0);
                keys.addView(chip, chipParams);
            }
            keys.setOnClickListener(v -> editGroup(group, edited -> {
                int at = holder.getAdapterPosition();
                if (at < 0) return;
                groups.set(at, edited);
                notifyItemChanged(at);
                save.run();
            }));
            row.addView(keys, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            ImageView handle = icon(R.drawable.ic_terminow_drag, R.string.terminow_keys_move);
            handle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && helper != null) helper.startDrag(holder);
                return false;
            });
            row.addView(handle, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }

        @Override
        public int getItemCount() {
            return groups.size();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------------

    @NonNull
    private View tab(int iconRes, int labelRes, boolean selected, @NonNull Runnable onClick) {
        ImageView view = icon(iconRes, labelRes);
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (selected) {
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(12));
            shape.setColor(color(R.color.terminow_field));
            view.setBackground(shape);
            view.setColorFilter(color(R.color.terminow_text), PorterDuff.Mode.SRC_IN);
        }
        view.setOnClickListener(v -> onClick.run());
        return view;
    }

    @NonNull
    private LinearLayout.LayoutParams tabParams() {
        return new LinearLayout.LayoutParams(0, dp(46), 1);
    }

    @NonNull
    private ImageView icon(int iconRes, int descriptionRes) {
        ImageView view = new ImageView(mActivity);
        view.setImageResource(iconRes);
        view.setColorFilter(color(R.color.terminow_text_secondary), PorterDuff.Mode.SRC_IN);
        view.setPadding(dp(9), dp(9), dp(9), dp(9));
        view.setContentDescription(mActivity.getString(descriptionRes));
        view.setFocusable(false);
        return view;
    }

    @NonNull
    private TextView keyButton(@NonNull String label, boolean active) {
        TextView button = text(label, 14, active ? R.color.terminow_bar_home : R.color.terminow_text);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setFocusable(false);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(8));
        shape.setColor(color(active ? R.color.terminow_panel_accent : R.color.terminow_field));
        button.setBackground(shape);
        return button;
    }

    @NonNull
    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(8);
        return params;
    }

    @NonNull
    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(mActivity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    @NonNull
    private TextView text(@NonNull String text, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, mActivity.getResources().getDisplayMetrics()));
    }

}
