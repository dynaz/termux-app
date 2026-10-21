package com.termux.app.terminow;

import androidx.annotation.NonNull;

/**
 * What Open Ubuntu and Open Claude do, from what the user chose to remember for each. A tab is
 * only gone back to while it runs; one that ended is never offered to be resumed. A tab and a
 * conversation of Claude Code are different things: a conversation always starts Claude Code
 * in a new tab with its flag, never typed into a tab that runs.
 */
public final class TerminowLaunchChoice {

    private TerminowLaunchChoice() {}

    public enum Action {
        /** Show the picker. */
        ASK,
        /** Show the tab that runs: attach to it, nothing is started or typed. */
        RESUME,
        /** Start the environment in the TermiNow shell of the tab that is open, when it is idle. */
        CURRENT_TAB,
        /** A new terminal session; the environment itself is never installed again. */
        NEW_TAB,
        /** Claude Code in a new tab with the last conversation: --continue. */
        CONTINUE,
        /** Claude Code in a new tab with its picker of earlier conversations: --resume. */
        PICK_CONVERSATION
    }

    /** Taps on Open or New tab closer together than this start only one tab. */
    public static final long REPEAT_TAP_MS = 1500;

    @NonNull
    public static Action ubuntu(@NonNull String behavior, boolean running) {
        switch (behavior) {
            case TerminowStore.UBUNTU_LAUNCH_RESUME: return running ? Action.RESUME : Action.NEW_TAB;
            case TerminowStore.UBUNTU_LAUNCH_CURRENT: return Action.CURRENT_TAB;
            case TerminowStore.UBUNTU_LAUNCH_NEW: return Action.NEW_TAB;
            default: return Action.ASK;
        }
    }

    /**
     * @param supportsContinue Whether the installed Claude Code has --continue, as its --help says.
     */
    @NonNull
    public static Action claude(@NonNull String behavior, boolean running, boolean supportsContinue) {
        switch (behavior) {
            case TerminowStore.CLAUDE_LAUNCH_RESUME: return running ? Action.RESUME : Action.NEW_TAB;
            case TerminowStore.CLAUDE_LAUNCH_NEW: return Action.NEW_TAB;
            // What was remembered is asked again when the installed version can not do it
            case TerminowStore.CLAUDE_LAUNCH_CONTINUE: return supportsContinue ? Action.CONTINUE : Action.ASK;
            default: return Action.ASK;
        }
    }

    /** What to remember for a choice of the Ubuntu picker, {@code null} for one that is not remembered. */
    public static String ubuntuBehaviorOf(@NonNull Action action) {
        switch (action) {
            case RESUME: return TerminowStore.UBUNTU_LAUNCH_RESUME;
            case CURRENT_TAB: return TerminowStore.UBUNTU_LAUNCH_CURRENT;
            case NEW_TAB: return TerminowStore.UBUNTU_LAUNCH_NEW;
            default: return null;
        }
    }

    public static String claudeBehaviorOf(@NonNull Action action) {
        switch (action) {
            case RESUME: return TerminowStore.CLAUDE_LAUNCH_RESUME;
            case NEW_TAB: return TerminowStore.CLAUDE_LAUNCH_NEW;
            case CONTINUE: return TerminowStore.CLAUDE_LAUNCH_CONTINUE;
            // Picking a conversation is a choice each time
            default: return null;
        }
    }

    /**
     * The arguments of Claude Code for a choice, {@code null} when the installed version does
     * not have the flag, which is then never passed.
     */
    public static String[] claudeArguments(@NonNull Action action, boolean supportsContinue, boolean supportsResume) {
        switch (action) {
            case CONTINUE: return supportsContinue ? new String[]{"--continue"} : null;
            case PICK_CONVERSATION: return supportsResume ? new String[]{"--resume"} : null;
            case NEW_TAB: return new String[0];
            default: return null;
        }
    }

    /** Whether a tap is a repeat of the one before for the same thing, which then starts nothing more. */
    public static boolean isRepeatTap(long lastTapMs, long nowMs) {
        return lastTapMs > 0 && nowMs >= lastTapMs && nowMs - lastTapMs < REPEAT_TAP_MS;
    }
}
