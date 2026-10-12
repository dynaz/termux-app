package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A saved way to open a terminal: environment, folder, tool or the user's own command, and tmux.
 * Profiles never hold passwords, keys or tokens, and a command that looks like it has one is
 * refused. A custom command is shown to the user before it runs the first time, and again after
 * it changed.
 */
public class TerminowLaunchProfile {

    /** The icons to pick from, shown as text. */
    public static final String[] ICONS = {"🖥️", "🐧", "✳️", "🚀", "🛠️", "📦", "🧪", "📁"};

    private static final Pattern SECRET = Pattern.compile(
        "(?i)(sk-ant-|sk-[a-z0-9]{20,}|ghp_[a-z0-9]{20,}|github_pat_|xox[abpr]-|AKIA[0-9A-Z]{16}|" +
            "password\\s*[=:]|passwd\\s*[=:]|--password|api[_-]?key\\s*[=:]|token\\s*[=:]|secret\\s*[=:]|" +
            "ANTHROPIC_API_KEY\\s*=|bearer\\s+[a-z0-9._-]{12,})");

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String icon = ICONS[0];
    /** {@link TerminowMcpTargets#TARGET_TERMINOW} or the name of a proot-distro container. */
    public String environment = TerminowMcpTargets.TARGET_TERMINOW;
    public String user = "root";
    /** The folder in the environment, empty for its home. */
    public String folder = "";
    public String tool = TerminowLaunchPlan.TOOL_SHELL;
    /** The user's own command, only for {@link TerminowLaunchPlan#TOOL_CUSTOM}. */
    public String command = "";
    public String tmux = TerminowLaunchPlan.TMUX_OFF;
    /** The command that the user saw and confirmed, to ask again when it changed. */
    public String confirmedCommand = "";

    /** Whether text looks like it has a password, key or token, which a profile must not keep. */
    public static boolean looksSecret(@NonNull String text) {
        return SECRET.matcher(text).find();
    }

    /** Why the profile can not be saved, {@code null} if it can. */
    @Nullable
    public String validate() {
        if (name.trim().isEmpty()) return "name";
        if (TerminowLaunchPlan.TOOL_CUSTOM.equals(tool) && command.trim().isEmpty()) return "command";
        if (command.contains("\0")) return "command";
        if (looksSecret(command)) return "secret";
        if (!environment.equals(TerminowMcpTargets.TARGET_TERMINOW) &&
            (!TerminowMcpTargets.isValidContainerName(environment) || !TerminowMcpTargets.isValidUserName(user)))
            return "environment";
        if (!folder.isEmpty() && (!folder.startsWith("/") || folder.contains("\n"))) return "folder";
        return null;
    }

    public boolean needsConfirmation() {
        return TerminowLaunchPlan.TOOL_CUSTOM.equals(tool) && !command.equals(confirmedCommand);
    }

    /** The tmux session of the profile, the same each time, for attaching to it again. */
    @NonNull
    public String getTmuxName() {
        return TerminowLaunchPlan.tmuxNameFor(name);
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("icon", icon).put("environment", environment)
            .put("user", user).put("folder", folder).put("tool", tool).put("command", command).put("tmux", tmux)
            .put("confirmedCommand", confirmedCommand);
    }

    @NonNull
    public static TerminowLaunchProfile fromJson(@NonNull JSONObject object) {
        TerminowLaunchProfile profile = new TerminowLaunchProfile();
        profile.id = object.optString("id", profile.id);
        profile.name = object.optString("name");
        profile.icon = object.optString("icon", ICONS[0]);
        profile.environment = object.optString("environment", TerminowMcpTargets.TARGET_TERMINOW);
        profile.user = object.optString("user", "root");
        profile.folder = object.optString("folder");
        profile.tool = object.optString("tool", TerminowLaunchPlan.TOOL_SHELL);
        profile.command = object.optString("command");
        profile.tmux = object.optString("tmux", TerminowLaunchPlan.TMUX_OFF);
        profile.confirmedCommand = object.optString("confirmedCommand");
        return profile;
    }

    @NonNull
    public static List<TerminowLaunchProfile> listFromJson(@NonNull String text) {
        List<TerminowLaunchProfile> profiles = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                TerminowLaunchProfile profile = fromJson(object);
                // A profile that was changed outside the app to hold a secret is not loaded
                if (profile.validate() == null) profiles.add(profile);
            }
        } catch (JSONException e) {
            // No profiles
        }
        return profiles;
    }

    @NonNull
    public static String listToJson(@NonNull List<TerminowLaunchProfile> profiles) {
        JSONArray array = new JSONArray();
        for (TerminowLaunchProfile profile : profiles) {
            try {
                array.put(profile.toJson());
            } catch (JSONException e) {
                // Skipped
            }
        }
        return array.toString();
    }

}
