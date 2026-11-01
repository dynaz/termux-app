package com.termux.app.terminow;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.termux.R;
import com.termux.app.terminow.linux.TerminowLinuxInstaller;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Runs one scheduled cloud backup. WorkManager calls this when the schedule's time and constraints
 * (Wi-Fi/charging) are met. It reads the schedule and the S3 config, then — unless the environment is
 * in use (skip, never stop the user's work) or Premium has lapsed — makes a full backup to a cache
 * file and uploads it through the same {@link TerminowPhoneBackup} pipeline the manual backup uses,
 * recording the last success or failure back into the schedule so the UI can show it.
 *
 * <p>Whether a background worker may actually run proot-distro (native exec, Doze, battery policy)
 * must be checked on a real device; here it is compile-checked. It never deletes or overwrites the
 * environment, and retention only ever trims this feature's own cloud backups.</p>
 */
public final class TerminowBackupWorker extends Worker {

    private static final String CHANNEL_ID = "terminow_scheduled_backup";
    private static final int NOTIFICATION_ID = 7342;
    private static final long POLL_MS = 2000;
    private static final long MAX_WAIT_MS = 6L * 60 * 60 * 1000;   // give a large backup room, but never hang forever

    public TerminowBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        TerminowStore store = new TerminowStore(context);
        TerminowBackupSchedule schedule = store.getBackupSchedule();

        if (!schedule.enabled || schedule.alias.isEmpty()) return Result.success();
        if (!"s3".equals(schedule.destination)) return record(store, schedule, "This TermiNow build can only run scheduled backups to S3 storage");
        if (!schedule.full) return record(store, schedule, "Scheduled folder backups are not supported yet; choose a full backup");

        TerminowS3Config config = store.getS3Config();
        if (config == null || !config.isComplete())
            return record(store, schedule, "No cloud storage is set up for scheduled backups");
        if (!TerminowPhoneBackup.mayBackUp(store))
            return record(store, schedule, "Premium is needed for scheduled backups; existing backups are still available to restore");
        if (schedule.skipWhenActive && TerminowDistroState.isActive(schedule.alias))
            return record(store, schedule, "Skipped: " + schedule.alias + " was in use. It will try again at the next scheduled time.");
        if (TerminowPhoneBackup.isRunning()) return Result.retry();   // a backup is already running; try later

        long now = System.currentTimeMillis();
        File cacheDir = new File(context.getCacheDir(), "cloud-backup");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        boolean encrypt = !config.encryptionPassword.isEmpty();
        String fileName = TerminowBackupArchive.fileName(schedule.alias, TerminowBackupArchive.TYPE_FULL, now) + (encrypt ? ".enc" : "");
        File temp = new File(cacheDir, now + "-" + fileName);

        TerminowPhoneBackup.Request request = new TerminowPhoneBackup.Request();
        request.kind = TerminowPhoneBackup.Kind.BACKUP_FULL;
        request.alias = schedule.alias;
        request.title = schedule.alias;
        request.distro = schedule.alias;
        request.architecture = TerminowLinuxUi.deviceArch();
        request.appVersion = "TermiNow " + com.termux.BuildConfig.VERSION_NAME + " (" + com.termux.BuildConfig.VERSION_CODE + ")";
        request.password = encrypt ? config.encryptionPassword.toCharArray() : null;
        request.localFile = temp;
        request.provider = config.toProvider();
        request.remoteKey = TerminowCloudBackup.keyFor(schedule.alias, fileName);
        request.retain = schedule.retain;

        try {
            TerminowPhoneBackup.Job job = TerminowPhoneBackup.start(context, store, runner(context), TermuxConstants.TERMUX_PREFIX_DIR_PATH, request);
            if (job == null) return Result.retry();   // became busy, or Premium check failed between here and start
            long waited = 0;
            while (job.state == TerminowPhoneBackup.State.RUNNING && waited < MAX_WAIT_MS) {
                try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                waited += POLL_MS;
            }
            if (job.state == TerminowPhoneBackup.State.SUCCEEDED) {
                schedule.lastSuccessMillis = System.currentTimeMillis();
                schedule.lastFailureReason = "";
                store.setBackupSchedule(schedule);
                return Result.success();
            }
            return record(store, schedule, job.state == TerminowPhoneBackup.State.RUNNING
                ? "The backup was still running after a long time and was left to finish"
                : (job.error == null || job.error.isEmpty() ? "The backup did not finish" : job.error));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    /** Record a failure/skip reason in the schedule, notify, and end successfully (the next period retries). */
    @NonNull
    private Result record(@NonNull TerminowStore store, @NonNull TerminowBackupSchedule schedule, @NonNull String reason) {
        schedule.lastFailureMillis = System.currentTimeMillis();
        schedule.lastFailureReason = reason;
        store.setBackupSchedule(schedule);
        notify(getApplicationContext(), reason);
        return Result.success();
    }

    private void notify(@NonNull Context context, @NonNull String text) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.terminow_backup_schedule_channel), NotificationManager.IMPORTANCE_LOW));
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(context.getString(R.string.terminow_backup_schedule_notification_title))
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true);
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    @NonNull
    private static TerminowLinuxInstaller.Runner runner(@NonNull Context context) {
        final Context app = context.getApplicationContext();
        return new TerminowLinuxInstaller.Runner() {
            @NonNull
            @Override
            public java.lang.Process start(@NonNull List<String> command) throws IOException {
                return new TerminowClaudeSetup(app, new TerminowMcpTargets(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH),
                    new File(TermuxConstants.TERMUX_HOME_DIR_PATH))).start(command);
            }

            @Override
            public void interrupt(int pid) {
                android.os.Process.sendSignal(pid, 2);
            }
        };
    }
}
