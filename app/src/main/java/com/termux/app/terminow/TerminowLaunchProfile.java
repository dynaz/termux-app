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
    /** For {@link TerminowLaunchPlan#TOOL_PROGRAM}: the program, run without a shell, and its arguments. */
    public String executable = "";
    public List<String> arguments = new ArrayList<>();
    /** A command of the user that runs before the shell of {@link TerminowLaunchPlan#TOOL_SHELL}, empty for none. */
    public String startup = "";
    /** The color of the card, one of {@link #COLORS}. */
    public String color = COLORS[0];

    /** The colors of cards to pick from. */
    public static final String[] COLORS = {"#2F6FED", "#21C17A", "#E95420", "#A80030", "#8E5CF6", "#F5A623", "#0EA5B7", "#64748B"};

    /** Whether text looks like it has a password, key or token, which a profile must not keep. */
    public static boolean looksSecret(@NonNull String text) {
        return SECRET.matcher(text).find();
    }

    /** Why the profile can not be saved, {@code null} if it can. */
    @Nullable
    public String validate() {
        if (name.trim().isEmpty()) return "name";
        if (TerminowLaunchPlan.TOOL_CUSTOM.equals(tool) && command.trim().isEmpty()) return "command";
        if (command.contains("\0") || startup.contains("\0")) return "command";
        if (looksSecret(command) || looksSecret(startup)) return "secret";
        if (TerminowLaunchPlan.TOOL_PROGRAM.equals(tool)) {
            if (!isValidExecutable(executable)) return "executable";
            if (arguments.size() > 64) return "executable";
            for (String argument : arguments) {
                if (argument.contains("\0") || argument.length() > 4096) return "executable";
                if (looksSecret(argument)) return "secret";
            }
        }
        if (!java.util.Arrays.asList(COLORS).contains(color)) color = COLORS[0];
        if (!environment.equals(TerminowMcpTargets.TARGET_TERMINOW) &&
            (!TerminowMcpTargets.isValidContainerName(environment) || !TerminowMcpTargets.isValidUserName(user)))
            return "environment";
        if (!folder.isEmpty() && (!folder.startsWith("/") || folder.contains("\n"))) return "folder";
        return null;
    }

    /** An absolute path, or a name that is looked up in the PATH of the environment, without spaces or shell characters. */
    public static boolean isValidExecutable(@NonNull String executable) {
        return executable.matches("/?[A-Za-z0-9._+@-]+(/[A-Za-z0-9._+@-]+)*") && executable.length() <= 512;
    }

    /**
     * Whether the user must see what runs before it does: their own shell command, their own
     * startup command, or a program with its arguments, the first time and after a change.
     */
    public boolean needsConfirmation() {
        String what = whatRuns();
        return !what.isEmpty() && !what.equals(confirmedCommand);
    }

    /** What the user wrote that runs, as it is shown to confirm it; empty when it is only a shell or Claude. */
    @NonNull
    public String whatRuns() {
        if (TerminowLaunchPlan.TOOL_CUSTOM.equals(tool)) return command;
        if (TerminowLaunchPlan.TOOL_PROGRAM.equals(tool)) {
            StringBuilder line = new StringBuilder(executable);
            for (String argument : arguments) line.append(' ').append(TerminowStore.shellQuote(argument));
            return line.toString();
        }
        if (TerminowLaunchPlan.TOOL_SHELL.equals(tool) && !startup.trim().isEmpty()) return startup;
        return "";
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
            .put("confirmedCommand", confirmedCommand).put("executable", executable).put("arguments", new JSONArray(arguments))
            .put("startup", startup).put("color", color);
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
        // Fields of later versions; a profile saved before them gets their defaults
        profile.executable = object.optString("executable");
        JSONArray arguments = object.optJSONArray("arguments");
        for (int i = 0; arguments != null && i < arguments.length(); i++) profile.arguments.add(arguments.optString(i));
        profile.startup = object.optString("startup");
        profile.color = object.optString("color", COLORS[0]);
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
