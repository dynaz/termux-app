package com.termux.app.terminow;

import androidx.annotation.NonNull;

/**
 * A QuickCut: a piece of text the person saves to insert into an open terminal session — a command,
 * a URL, a snippet of text, an AI prompt. It is deliberately <em>not</em> a {@link TerminowStore.Snippet}:
 * a Snippet is a startup command run when a session opens, while a QuickCut is inserted on demand into
 * a session that is already running and is never run on its own.
 *
 * <p>Model only; {@link TerminowQuickCuts} stores and orders them, and never sends a QuickCut anywhere.
 */
public final class TerminowQuickCut {

    /** A stable id, used to target an edit or a delete; it never changes once created. */
    public String id = "";
    public String name = "";
    public String content = "";
    public boolean pinned = false;
    /** Incremented only after the content was inserted into a terminal successfully. */
    public int usageCount = 0;
    /** When it was last used (epoch millis), 0 if never. */
    public long lastUsedAt = 0;

    public TerminowQuickCut() {}

    public TerminowQuickCut(@NonNull String id, @NonNull String name, @NonNull String content) {
        this.id = id;
        this.name = name;
        this.content = content;
    }

    /** The label shown in lists: the name, or the first line of the content when there is no name. */
    @NonNull
    public String getTitle() {
        if (!name.isEmpty()) return name;
        return TerminowQuickCuts.preview(content, 40);
    }
}
