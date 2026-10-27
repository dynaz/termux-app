package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure decision logic for "AI Agents in Ubuntu": which AI CLIs to offer for one environment, which
 * conversation options are actually supported, and how a remembered choice is validated. No Android
 * dependencies, so it is unit-tested directly.
 *
 * <p>Only AI coding agents from {@link TerminowToolInstaller.Tool} are offered, and only when the
 * environment's own {@link TerminowToolInstaller.Detection} reports them installed — never the
 * installed state of another distro. Conversation options other than a new conversation are offered
 * only when the CLI was verified to support them; today that is Claude Code alone (from its checked
 * {@code --continue}/{@code --resume}). Other agents start a new conversation only, so no Claude flag
 * is ever applied to a different agent.</p>
 */
public final class TerminowAiAgentsModel {

    /** The AI coding agents, in the order they are shown. Mirrors the CAT_AI set of the Tools Library. */
    private static final TerminowToolInstaller.Tool[] AI_TOOLS = {
        TerminowToolInstaller.Tool.CLAUDE,
        TerminowToolInstaller.Tool.CURSOR,
        TerminowToolInstaller.Tool.CODEX,
        TerminowToolInstaller.Tool.GEMINI,
        TerminowToolInstaller.Tool.GROK,
        TerminowToolInstaller.Tool.OPENCODE,
        TerminowToolInstaller.Tool.AIDER,
        TerminowToolInstaller.Tool.HERMES,
    };

    /** A conversation entry point a CLI supports. */
    public enum Conversation { NEW, CONTINUE, RESUME }

    private TerminowAiAgentsModel() {}

    public static boolean isAiTool(@NonNull TerminowToolInstaller.Tool tool) {
        for (TerminowToolInstaller.Tool t : AI_TOOLS) if (t == tool) return true;
        return false;
    }

    /** The AI agents detected as installed in this environment, in display order (never another distro's). */
    @NonNull
    public static List<TerminowToolInstaller.Tool> installed(@Nullable TerminowToolInstaller.Detection detection) {
        List<TerminowToolInstaller.Tool> out = new ArrayList<>();
        if (detection == null) return out;
        for (TerminowToolInstaller.Tool tool : AI_TOOLS) {
            String v = detection.versions.get(tool);
            if (v != null) out.add(tool);   // present in the map == found installed
        }
        return out;
    }

    /** The detected version string for a tool, or empty when not known/readable. */
    @NonNull
    public static String versionOf(@Nullable TerminowToolInstaller.Detection detection,
                                   @NonNull TerminowToolInstaller.Tool tool) {
        if (detection == null) return "";
        String v = detection.versions.get(tool);
        return v == null ? "" : v.trim();
    }

    /**
     * The conversation options to offer. Only verified capabilities are returned: Claude's continue and
     * resume come from its checked help; every other agent gets {@link Conversation#NEW} only.
     */
    @NonNull
    public static List<Conversation> conversations(@NonNull TerminowToolInstaller.Tool tool,
                                                   boolean claudeSupportsContinue, boolean claudeSupportsResume) {
        List<Conversation> out = new ArrayList<>();
        out.add(Conversation.NEW);
        if (tool == TerminowToolInstaller.Tool.CLAUDE) {
            if (claudeSupportsContinue) out.add(Conversation.CONTINUE);
            if (claudeSupportsResume) out.add(Conversation.RESUME);
        }
        return out;
    }

    /** The binary name on PATH for an AI tool (its registry id). Safe to embed in a launch script. */
    @NonNull
    public static String binaryOf(@NonNull TerminowToolInstaller.Tool tool) {
        return tool.id;
    }

    /**
     * Resolve a remembered agent id against what is installed now: returns the tool only if it is a
     * known AI agent AND still installed in this environment, else null so the picker is shown again.
     */
    @Nullable
    public static TerminowToolInstaller.Tool resolveRemembered(@Nullable String rememberedId,
                                                               @NonNull List<TerminowToolInstaller.Tool> installed) {
        if (rememberedId == null || rememberedId.isEmpty()) return null;
        for (TerminowToolInstaller.Tool tool : installed) {
            if (tool.id.equals(rememberedId) && isAiTool(tool)) return tool;
        }
        return null;
    }

    /** The AI agents in display order (for callers that want the full catalog, e.g. an empty-state hint). */
    @NonNull
    public static List<TerminowToolInstaller.Tool> catalog() {
        return Arrays.asList(AI_TOOLS);
    }
}
