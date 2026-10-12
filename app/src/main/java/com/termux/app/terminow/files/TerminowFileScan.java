package com.termux.app.terminow.files;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Walking the folders that the app can access, for the file categories, the search of the File
 * Manager and the space analyzer. Links are never followed, the depth and the number of
 * results are limited, and a walk can be cancelled.
 */
public class TerminowFileScan {

    public enum Category {
        IMAGES("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg"),
        VIDEOS("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v"),
        AUDIO("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus"),
        DOCUMENTS("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "md", "csv", "rtf", "epub"),
        APK("apk", "apks", "xapk"),
        ARCHIVES("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst");

        private final Set<String> mExtensions;

        Category(String... extensions) {
            mExtensions = new HashSet<>(Arrays.asList(extensions));
        }

        public boolean matches(@NonNull String name) {
            return mExtensions.contains(extension(name));
        }

        /** The category of a file name, {@code null} if none. */
        @Nullable
        public static Category of(@NonNull String name) {
            for (Category category : values()) if (category.matches(name)) return category;
            return null;
        }
    }

    public static final int MAX_DEPTH = 16;
    public static final int MAX_RESULTS = 2000;

    @NonNull
    public static String extension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** A file that was found, with the folders down to it. */
    public static class Match {
        public final TerminowFs fs;
        public final TerminowFs.Node node;
        public final List<TerminowFs.Node> parents;

        Match(@NonNull TerminowFs fs, @NonNull TerminowFs.Node node, @NonNull List<TerminowFs.Node> parents) {
            this.fs = fs;
            this.node = node;
            this.parents = parents;
        }
    }

    public interface Filter {
        boolean accept(@NonNull TerminowFs.Node file);
    }

    public interface Progress {
        /** Called for each folder, from the worker. */
        void onFolder(@NonNull String name, int found);
    }

    /**
     * Find the files that the filter accepts under a folder. Hidden folders, like `.ssh` or
     * `.cache`, are only searched if {@code includeHidden}.
     */
    public static void find(@NonNull TerminowFs fs, @NonNull List<TerminowFs.Node> start, @NonNull Filter filter, boolean includeHidden,
                            @NonNull List<Match> results, @NonNull TerminowFs.Cancel cancel, @Nullable Progress progress) throws IOException {
        walk(fs, new ArrayList<>(start), filter, includeHidden, results, cancel, progress, 0);
    }

    private static void walk(@NonNull TerminowFs fs, @NonNull List<TerminowFs.Node> path, @NonNull Filter filter, boolean includeHidden,
                             @NonNull List<Match> results, @NonNull TerminowFs.Cancel cancel, @Nullable Progress progress, int depth)
        throws IOException {
        cancel.check();
        if (depth > MAX_DEPTH || results.size() >= MAX_RESULTS) return;
        TerminowFs.Node directory = path.get(path.size() - 1);
        if (progress != null) progress.onFolder(directory.name, results.size());
        List<TerminowFs.Node> children;
        try {
            children = fs.list(directory, cancel);
        } catch (TerminowFs.CancelledException e) {
            throw e;
        } catch (IOException e) {
            // A folder that can not be read is skipped, the others are still searched
            return;
        }
        for (TerminowFs.Node child : children) {
            cancel.check();
            if (child.link) continue;
            if (child.directory) {
                if (!includeHidden && child.isHidden()) continue;
                path.add(child);
                walk(fs, path, filter, includeHidden, results, cancel, progress, depth + 1);
                path.remove(path.size() - 1);
            } else if (filter.accept(child)) {
                results.add(new Match(fs, child, new ArrayList<>(path)));
                if (results.size() >= MAX_RESULTS) return;
            }
        }
    }

    /** The result of analyzing the space of a location. */
    public static class Space {
        public long totalBytes;
        public int files;
        public int folders;
        public boolean incomplete;
        /** The folders that could not be read, protected by Android or denied, which were not counted. */
        public final List<String> unreadable = new ArrayList<>();
        /** The folders and files at the top, with their sizes, the largest first. */
        public final List<Entry> top = new ArrayList<>();
        /** The largest files anywhere, the largest first. */
        public final List<Match> largest = new ArrayList<>();
    }

    public static class Entry {
        public final TerminowFs.Node node;
        public long bytes;

        Entry(@NonNull TerminowFs.Node node) {
            this.node = node;
        }
    }

    public static final int LARGEST_KEPT = 20;

    /** Add up the sizes under a folder, without following links. */
    @NonNull
    public static Space analyze(@NonNull TerminowFs fs, @NonNull TerminowFs.Node root, @NonNull TerminowFs.Cancel cancel,
                                @Nullable Progress progress) throws IOException {
        Space space = new Space();
        List<TerminowFs.Node> path = new ArrayList<>();
        path.add(root);
        for (TerminowFs.Node child : fs.list(root, cancel)) {
            cancel.check();
            if (child.link) continue;
            Entry entry = new Entry(child);
            if (child.directory) {
                space.folders++;
                path.add(child);
                entry.bytes = sizeOf(fs, path, space, cancel, progress, 1);
                path.remove(path.size() - 1);
            } else {
                entry.bytes = Math.max(0, child.size);
                space.files++;
                addLargest(space, new Match(fs, child, new ArrayList<>(path)));
            }
            space.totalBytes += entry.bytes;
            space.top.add(entry);
        }
        Collections.sort(space.top, (a, b) -> Long.compare(b.bytes, a.bytes));
        return space;
    }

    private static long sizeOf(@NonNull TerminowFs fs, @NonNull List<TerminowFs.Node> path, @NonNull Space space,
                               @NonNull TerminowFs.Cancel cancel, @Nullable Progress progress, int depth) throws IOException {
        cancel.check();
        if (depth > MAX_DEPTH * 2) {
            space.incomplete = true;
            return 0;
        }
        TerminowFs.Node directory = path.get(path.size() - 1);
        if (progress != null) progress.onFolder(directory.name, space.files);
        List<TerminowFs.Node> children;
        try {
            children = fs.list(directory, cancel);
        } catch (TerminowFs.CancelledException e) {
            throw e;
        } catch (IOException e) {
            space.incomplete = true;
            if (space.unreadable.size() < 50) space.unreadable.add(fs.getDisplayPath(path));
            return 0;
        }
        long total = 0;
        for (TerminowFs.Node child : children) {
            if (child.link) continue;
            if (child.directory) {
                space.folders++;
                path.add(child);
                total += sizeOf(fs, path, space, cancel, progress, depth + 1);
                path.remove(path.size() - 1);
            } else {
                space.files++;
                total += Math.max(0, child.size);
                addLargest(space, new Match(fs, child, new ArrayList<>(path)));
            }
        }
        return total;
    }

    private static void addLargest(@NonNull Space space, @NonNull Match match) {
        List<Match> largest = space.largest;
        if (largest.size() >= LARGEST_KEPT && largest.get(largest.size() - 1).node.size >= match.node.size) return;
        int index = 0;
        while (index < largest.size() && largest.get(index).node.size >= match.node.size) index++;
        largest.add(index, match);
        if (largest.size() > LARGEST_KEPT) largest.remove(largest.size() - 1);
    }

}
