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

    /** The keys that are shown: a shell, or one of the AI CLIs while it runs in the tab. */
    public enum Mode { TERMINAL, CLAUDE, CURSOR, CODEX, GEMINI }

    /** What the user chose for a tab with the mode button: found out (AUTO), or forced to one. */
    public enum Choice { AUTO, TERMINAL, CLAUDE, CURSOR, CODEX, GEMINI }

    /** The AI modes, in the order shown in the picker; TERMINAL is the shell, not an AI. */
    public static final List<Mode> AI_MODES = Collections.unmodifiableList(Arrays.asList(Mode.CLAUDE, Mode.CURSOR, Mode.CODEX, Mode.GEMINI));

    /** Whether a mode is an AI CLI (not the plain shell). */
    public static boolean isAi(@NonNull Mode mode) {
        return mode != Mode.TERMINAL;
    }

    /** The Choice that forces a mode, for the picker and Reset-to-Auto. */
    @NonNull
    public static Choice choiceFor(@NonNull Mode mode) {
        switch (mode) {
            case CLAUDE: return Choice.CLAUDE;
            case CURSOR: return Choice.CURSOR;
            case CODEX: return Choice.CODEX;
            case GEMINI: return Choice.GEMINI;
            default: return Choice.TERMINAL;
        }
    }

    /** The key of the bar that opens the key panel with more keys. It is never typed. */
    public static final String MORE = "MORE";

    /** Esc, Tab, Ctrl, Alt and /, for a shell in Ubuntu or TermiNow. */
    public static final List<String> TERMINAL_KEYS = Collections.unmodifiableList(Arrays.asList("ESC", "TAB", "CTRL", "ALT", "/"));

    /** The same with Shift+Tab, which switches the mode of Claude Code. */
    public static final List<String> CLAUDE_KEYS = Collections.unmodifiableList(Arrays.asList("ESC", "TAB", "SHIFT TAB", "CTRL", "ALT", "/"));

    /** Cursor rotates its modes (Agent/Plan/Ask) with Shift+Tab, and takes @ for file context. */
    public static final List<String> CURSOR_KEYS = Collections.unmodifiableList(Arrays.asList("ESC", "TAB", "SHIFT TAB", "CTRL", "ALT", "/", "@"));

    /** Codex interrupts with Esc and queues with Tab; a conservative base set. */
    public static final List<String> CODEX_KEYS = Collections.unmodifiableList(Arrays.asList("ESC", "TAB", "CTRL", "ALT", "/"));

    /** Gemini takes @ for file context and ! for a shell command, besides the base keys. */
    public static final List<String> GEMINI_KEYS = Collections.unmodifiableList(Arrays.asList("ESC", "TAB", "CTRL", "ALT", "/", "@", "!"));

    /** The arrows, always together, in the order of Vim and of most terminal key bars. */
    public static final List<String> NAVIGATION_KEYS = Collections.unmodifiableList(Arrays.asList("LEFT", "DOWN", "UP", "RIGHT"));

    /** The keys of the bar for a mode, as the names that the extra keys send, a macro like "SHIFT TAB", or a character. */
    @NonNull
    public static List<String> keysOf(@NonNull Mode mode) {
        switch (mode) {
            case CLAUDE: return CLAUDE_KEYS;
            case CURSOR: return CURSOR_KEYS;
            case CODEX: return CODEX_KEYS;
            case GEMINI: return GEMINI_KEYS;
            default: return TERMINAL_KEYS;
        }
    }

    /** Whether a key of the bar is a modifier, which is on for the next key instead of being sent. */
    public static boolean isModifier(@NonNull String key) {
        return key.equals("CTRL") || key.equals("ALT");
    }

    /** Whether a key repeats while it is held, like the arrows. */
    public static boolean repeats(@NonNull String key) {
        return NAVIGATION_KEYS.contains(key);
    }

    /**
     * Ctrl or Alt of the bar: tapped, it is on for the next key and then off; held, it is locked
     * on until it is tapped again. It is off again on another tab.
     */
    public static final class Modifier {
        private boolean mOn;
        private boolean mLocked;

        public boolean isOn() {
            return mOn;
        }

        public boolean isLocked() {
            return mOn && mLocked;
        }

        /** A tap: on for one key, or off when it is on or locked. */
        public void tap() {
            mOn = !mOn;
            mLocked = false;
        }

        /** Held: locked on, or off when it was locked. */
        public void hold() {
            boolean wasLocked = isLocked();
            mOn = !wasLocked;
            mLocked = !wasLocked;
        }

        /** Whether it applies to the key that is sent now; a modifier that is not locked is then used up. */
        public boolean consume() {
            if (!mOn) return false;
            if (!mLocked) mOn = false;
            return true;
        }

        public void reset() {
            mOn = false;
            mLocked = false;
        }
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

    /**
     * The mode of a tab for any provider: a manual override wins (and stays until the user picks AUTO),
     * else the AI the app launched, else the one detected, else the shell. An ended tab is always the
     * shell. A per-session choice never leaks to another tab, since each tab resolves from its own
     * {@code choice}, {@code launched} and {@code detected}.
     *
     * @param launched  The AI the app started in the tab (from Tools Library / Quick Launch), or null.
     * @param detected  The AI found running in the tab by the detection scan, or null.
     */
    @NonNull
    public static Mode resolve(@NonNull Choice choice, boolean running, @Nullable Mode launched, @Nullable Mode detected) {
        if (!running) return Mode.TERMINAL;
        switch (choice) {
            case TERMINAL: return Mode.TERMINAL;
            case CLAUDE: return Mode.CLAUDE;
            case CURSOR: return Mode.CURSOR;
            case CODEX: return Mode.CODEX;
            case GEMINI: return Mode.GEMINI;
            default:
                if (launched != null) return launched;
                if (detected != null) return detected;
                return Mode.TERMINAL;
        }
    }

    /**
     * Whether Claude Code looks to be running from the terminal output, used when it can not be found
     * in the local process tree — for example when it runs on a remote host over SSH, so its process
     * is not in this device's {@code /proc}. It matches the persistent markers of the Claude Code
     * interface in the last screen of output, so that an exited Claude or a plain shell does not match.
     */
    public static boolean isClaudeInOutput(String transcript) {
        if (transcript == null || transcript.isEmpty()) return false;
        // Only the last screen or so: the interface is redrawn at the bottom while Claude runs, so when
        // it is not running the markers are not there, avoiding a stale match from far up the scrollback.
        int window = 2000;
        String tail = transcript.length() > window ? transcript.substring(transcript.length() - window) : transcript;
        return tail.contains("? for shortcuts") || tail.contains("Welcome to Claude Code");
    }

    /** Where a command is listed in the panel, in the order of a session. */
    public enum Category { CONVERSATION, MODEL, PROJECT, TOOLS, ACCOUNT }

    /** A command of an AI CLI, which is put on its prompt for the user to send. */
    public static final class Command {
        public final String text;
        /** Which string says what it does, by its name, see the strings of the key bar (Claude). Empty for providers that carry a literal {@link #description} instead. */
        public final String descriptionName;
        /** A literal description, used when {@link #descriptionName} is empty (the non-Claude providers). */
        public final String description;
        public final Category category;
        /** What it takes after its name, like "<path>" when it needs it or "[name]" when it may have it, empty for nothing. */
        public final String argument;
        /** The first version of Claude Code that has it. */
        public final String since;

        Command(@NonNull String text, @NonNull String descriptionName, @NonNull Category category, @NonNull String argument) {
            this(text, descriptionName, category, argument, "1.0.0");
        }

        Command(@NonNull String text, @NonNull String descriptionName, @NonNull Category category, @NonNull String argument,
                @NonNull String since) {
            this(text, descriptionName, "", category, argument, since);
        }

        Command(@NonNull String text, @NonNull String descriptionName, @NonNull String description, @NonNull Category category,
                @NonNull String argument, @NonNull String since) {
            this.text = text;
            this.descriptionName = descriptionName;
            this.description = description;
            this.category = category;
            this.argument = argument;
            this.since = since;
        }

        /** Whether it can not run without what follows its name. */
        public boolean needsArgument() {
            return argument.startsWith("<");
        }
    }

    /** A command of a non-Claude AI CLI, with a literal description (no string resource needed). */
    @NonNull
    static Command aiCommand(@NonNull String text, @NonNull String argument, @NonNull String description) {
        return new Command(text, "", description, Category.CONVERSATION, argument, "1.0.0");
    }

    /** Whether a command matches what was typed in the filter of the panel: its name or what it does. */
    public static boolean matches(@NonNull Command command, @NonNull String description, @NonNull String filter) {
        String query = filter.trim().toLowerCase(java.util.Locale.ROOT);
        if (query.startsWith("/")) query = query.substring(1);
        if (query.isEmpty()) return true;
        return command.text.substring(1).contains(query) || description.toLowerCase(java.util.Locale.ROOT).contains(query);
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
        /** A literal description, used when {@link #descriptionName} is empty. */
        public final String description;

        Shortcut(@NonNull String label, @NonNull String keys, @NonNull String descriptionName) {
            this(label, keys, descriptionName, "");
        }

        Shortcut(@NonNull String label, @NonNull String keys, @NonNull String descriptionName, @NonNull String description) {
            this.label = label;
            this.keys = keys;
            this.descriptionName = descriptionName;
            this.description = description;
        }
    }

    @NonNull
    static Shortcut aiShortcut(@NonNull String label, @NonNull String keys, @NonNull String description) {
        return new Shortcut(label, keys, "", description);
    }

    /** The commands of https://code.claude.com/docs/en/commands that are of use in a terminal on a phone. */
    public static final List<Command> CLAUDE_COMMANDS = Collections.unmodifiableList(Arrays.asList(
        new Command("/help", "terminow_keybar_cmd_help", Category.CONVERSATION, ""),
        new Command("/clear", "terminow_keybar_cmd_clear", Category.CONVERSATION, "[name]"),
        new Command("/compact", "terminow_keybar_cmd_compact", Category.CONVERSATION, "[instructions]"),
        new Command("/resume", "terminow_keybar_cmd_resume", Category.CONVERSATION, "[session]"),
        new Command("/rewind", "terminow_keybar_cmd_rewind", Category.CONVERSATION, ""),
        new Command("/branch", "terminow_keybar_cmd_branch", Category.CONVERSATION, "[name]"),
        new Command("/rename", "terminow_keybar_cmd_rename", Category.CONVERSATION, "[name]"),
        new Command("/btw", "terminow_keybar_cmd_btw", Category.CONVERSATION, "[question]"),
        new Command("/recap", "terminow_keybar_cmd_recap", Category.CONVERSATION, ""),
        new Command("/context", "terminow_keybar_cmd_context", Category.CONVERSATION, ""),
        new Command("/copy", "terminow_keybar_cmd_copy", Category.CONVERSATION, "[N]"),
        new Command("/export", "terminow_keybar_cmd_export", Category.CONVERSATION, "[filename]"),
        new Command("/exit", "terminow_keybar_cmd_exit", Category.CONVERSATION, ""),
        new Command("/model", "terminow_keybar_cmd_model", Category.MODEL, "[model]"),
        new Command("/effort", "terminow_keybar_cmd_effort", Category.MODEL, "[level]"),
        new Command("/fast", "terminow_keybar_cmd_fast", Category.MODEL, "[on|off]"),
        new Command("/plan", "terminow_keybar_cmd_plan", Category.MODEL, "[description]"),
        new Command("/permissions", "terminow_keybar_cmd_permissions", Category.MODEL, ""),
        new Command("/config", "terminow_keybar_cmd_config", Category.MODEL, ""),
        new Command("/status", "terminow_keybar_cmd_status", Category.MODEL, ""),
        new Command("/theme", "terminow_keybar_cmd_theme", Category.MODEL, ""),
        new Command("/output-style", "terminow_keybar_cmd_output_style", Category.MODEL, "[style]"),
        new Command("/tui", "terminow_keybar_cmd_tui", Category.MODEL, "[default|fullscreen]"),
        new Command("/init", "terminow_keybar_cmd_init", Category.PROJECT, ""),
        new Command("/memory", "terminow_keybar_cmd_memory", Category.PROJECT, ""),
        new Command("/add-dir", "terminow_keybar_cmd_add_dir", Category.PROJECT, "<path>"),
        new Command("/cd", "terminow_keybar_cmd_cd", Category.PROJECT, "<path>"),
        new Command("/diff", "terminow_keybar_cmd_diff", Category.PROJECT, ""),
        new Command("/review", "terminow_keybar_cmd_review", Category.PROJECT, "[pr|branch]"),
        new Command("/security-review", "terminow_keybar_cmd_security_review", Category.PROJECT, ""),
        new Command("/simplify", "terminow_keybar_cmd_simplify", Category.PROJECT, "[target]"),
        new Command("/goal", "terminow_keybar_cmd_goal", Category.PROJECT, "[condition|clear]"),
        new Command("/mcp", "terminow_keybar_cmd_mcp", Category.TOOLS, ""),
        new Command("/agents", "terminow_keybar_cmd_agents", Category.TOOLS, ""),
        new Command("/skills", "terminow_keybar_cmd_skills", Category.TOOLS, ""),
        new Command("/hooks", "terminow_keybar_cmd_hooks", Category.TOOLS, ""),
        new Command("/plugin", "terminow_keybar_cmd_plugin", Category.TOOLS, "[subcommand]"),
        new Command("/tasks", "terminow_keybar_cmd_tasks", Category.TOOLS, ""),
        new Command("/doctor", "terminow_keybar_cmd_doctor", Category.TOOLS, ""),
        new Command("/debug", "terminow_keybar_cmd_debug", Category.TOOLS, "[description]"),
        new Command("/statusline", "terminow_keybar_cmd_statusline", Category.TOOLS, ""),
        new Command("/keybindings", "terminow_keybar_cmd_keybindings", Category.TOOLS, ""),
        new Command("/release-notes", "terminow_keybar_cmd_release_notes", Category.TOOLS, ""),
        new Command("/bug", "terminow_keybar_cmd_bug", Category.TOOLS, "[report]"),
        new Command("/usage", "terminow_keybar_cmd_usage", Category.ACCOUNT, ""),
        new Command("/login", "terminow_keybar_cmd_login", Category.ACCOUNT, ""),
        new Command("/logout", "terminow_keybar_cmd_logout", Category.ACCOUNT, ""),
        new Command("/upgrade", "terminow_keybar_cmd_upgrade", Category.ACCOUNT, "")));

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

    // Commands/shortcuts below are from each tool's documentation (verified, not guessed); when a
    // tool's set is uncertain the list stays small and Help is always present. See the research notes.

    public static final List<Command> GEMINI_COMMANDS = Collections.unmodifiableList(Arrays.asList(
        aiCommand("/help", "", "List the available commands"),
        aiCommand("/clear", "", "Clear the screen"),
        aiCommand("/chat", "[save|resume|list]", "Save, resume or list chats"),
        aiCommand("/compress", "", "Compress the conversation context"),
        aiCommand("/memory", "[show|add|refresh]", "Manage memory (GEMINI.md)"),
        aiCommand("/tools", "", "List the available tools"),
        aiCommand("/mcp", "", "List MCP servers"),
        aiCommand("/stats", "", "Show session statistics"),
        aiCommand("/theme", "", "Change the theme"),
        aiCommand("/about", "", "Show version and account info"),
        aiCommand("/copy", "", "Copy the last output"),
        aiCommand("/quit", "", "Exit")));

    public static final List<Command> CODEX_COMMANDS = Collections.unmodifiableList(Arrays.asList(
        aiCommand("/model", "", "Choose the model"),
        aiCommand("/approvals", "", "Choose the approval preset"),
        aiCommand("/new", "", "Start a new conversation"),
        aiCommand("/init", "", "Create an AGENTS.md"),
        aiCommand("/diff", "", "Show the git diff"),
        aiCommand("/compact", "", "Compact the context"),
        aiCommand("/status", "", "Show the session status"),
        aiCommand("/quit", "", "Exit")));

    public static final List<Command> CURSOR_COMMANDS = Collections.unmodifiableList(Arrays.asList(
        aiCommand("/model", "[filter]", "Choose the model"),
        aiCommand("/plan", "", "Switch to Plan mode"),
        aiCommand("/ask", "", "Switch to Ask (read-only) mode"),
        aiCommand("/debug", "[prompt]", "Toggle Debug mode"),
        aiCommand("/logs", "", "Show the debug log path"),
        aiCommand("/rename", "", "Rename the chat"),
        aiCommand("/clear", "", "Start a new chat"),
        aiCommand("/about", "", "Show CLI and account info")));

    public static final List<Shortcut> GEMINI_SHORTCUTS = Collections.unmodifiableList(Arrays.asList(
        aiShortcut("Esc", "ESC", "Cancel the current request"),
        new Shortcut("Ctrl+C", "CTRL c", "terminow_keybar_key_ctrl_c"),
        new Shortcut("Ctrl+L", "CTRL l", "terminow_keybar_key_ctrl_l"),
        aiShortcut("@", "@", "Add a file to the context"),
        aiShortcut("!", "!", "Run a shell command")));

    public static final List<Shortcut> CODEX_SHORTCUTS = Collections.unmodifiableList(Arrays.asList(
        aiShortcut("Esc", "ESC", "Interrupt the current task"),
        new Shortcut("Tab", "TAB", "terminow_keybar_key_claude_tab"),
        new Shortcut("Ctrl+C", "CTRL c", "terminow_keybar_key_ctrl_c"),
        new Shortcut("Ctrl+L", "CTRL l", "terminow_keybar_key_ctrl_l")));

    public static final List<Shortcut> CURSOR_SHORTCUTS = Collections.unmodifiableList(Arrays.asList(
        aiShortcut("Esc", "ESC", "Cancel"),
        aiShortcut("Shift+Tab", "SHIFT TAB", "Rotate Agent / Plan / Ask modes"),
        new Shortcut("Ctrl+C", "CTRL c", "terminow_keybar_key_ctrl_c"),
        aiShortcut("@", "@", "Add a file to the context")));

    /** The commands put on the prompt for an AI, by its mode; empty for a shell. */
    @NonNull
    public static List<Command> commandsForProvider(@NonNull Mode mode) {
        switch (mode) {
            case CLAUDE: return CLAUDE_COMMANDS;
            case CURSOR: return CURSOR_COMMANDS;
            case CODEX: return CODEX_COMMANDS;
            case GEMINI: return GEMINI_COMMANDS;
            default: return Collections.emptyList();
        }
    }

    /** The key shortcuts for a mode; the terminal set for a shell. */
    @NonNull
    public static List<Shortcut> shortcutsForProvider(@NonNull Mode mode) {
        switch (mode) {
            case CLAUDE: return CLAUDE_SHORTCUTS;
            case CURSOR: return CURSOR_SHORTCUTS;
            case CODEX: return CODEX_SHORTCUTS;
            case GEMINI: return GEMINI_SHORTCUTS;
            default: return TERMINAL_SHORTCUTS;
        }
    }

    /** The provider's display name, for the picker and the icon label. */
    @NonNull
    public static String displayName(@NonNull Mode mode) {
        switch (mode) {
            case CLAUDE: return "Claude Code";
            case CURSOR: return "Cursor CLI";
            case CODEX: return "Codex CLI";
            case GEMINI: return "Gemini CLI";
            default: return "Shell";
        }
    }

    /**
     * The text that a command puts on the prompt: the command alone, with no Enter or line
     * break, so that Claude Code only runs it when the user sends it.
     */
    @NonNull
    public static String promptText(@NonNull Command command) {
        // One that needs more gets a space, for the user to type it after
        String text = command.text.replace("\r", "").replace("\n", "");
        return command.needsArgument() ? text + " " : text;
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
     * Which AI CLI a process is, by its program name or its npm install, or {@code null} for none.
     * Claude, Cursor ({@code cursor-agent}), Codex and Gemini are matched by their real executables;
     * the bare name {@code agent} is deliberately not matched, so ssh-agent and the like never count.
     */
    @Nullable
    public static Mode providerOf(@NonNull List<String> argv) {
        if (argv.isEmpty()) return null;
        if (isClaudeProcess(argv)) return Mode.CLAUDE;
        String program = argv.get(0);
        String name = program.substring(program.lastIndexOf('/') + 1);
        if (name.equals("cursor-agent")) return Mode.CURSOR;
        if (name.equals("codex")) return Mode.CODEX;
        if (name.equals("gemini")) return Mode.GEMINI;
        if (program.contains("/cursor-agent/")) return Mode.CURSOR;
        if (name.equals("node") || name.equals("nodejs")) {
            for (int i = 1; i < argv.size() && i < 4; i++) {
                String arg = argv.get(i);
                if (arg.contains("@openai/codex") || arg.endsWith("/bin/codex")) return Mode.CODEX;
                if (arg.contains("@google/gemini-cli") || arg.endsWith("/bin/gemini")) return Mode.GEMINI;
                if (arg.contains("cursor-agent") || arg.contains("@cursor/")) return Mode.CURSOR;
            }
        }
        return null;
    }

    /**
     * Whether Claude Code runs in the processes that a tab started: the process of the tab and
     * all that came from it, as /proc shows them to the app (its own processes, also those of
     * proot, which keep their arguments). Processes that can not be read are left out.
     *
     * @param proc The /proc folder, another one in tests.
     */
    public static boolean isClaudeRunningUnder(@NonNull File proc, int rootPid) {
        return providerRunningUnder(proc, rootPid) == Mode.CLAUDE;
    }

    /**
     * Which AI CLI runs in the processes a tab started: the tab's process and all descended from it,
     * as /proc shows them (the app's own, and proot's, which keep their arguments). The first AI found
     * in the tree is returned, or {@code null} for none. Processes that can not be read are skipped.
     *
     * @param proc The /proc folder, another one in tests.
     */
    @Nullable
    public static Mode providerRunningUnder(@NonNull File proc, int rootPid) {
        if (rootPid <= 0) return null;
        File[] entries = proc.listFiles();
        if (entries == null) return null;
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
            if (argv != null) {
                Mode provider = providerOf(argv);
                if (provider != null) return provider;
            }
            List<Integer> list = children.get(pid);
            if (list != null) todo.addAll(list);
        }
        return null;
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
