package com.termux.app.terminow;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Registers (or cancels) the periodic WorkManager job for scheduled backups from a schedule. The job
 * runs at most as often as the schedule's period, only when the Wi-Fi/charging constraints are met;
 * WorkManager picks the exact moment and may delay it to batch work or respect Doze, so the time the
 * user set is a preference, not a guarantee. Calling {@link #apply} again replaces the registration,
 * so changing the schedule takes effect at once.
 */
final class TerminowBackupScheduler {

    static final String UNIQUE_WORK = "terminow-scheduled-backup";

    private TerminowBackupScheduler() {}

    /** Register the schedule, or cancel the job when it is disabled. */
    static void apply(@NonNull Context context, @NonNull TerminowBackupSchedule schedule) {
        WorkManager wm = WorkManager.getInstance(context.getApplicationContext());
        if (!schedule.enabled || schedule.alias.isEmpty()) {
            wm.cancelUniqueWork(UNIQUE_WORK);
            return;
        }
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(schedule.wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED)
            .setRequiresCharging(schedule.requireCharging)
            .build();

        long period = schedule.periodMillis();
        long initialDelay = Math.max(0, schedule.nextRunAfter(System.currentTimeMillis()) - System.currentTimeMillis());
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
            TerminowBackupWorker.class, period, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(UNIQUE_WORK)
            .build();
        // UPDATE keeps the next run's timing when only constraints change, and re-registers otherwise.
        wm.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    static void cancel(@NonNull Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(UNIQUE_WORK);
    }
}
