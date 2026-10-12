package com.termux.app.terminow;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The screens of launch profiles: the list, the editor, the picker of the add menu and of the
 * toolbar, and the default for new Local Terminals of the settings. Profiles are kept on this
 * phone only, and the editor refuses commands that look like they hold a password, key or token.
 */
public class TerminowLaunchProfilesUi {

    /** The tools a profile can start, in the order of the editor. */
    private static final String[] TOOLS = {TerminowLaunchPlan.TOOL_SHELL, TerminowLaunchPlan.TOOL_CLAUDE,
        TerminowLaunchPlan.TOOL_CLAUDE_CONTINUE, TerminowLaunchPlan.TOOL_CLAUDE_RESUME, TerminowLaunchPlan.TOOL_CUSTOM};
    private static final int[] TOOL_LABELS = {R.string.terminow_profiles_tool_shell, R.string.terminow_profiles_tool_claude,
        R.string.terminow_profiles_tool_claude_continue, R.string.terminow_profiles_tool_claude_resume, R.string.terminow_profiles_tool_custom};
    private static final String[] TMUX_MODES = {TerminowLaunchPlan.TMUX_OFF, TerminowLaunchPlan.TMUX_CREATE, TerminowLaunchPlan.TMUX_ATTACH};
    private static final int[] TMUX_LABELS = {R.string.terminow_profiles_tmux_off, R.string.terminow_profiles_tmux_create,
        R.string.terminow_profiles_tmux_attach};

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final TerminowLauncher mLauncher;

    TerminowLaunchProfilesUi(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull TerminowLauncher launcher) {
        mActivity = activity;
        mStore = store;
        mLauncher = launcher;
    }

