package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Retention for scheduled backups: which backups to delete so that only the newest {@code retain}
 * are kept. It is applied <b>only after a new backup has succeeded</b> (the caller's job), and it is
 * handed only the backups the provider lists under this feature's own prefix, so it can never delete
 * a user's unrelated files. As a safety rule it never deletes everything: a {@code retain} below 1 is
 * treated as "keep all". Pure and unit-tested.
 */
final class TerminowBackupRetention {

    private TerminowBackupRetention() {}

    /**
     * The keys to delete: everything except the newest {@code retain} by modified time. Ties keep a
     * stable order. Returns empty when {@code retain < 1}, or when there is nothing to trim.
     */
    @NonNull
    static List<String> keysToDelete(@NonNull List<TerminowBackupProvider.Remote> existing, int retain) {
        if (retain < 1 || existing.size() <= retain) return new ArrayList<>();
        List<TerminowBackupProvider.Remote> sorted = new ArrayList<>(existing);
        // Newest first; a stable sort keeps equal-time entries in their given order.
        Collections.sort(sorted, new Comparator<TerminowBackupProvider.Remote>() {
            @Override public int compare(TerminowBackupProvider.Remote a, TerminowBackupProvider.Remote b) {
                return Long.compare(b.modifiedMillis, a.modifiedMillis);
            }
        });
        List<String> toDelete = new ArrayList<>();
        for (int i = retain; i < sorted.size(); i++) toDelete.add(sorted.get(i).key);
        return toDelete;
    }
}
