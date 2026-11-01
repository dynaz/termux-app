package com.termux.app.terminow;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * The settings for a scheduled backup, plus its status. Serialized to JSON in the store. The actual
 * scheduling is done by WorkManager (which decides exactly when a periodic job runs, and may delay it
 * to batch work, respect Doze, or wait for the Wi-Fi/charging constraints); this class only holds the
 * user's choices, computes the next wanted run time, and records the last success and failure. It is
 * pure and unit-tested.
 */
final class TerminowBackupSchedule {

    enum Frequency { DAILY, WEEKLY }

    boolean enabled = false;
    Frequency frequency = Frequency.DAILY;
    int hour = 2;                       // 02:00 by default, a quiet hour
    int minute = 0;
    int dayOfWeek = Calendar.SUNDAY;    // for WEEKLY
    boolean wifiOnly = true;
    boolean requireCharging = false;
    int retain = 3;                     // keep the last N successful backups
    String destination = "s3";
    /** If a full backup needs the distro stopped and it is active, skip and notify rather than kill it. */
    boolean skipWhenActive = true;

    long lastSuccessMillis = 0;
    long lastFailureMillis = 0;
    String lastFailureReason = "";

    @NonNull
    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("enabled", enabled);
        o.put("frequency", frequency.name());
        o.put("hour", hour);
        o.put("minute", minute);
        o.put("dayOfWeek", dayOfWeek);
        o.put("wifiOnly", wifiOnly);
        o.put("requireCharging", requireCharging);
        o.put("retain", retain);
        o.put("destination", destination);
        o.put("skipWhenActive", skipWhenActive);
        o.put("lastSuccessMillis", lastSuccessMillis);
        o.put("lastFailureMillis", lastFailureMillis);
        o.put("lastFailureReason", lastFailureReason);
        return o;
    }

    @NonNull
    static TerminowBackupSchedule fromJson(@NonNull String json) {
        TerminowBackupSchedule s = new TerminowBackupSchedule();
        try {
            JSONObject o = new JSONObject(json);
            s.enabled = o.optBoolean("enabled", false);
            try { s.frequency = Frequency.valueOf(o.optString("frequency", "DAILY")); } catch (IllegalArgumentException ignored) {}
            s.hour = clamp(o.optInt("hour", 2), 0, 23);
            s.minute = clamp(o.optInt("minute", 0), 0, 59);
            s.dayOfWeek = clamp(o.optInt("dayOfWeek", Calendar.SUNDAY), Calendar.SUNDAY, Calendar.SATURDAY);
            s.wifiOnly = o.optBoolean("wifiOnly", true);
            s.requireCharging = o.optBoolean("requireCharging", false);
            s.retain = Math.max(1, o.optInt("retain", 3));
            s.destination = o.optString("destination", "s3");
            s.skipWhenActive = o.optBoolean("skipWhenActive", true);
            s.lastSuccessMillis = o.optLong("lastSuccessMillis", 0);
            s.lastFailureMillis = o.optLong("lastFailureMillis", 0);
            s.lastFailureReason = o.optString("lastFailureReason", "");
        } catch (JSONException ignored) {
            // A corrupt value leaves the defaults; the schedule simply shows as off.
        }
        return s;
    }

    /** The next wall-clock time (ms) at or after {@code now} that matches this schedule. */
    long nextRunAfter(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (frequency == Frequency.WEEKLY) {
            c.set(Calendar.DAY_OF_WEEK, dayOfWeek);
            while (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 7);
        } else {
            if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    /** The approximate period for a WorkManager PeriodicWorkRequest. */
    long periodMillis() {
        return frequency == Frequency.WEEKLY ? 7L * 24 * 60 * 60 * 1000 : 24L * 60 * 60 * 1000;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