    /** Add or replace a profile by its id. */
    void save(@NonNull TerminowLaunchProfile profile) {
        List<TerminowLaunchProfile> profiles = mStore.getLaunchProfiles();
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(profile.id)) {
                profiles.set(i, profile);
                replaced = true;
            }
        }
        if (!replaced) profiles.add(profile);
        mStore.setLaunchProfiles(profiles);
    }

    private void delete(@NonNull TerminowLaunchProfile profile) {
        List<TerminowLaunchProfile> profiles = mStore.getLaunchProfiles();
        List<TerminowLaunchProfile> kept = new ArrayList<>();
        for (TerminowLaunchProfile other : profiles)
            if (!other.id.equals(profile.id)) kept.add(other);
        mStore.setLaunchProfiles(kept);
        if (mStore.getDefaultLaunchProfile().equals(profile.id)) mStore.setDefaultLaunchProfile("local");
    }

    // ---------------------------------------------------------------------------------------
    // The default of new Local Terminals
    // ---------------------------------------------------------------------------------------

    /** The name of the default launch profile, for the settings and the tools. */
    @NonNull
    String describeDefault() {
        return describeDefault(mActivity, mStore);
    }

    @NonNull
    static String describeDefault(@NonNull TermuxActivity activity, @NonNull TerminowStore store) {
        String value = store.getDefaultLaunchProfile();
        if (value.startsWith("ubuntu:"))
            return activity.getString(R.string.terminow_profiles_default_container, value.substring("ubuntu:".length()));
        if (!value.equals("local")) {
            for (TerminowLaunchProfile profile : store.getLaunchProfiles())
                if (profile.id.equals(value)) return profile.icon + " " + profile.name;
        }
        return activity.getString(R.string.terminow_profiles_default_local);
    }

    /** Choose what a new Local Terminal opens: the TermiNow shell, a container or a saved profile. */
    static void pickDefault(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull Runnable done) {
        List<String> values = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        values.add("local");
        labels.add(activity.getString(R.string.terminow_profiles_default_local));
        TerminowMcpTargets targets = new TerminowMcpTargets(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH),
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        for (TerminowMcpTargets.Container container : targets.getContainers()) {
            values.add("ubuntu:" + container.name);
            labels.add(activity.getString(R.string.terminow_profiles_default_container, container.name));
        }
        for (TerminowLaunchProfile profile : store.getLaunchProfiles()) {
            values.add(profile.id);
            labels.add(profile.icon + " " + profile.name);
        }
        int checked = Math.max(0, values.indexOf(store.getDefaultLaunchProfile()));
        new AlertDialog.Builder(activity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_settings_default_profile)
            .setSingleChoiceItems(labels.toArray(new CharSequence[0]), checked, (d, which) -> {
                store.setDefaultLaunchProfile(values.get(which));
                d.dismiss();
                done.run();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // The list
    // ---------------------------------------------------------------------------------------

    /** Choose a saved profile and start it, from the toolbar. */
    void pickAndLaunch() {
        List<TerminowLaunchProfile> profiles = mStore.getLaunchProfiles();
        if (profiles.isEmpty()) {
            showManager();
            return;
        }
        CharSequence[] labels = new CharSequence[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) labels[i] = profiles.get(i).icon + "  " + profiles.get(i).name;
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_profiles_open_from)
            .setItems(labels, (d, which) -> mLauncher.launchProfile(profiles.get(which)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    void showManager() {
        List<TerminowLaunchProfile> profiles = mStore.getLaunchProfiles();
        LinearLayout list = vertical();
        list.setPadding(dp(22), dp(4), dp(22), dp(4));
        AlertDialog[] dialog = new AlertDialog[1];
        if (profiles.isEmpty()) {
            list.addView(text(mActivity.getString(R.string.terminow_profiles_empty), 14, R.color.terminow_text_secondary), matchWrap());
        }
        String defaultValue = mStore.getDefaultLaunchProfile();
        for (TerminowLaunchProfile profile : profiles) {
            LinearLayout row = vertical();
            row.setPadding(0, dp(10), 0, dp(10));
            TextView title = text(profile.icon + "  " + profile.name +
                (profile.id.equals(defaultValue) ? "  · " + mActivity.getString(R.string.terminow_profiles_is_default) : ""), 16, R.color.terminow_text);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(title, matchWrap());
            row.addView(text(summary(profile), 12, R.color.terminow_text_secondary), matchWrap());
            row.setOnClickListener(v -> {
                if (dialog[0] != null) dialog[0].dismiss();
                showActions(profile);
            });
            list.addView(row, matchWrap());
        }
        TextView note = text(mActivity.getString(R.string.terminow_profiles_no_secrets), 12, R.color.terminow_text_secondary);
        note.setPadding(0, dp(10), 0, 0);
        list.addView(note, matchWrap());
        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(list);
        dialog[0] = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_profiles_manage)
            .setView(scroll)
            .setPositiveButton(R.string.terminow_profiles_new, (d, w) -> edit(null, TerminowLaunchPlan.TOOL_SHELL))
            .setNeutralButton(R.string.terminow_settings_default_profile_short, (d, w) -> pickDefault(mActivity, mStore, this::showManager))
            .setNegativeButton(R.string.terminow_profiles_close, null)
            .show();
    }

    /** "Ubuntu · ~/project · Claude Code · tmux attach" */
    @NonNull
    private String summary(@NonNull TerminowLaunchProfile profile) {
        TerminowLauncher.Environment environment = mLauncher.environmentFor(profile.environment);
        String env = environment != null ? environment.shortName() :
            mActivity.getString(R.string.terminow_profiles_env_gone, profile.environment);
        String folder = profile.folder.isEmpty() ? "~" : profile.folder;
        String tool = TerminowLaunchPlan.TOOL_CUSTOM.equals(profile.tool) ? profile.command :
            mActivity.getString(TOOL_LABELS[Math.max(0, indexOf(TOOLS, profile.tool))]);
        String text = env + " · " + folder + " · " + tool;
        if (!TerminowLaunchPlan.TMUX_OFF.equals(profile.tmux)) text += " · tmux " + profile.getTmuxName();
        return text;
    }

    private void showActions(@NonNull TerminowLaunchProfile profile) {
        CharSequence[] actions = {mActivity.getString(R.string.terminow_profiles_open), mActivity.getString(R.string.terminow_profiles_edit),
            mActivity.getString(R.string.terminow_profiles_make_default), mActivity.getString(R.string.terminow_profiles_delete)};
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(profile.icon + "  " + profile.name)
            .setItems(actions, (d, which) -> {
                if (which == 0) mLauncher.launchProfile(profile);
                else if (which == 1) edit(profile, profile.tool);
                else if (which == 2) {
                    mStore.setDefaultLaunchProfile(profile.id);
                    mActivity.showToast(mActivity.getString(R.string.terminow_profiles_default_set, profile.name), false);
                    showManager();
                } else {
                    new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                        .setTitle(mActivity.getString(R.string.terminow_profiles_delete_title, profile.name))
                        .setPositiveButton(R.string.terminow_profiles_delete, (d2, w2) -> {
                            delete(profile);
                            showManager();
                        })
                        .setNegativeButton(android.R.string.cancel, (d2, w2) -> showManager())
                        .show();
                }
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> showManager())
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // The editor
    // ---------------------------------------------------------------------------------------

    /**
     * Edit a profile, or a new one with the tool given, in the environment and folder that are
     * chosen in the launcher.
     */
    void edit(@Nullable TerminowLaunchProfile existing, @NonNull String tool) {
        TerminowLaunchProfile profile;
        if (existing != null) {
            profile = TerminowLaunchProfile.fromJson(safeJson(existing));
        } else {
            profile = new TerminowLaunchProfile();
            TerminowLauncher.Environment environment = mLauncher.currentEnvironment();
            profile.environment = environment.key;
            profile.user = mLauncher.getUser(environment);
            profile.tool = tool;
            if (TerminowLaunchPlan.TOOL_CUSTOM.equals(tool)) profile.icon = "🛠️";
            else if (TerminowLaunchPlan.isClaude(tool)) profile.icon = "✳️";
            else if (environment.container != null) profile.icon = "🐧";
        }

        LinearLayout box = vertical();
        box.setPadding(dp(22), dp(6), dp(22), dp(6));

        box.addView(label(R.string.terminow_profiles_name));
        EditText name = field(profile.name, false);
        name.setHint(R.string.terminow_profiles_name_hint);
        box.addView(name, matchWrap());

        box.addView(label(R.string.terminow_profiles_icon));
        LinearLayout icons = new LinearLayout(mActivity);
        List<TextView> iconViews = new ArrayList<>();
        for (String icon : TerminowLaunchProfile.ICONS) {
            TextView view = text(icon, 20, R.color.terminow_text);
            view.setGravity(Gravity.CENTER);
            view.setOnClickListener(v -> {
                profile.icon = icon;
                for (TextView other : iconViews) other.setBackground(other == view ? selected() : null);
            });
            view.setBackground(icon.equals(profile.icon) ? selected() : null);
            iconViews.add(view);
            icons.addView(view, new LinearLayout.LayoutParams(0, dp(40), 1));
        }
        box.addView(icons, matchWrap());

        box.addView(label(R.string.terminow_launcher_environment));
        TextView environmentChoice = choice();
        box.addView(environmentChoice, matchWrap());
        box.addView(label(R.string.terminow_launcher_user));
        TextView userChoice = choice();
        box.addView(userChoice, matchWrap());
        box.addView(label(R.string.terminow_profiles_folder));
        TextView folderChoice = choice();
        box.addView(folderChoice, matchWrap());

        box.addView(label(R.string.terminow_profiles_tool));
        TextView toolChoice = choice();
        box.addView(toolChoice, matchWrap());
        TextView commandLabel = label(R.string.terminow_profiles_command);
        box.addView(commandLabel);
        EditText command = field(profile.command, true);
        command.setHint(R.string.terminow_profiles_command_hint);
        box.addView(command, matchWrap());

        box.addView(label(R.string.terminow_profiles_tmux));
        TextView tmuxChoice = choice();
        box.addView(tmuxChoice, matchWrap());

        TextView error = text("", 13, R.color.terminow_mcp_warn);
        error.setPadding(0, dp(10), 0, 0);
        box.addView(error, matchWrap());
        TextView note = text(mActivity.getString(R.string.terminow_profiles_no_secrets), 12, R.color.terminow_text_secondary);
        note.setPadding(0, dp(6), 0, 0);
        box.addView(note, matchWrap());

        Runnable[] update = new Runnable[1];
        update[0] = () -> {
            TerminowLauncher.Environment environment = mLauncher.environmentFor(profile.environment);
            environmentChoice.setText((environment != null ? environment.label :
                mActivity.getString(R.string.terminow_profiles_env_gone, profile.environment)) + "  ▾");
            boolean container = environment != null && environment.container != null;
            userChoice.setVisibility(container ? View.VISIBLE : View.GONE);
            box.getChildAt(box.indexOfChild(userChoice) - 1).setVisibility(container ? View.VISIBLE : View.GONE);
            userChoice.setText(profile.user + "  ▾");
            folderChoice.setText((profile.folder.isEmpty() ? mActivity.getString(R.string.terminow_profiles_folder_home) : profile.folder) + "  ▾");
            toolChoice.setText(mActivity.getString(TOOL_LABELS[Math.max(0, indexOf(TOOLS, profile.tool))]) + "  ▾");
            boolean custom = TerminowLaunchPlan.TOOL_CUSTOM.equals(profile.tool);
            commandLabel.setVisibility(custom ? View.VISIBLE : View.GONE);
            command.setVisibility(custom ? View.VISIBLE : View.GONE);
            tmuxChoice.setText(mActivity.getString(TMUX_LABELS[Math.max(0, indexOf(TMUX_MODES, profile.tmux))]) + "  ▾");
        };
        update[0].run();

        environmentChoice.setOnClickListener(v -> {
            List<TerminowLauncher.Environment> environments = mLauncher.environments();
            CharSequence[] labels = new CharSequence[environments.size()];
            for (int i = 0; i < environments.size(); i++) labels[i] = environments.get(i).label;
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_launcher_environment)
                .setItems(labels, (d, which) -> {
                    TerminowLauncher.Environment chosen = environments.get(which);
                    if (!chosen.key.equals(profile.environment)) {
                        profile.environment = chosen.key;
                        profile.user = mLauncher.getUser(chosen);
                        // A folder of another environment is not one of this one
                        profile.folder = "";
                    }
                    update[0].run();
                })
                .show();
        });
        userChoice.setOnClickListener(v -> {
            TerminowLauncher.Environment environment = mLauncher.environmentFor(profile.environment);
            if (environment == null || environment.container == null) return;
            List<String> users = TerminowMcpTargets.getUsers(environment.container);
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_launcher_user)
                .setItems(users.toArray(new CharSequence[0]), (d, which) -> {
                    profile.user = users.get(which);
                    profile.folder = "";
                    update[0].run();
                })
                .show();
        });
        folderChoice.setOnClickListener(v -> {
            TerminowLauncher.Environment environment = mLauncher.environmentFor(profile.environment);
            if (environment == null) return;
            String start = profile.folder.isEmpty() ? mLauncher.homeOf(environment, profile.user) : profile.folder;
            mLauncher.pickFolder(environment, start, path -> {
                profile.folder = path.equals(mLauncher.homeOf(environment, profile.user)) ? "" : path;
                update[0].run();
            });
        });
        toolChoice.setOnClickListener(v -> {
            CharSequence[] labels = new CharSequence[TOOLS.length];
            for (int i = 0; i < TOOLS.length; i++) labels[i] = mActivity.getString(TOOL_LABELS[i]);
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_profiles_tool)
                .setItems(labels, (d, which) -> {
                    profile.tool = TOOLS[which];
                    update[0].run();
                })
                .show();
        });
        tmuxChoice.setOnClickListener(v -> {
            CharSequence[] labels = new CharSequence[TMUX_MODES.length];
            for (int i = 0; i < TMUX_MODES.length; i++) labels[i] = mActivity.getString(TMUX_LABELS[i]);
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_profiles_tmux)
                .setItems(labels, (d, which) -> {
                    profile.tmux = TMUX_MODES[which];
                    update[0].run();
                })
                .show();
        });

        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(existing == null ? R.string.terminow_profiles_new : R.string.terminow_profiles_edit)
            .setView(scroll)
            .setPositiveButton(R.string.terminow_profiles_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            profile.name = name.getText().toString().trim();
            profile.command = TerminowLaunchPlan.TOOL_CUSTOM.equals(profile.tool) ? command.getText().toString().trim() : "";
            String problem = profile.validate();
            if (problem != null) {
                error.setText(problemText(problem));
                return;
            }
            save(profile);
            dialog.dismiss();
            mActivity.showToast(mActivity.getString(R.string.terminow_profiles_saved, profile.name), false);
        }));
        dialog.show();
    }

    @NonNull
    private String problemText(@NonNull String problem) {
        switch (problem) {
            case "name": return mActivity.getString(R.string.terminow_profiles_error_name);
            case "command": return mActivity.getString(R.string.terminow_profiles_error_command);
            case "secret": return mActivity.getString(R.string.terminow_profiles_error_secret);
            case "folder": return mActivity.getString(R.string.terminow_launcher_folder_invalid);
            default: return mActivity.getString(R.string.terminow_profiles_error_environment);
        }
    }

    @NonNull
    private static org.json.JSONObject safeJson(@NonNull TerminowLaunchProfile profile) {
        try {
            return profile.toJson();
        } catch (org.json.JSONException e) {
            return new org.json.JSONObject();
        }
    }

    private static int indexOf(@NonNull String[] values, @NonNull String value) {
        for (int i = 0; i < values.length; i++)
            if (values[i].equals(value)) return i;
        return -1;
    }

    // ---------------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------------

    @NonNull
    private TextView label(int res) {
        TextView view = text(mActivity.getString(res), 12, R.color.terminow_text_secondary);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    @NonNull
    private EditText field(@NonNull String value, boolean monospace) {
        EditText field = new EditText(mActivity);
        field.setText(value);
        field.setTextColor(color(R.color.terminow_text));
        field.setHintTextColor(color(R.color.terminow_text_secondary));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (monospace) {
            field.setTypeface(Typeface.MONOSPACE);
            // No suggestions or learning of what is typed in a command
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setMaxLines(4);
        } else {
            field.setSingleLine(true);
        }
        return field;
    }

    @NonNull
    private TextView choice() {
        TextView view = text("", 15, R.color.terminow_text);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(44));
        view.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(12));
        shape.setColor(color(R.color.terminow_field));
        view.setBackground(shape);
        return view;
    }

    @NonNull
    private GradientDrawable selected() {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(color(R.color.terminow_field));
        shape.setStroke(dp(2), color(R.color.terminow_panel_accent));
        return shape;
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
