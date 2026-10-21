package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the key bar below the terminal shows for a tab: the keys of a shell, or those of Claude
 * Code while it runs in the tab. Every key is a real key, a key with modifiers or one character
 * that goes through the input of the terminal; the name of a key is never typed. A command of
 * Claude Code is only put on its prompt, never sent with Enter.
 */
public final class TerminowKeyBarModel {

    private TerminowKeyBarModel() {}

    /** The keys that are shown. */
    public enum Mode { TERMINAL, CLAUDE }

    /** What the user chose for a tab with the mode button: found out, or always one of them. */
    public enum Choice { AUTO, TERMINAL, CLAUDE }

    /** The key of the bar that opens the key panel with more keys. It is never typed. */
    public static final String MORE = "MORE";

    /** Esc, Tab, Ctrl, Alt, the arrows and More, for a shell in Ubuntu or TermiNow. */
    public static final String TERMINAL_KEYS = "[['ESC','TAB','CTRL','ALT','LEFT','DOWN','UP','RIGHT'," +
        "{key: 'MORE', display: '⋯'}]]";

    /** Esc, Tab, Shift+Tab (the mode of Claude Code), Ctrl, the arrows, / and More, for Claude Code. */
    public static final String CLAUDE_KEYS = "[['ESC','TAB',{macro: 'SHIFT TAB', display: '⇧⇥'},'CTRL','LEFT','DOWN','UP','RIGHT'," +
        "{key: '/', display: '/'},{key: 'MORE', display: '⋯'}]]";

    @NonNull
    public static String keysOf(@NonNull Mode mode) {
        return mode == Mode.CLAUDE ? CLAUDE_KEYS : TERMINAL_KEYS;
    }

    /**
     * The keys of a tab.
     *
     * @param running Whether the tab still runs; one that ended is a plain terminal.
     * @param claudeLaunched Whether TermiNow started Claude Code as the program of the tab.
     * @param claudeFound Whether Claude Code was found running in the tab, like one typed in bash.
     */
    @NonNull
    public static Mode resolve(@NonNull Choice choice, boolean running, boolean claudeLaunched, boolean claudeFound) {
        if (!running) return Mode.TERMINAL;
        switch (choice) {
            case TERMINAL: return Mode.TERMINAL;
            case CLAUDE: return Mode.CLAUDE;
            default: return claudeLaunched || claudeFound ? Mode.CLAUDE : Mode.TERMINAL;
        }
    }

    /** A command of Claude Code, which is put on its prompt for the user to send. */
    public static final class Command {
        public final String text;
        /** Which string says what it does, by its name, see the strings of the key bar. */
        public final String descriptionName;
        /** The first version of Claude Code that has it. */
        public final String since;

        Command(@NonNull String text, @NonNull String descriptionName) {
            this(text, descriptionName, "1.0.0");
        }

        Command(@NonNull String text, @NonNull String descriptionName, @NonNull String since) {
            this.text = text;
            this.descriptionName = descriptionName;
            this.since = since;
        }
    }

    /** The numbers of a version that Claude Code prints, like "2.1.303 (Claude Code)", {@code null} if it has none. */
    @Nullable
    public static int[] parseVersion(@Nullable String version) {
        if (version == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(version);
        if (!matcher.find()) return null;
        try {
            return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean isAtLeast(@NonNull int[] version, @NonNull int[] minimum) {
        for (int i = 0; i < 3; i++) if (version[i] != minimum[i]) return version[i] > minimum[i];
        return true;
    }

    /**
     * The commands that the installed Claude Code has, by its version; all of them when the
     * version is not known, which the panel then says.
     */
    @NonNull
    public static List<Command> commandsFor(@Nullable String version) {
        int[] installed = parseVersion(version);
        if (installed == null) return CLAUDE_COMMANDS;
        List<Command> commands = new ArrayList<>();
        for (Command command : CLAUDE_COMMANDS) {
            int[] since = parseVersion(command.since);
            if (since == null || isAtLeast(installed, since)) commands.add(command);
        }
        return commands;
    }

    @Nullable
    public static Command commandOf(@NonNull String text) {
        for (Command command : CLAUDE_COMMANDS) if (command.text.equals(text)) return command;
        return null;
    }

    /** Pin a command, or unpin it if it was pinned; the others keep their order. */
    @NonNull
    public static List<String> toggleFavorite(@NonNull List<String> favorites, @NonNull String command) {
        List<String> result = new ArrayList<>(favorites);
        if (!result.remove(command)) result.add(command);
        return result;
    }

    /** A shortcut: keys that are sent, like "ESC ESC" or "CTRL c", in the form of a macro of the key bar. */
    public static final class Shortcut {
        public final String label;
        public final String keys;
        public final String descriptionName;

        Shortcut(@NonNull String label, @NonNull String keys, @NonNull String descriptionName) {
            this.label = label;
            this.keys = keys;
            this.descriptionName = descriptionName;
        }
    }

    public static final List<Command> CLAUDE_COMMANDS = Collections.unmodifiableList(Arrays.asList(
        new Command("/help", "terminow_keybar_cmd_help"),
        new Command("/clear", "terminow_keybar_cmd_clear"),
        new Command("/compact", "terminow_keybar_cmd_compact"),
        new Command("/model", "terminow_keybar_cmd_model"),
        new Command("/resume", "terminow_keybar_cmd_resume"),
        new Command("/init", "terminow_keybar_cmd_init"),
        new Command("/memory", "terminow_keybar_cmd_memory"),
        new Command("/mcp", "terminow_keybar_cmd_mcp"),
        new Command("/permissions", "terminow_keybar_cmd_permissions"),
        new Command("/status", "terminow_keybar_cmd_status"),
        new Command("/config", "terminow_keybar_cmd_config"),
        new Command("/cost", "terminow_keybar_cmd_cost"),
        new Command("/doctor", "terminow_keybar_cmd_doctor"),
        new Command("/login", "terminow_keybar_cmd_login"),
        new Command("/logout", "terminow_keybar_cmd_logout"),
        new Command("/exit", "terminow_keybar_cmd_exit")));

    public static final List<Shortcut> CLAUDE_SHORTCUTS = Collections.unmodifiableList(Arrays.asList(
        new Shortcut("Esc", "ESC", "terminow_keybar_key_claude_esc"),
        new Shortcut("Tab", "TAB", "terminow_keybar_key_claude_tab"),
        new Shortcut("Esc Esc", "ESC ESC", "terminow_keybar_key_claude_esc_esc"),
        new Shortcut("Shift+Tab", "SHIFT TAB", "terminow_keybar_key_claude_mode"),
        new Shortcut("Ctrl+C", "CTRL c", "terminow_keybar_key_ctrl_c"),
        new Shortcut("Ctrl+L", "CTRL l", "terminow_keybar_key_ctrl_l")));

    public static final List<Shortcut> TERMINAL_SHORTCUTS = Collections.unmodifiableList(Arrays.asList(
        new Shortcut("Ctrl+C", "CTRL c", "terminow_keybar_key_ctrl_c"),
        new Shortcut("Ctrl+D", "CTRL d", "terminow_keybar_key_ctrl_d"),
        new Shortcut("Ctrl+Z", "CTRL z", "terminow_keybar_key_ctrl_z"),
        new Shortcut("Ctrl+L", "CTRL l", "terminow_keybar_key_ctrl_l"),
        new Shortcut("Ctrl+R", "CTRL r", "terminow_keybar_key_ctrl_r"),
        new Shortcut("Home", "HOME", "terminow_keybar_key_home"),
        new Shortcut("End", "END", "terminow_keybar_key_end"),
        new Shortcut("PgUp", "PGUP", "terminow_keybar_key_pgup"),
        new Shortcut("PgDn", "PGDN", "terminow_keybar_key_pgdn")));

    /**
     * The text that a command puts on the prompt: the command alone, with no Enter or line
     * break, so that Claude Code only runs it when the user sends it.
     */
    @NonNull
    public static String promptText(@NonNull Command command) {
        return command.text.replace("\r", "").replace("\n", "");
    }

    /** Whether the arguments of a process are those of Claude Code: its program, by name or by its install. */
    public static boolean isClaudeProcess(@NonNull List<String> argv) {
        if (argv.isEmpty()) return false;
        String program = argv.get(0);
        String name = program.substring(program.lastIndexOf('/') + 1);
        if (name.equals("claude")) return true;
        // The official installer keeps each version in ~/.local/share/claude/versions/<version>
        if (program.contains("/claude/versions/")) return true;
        // An install with npm runs cli.js of @anthropic-ai/claude-code with node
        if (name.equals("node") || name.equals("nodejs")) {
            for (int i = 1; i < argv.size() && i < 4; i++)
                if (argv.get(i).contains("@anthropic-ai/claude-code/") || argv.get(i).endsWith("/bin/claude")) return true;
        }
        return false;
    }

    /**
     * Whether Claude Code runs in the processes that a tab started: the process of the tab and
     * all that came from it, as /proc shows them to the app (its own processes, also those of
     * proot, which keep their arguments). Processes that can not be read are left out.
     *
     * @param proc The /proc folder, another one in tests.
     */
    public static boolean isClaudeRunningUnder(@NonNull File proc, int rootPid) {
        if (rootPid <= 0) return false;
        File[] entries = proc.listFiles();
        if (entries == null) return false;
        Map<Integer, List<Integer>> children = new HashMap<>();
        for (File entry : entries) {
            int pid = parsePid(entry.getName());
            if (pid <= 0) continue;
            int parent = parentOf(new File(entry, "stat"));
            if (parent <= 0) continue;
            List<Integer> list = children.get(parent);
            if (list == null) children.put(parent, list = new ArrayList<>());
            list.add(pid);
        }
        List<Integer> todo = new ArrayList<>(Collections.singletonList(rootPid));
        // Limited, in case /proc changes while it is read
        for (int i = 0; i < todo.size() && i < 4096; i++) {
            int pid = todo.get(i);
            List<String> argv = argvOf(new File(proc, pid + "/cmdline"));
            if (argv != null && isClaudeProcess(argv)) return true;
            List<Integer> list = children.get(pid);
            if (list != null) todo.addAll(list);
        }
        return false;
    }

    private static int parsePid(@NonNull String name) {
        if (name.isEmpty() || name.length() > 9) return -1;
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) < '0' || name.charAt(i) > '9') return -1;
        return Integer.parseInt(name);
    }

    /** The parent pid in a stat file, after the name in parentheses, which may itself have spaces and parentheses. */
    static int parentOf(@NonNull File stat) {
        String text = read(stat);
        if (text == null) return -1;
        int end = text.lastIndexOf(')');
        if (end < 0) return -1;
        String[] fields = text.substring(end + 1).trim().split(" ");
        if (fields.length < 2) return -1;
        try {
            return Integer.parseInt(fields[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Nullable
    private static List<String> argvOf(@NonNull File cmdline) {
        String text = read(cmdline);
        if (text == null || text.isEmpty()) return null;
        List<String> argv = new ArrayList<>();
        for (String arg : text.split("\0")) if (!arg.isEmpty()) argv.add(arg);
        return argv;
    }

    @Nullable
    private static String read(@NonNull File file) {
        // The files of /proc have no size, so they are read until their end, and only the start
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[8192];
            int length = 0;
            int read;
            while (length < bytes.length && (read = in.read(bytes, length, bytes.length - length)) > 0) length += read;
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
