package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/** Scheduling math and retention selection (both pure). */
public class TerminowBackupPolicyTest {

    private static long at(int year, int month1to12, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(year, month1to12 - 1, day, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static Calendar cal(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c;
    }

    @Test
    public void dailyNextRunIsLaterTodayOrTomorrow() {
        TerminowBackupSchedule s = new TerminowBackupSchedule();
        s.frequency = TerminowBackupSchedule.Frequency.DAILY;
        s.hour = 2; s.minute = 30;

        // now is 01:00 -> runs today at 02:30
        long now = at(2026, 6, 15, 1, 0);
        Calendar next = cal(s.nextRunAfter(now));
        assertTrue(next.getTimeInMillis() > now);
        assertEquals(2, next.get(Calendar.HOUR_OF_DAY));
        assertEquals(30, next.get(Calendar.MINUTE));
        assertEquals(15, next.get(Calendar.DAY_OF_MONTH));

        // now is 03:00 -> already past, runs tomorrow at 02:30
        long now2 = at(2026, 6, 15, 3, 0);
        Calendar next2 = cal(s.nextRunAfter(now2));
        assertEquals(16, next2.get(Calendar.DAY_OF_MONTH));
        assertEquals(2, next2.get(Calendar.HOUR_OF_DAY));
    }

    @Test
    public void weeklyNextRunLandsOnTheChosenDayInTheFuture() {
        TerminowBackupSchedule s = new TerminowBackupSchedule();
        s.frequency = TerminowBackupSchedule.Frequency.WEEKLY;
        s.dayOfWeek = Calendar.MONDAY;
        s.hour = 4; s.minute = 0;

        long now = at(2026, 6, 15, 12, 0);   // whatever weekday this is
        long next = s.nextRunAfter(now);
        Calendar c = cal(next);
        assertTrue("in the future", next > now);
        assertEquals("on Monday", Calendar.MONDAY, c.get(Calendar.DAY_OF_WEEK));
        assertEquals(4, c.get(Calendar.HOUR_OF_DAY));
        assertTrue("within a week", next - now <= 7L * 24 * 60 * 60 * 1000);
    }

    @Test
    public void jsonRoundTripsAndClampsBadValues() throws Exception {
        TerminowBackupSchedule s = new TerminowBackupSchedule();
        s.enabled = true;
        s.frequency = TerminowBackupSchedule.Frequency.WEEKLY;
        s.hour = 23; s.minute = 45;
        s.dayOfWeek = Calendar.FRIDAY;
        s.wifiOnly = false; s.requireCharging = true;
        s.retain = 5; s.destination = "s3"; s.skipWhenActive = false;
        s.lastSuccessMillis = 123456; s.lastFailureMillis = 654321; s.lastFailureReason = "network";

        TerminowBackupSchedule back = TerminowBackupSchedule.fromJson(s.toJson().toString());
        assertTrue(back.enabled);
        assertEquals(TerminowBackupSchedule.Frequency.WEEKLY, back.frequency);
        assertEquals(23, back.hour);
        assertEquals(45, back.minute);
        assertEquals(Calendar.FRIDAY, back.dayOfWeek);
        assertFalse(back.wifiOnly);
        assertTrue(back.requireCharging);
        assertEquals(5, back.retain);
        assertFalse(back.skipWhenActive);
        assertEquals(123456, back.lastSuccessMillis);
        assertEquals("network", back.lastFailureReason);

        // Corrupt JSON -> safe defaults (disabled), never throws.
        TerminowBackupSchedule def = TerminowBackupSchedule.fromJson("{not valid");
        assertFalse(def.enabled);
        // retain is floored at 1 so a scheduled run can never be told to keep zero backups.
        TerminowBackupSchedule zero = TerminowBackupSchedule.fromJson("{\"retain\":0}");
        assertEquals(1, zero.retain);
    }

    // --- retention ---

    private static TerminowBackupProvider.Remote r(String key, long modified) {
        return new TerminowBackupProvider.Remote(key, 0, modified);
    }

    @Test
    public void retentionDeletesTheOldestBeyondRetain() {
        List<TerminowBackupProvider.Remote> list = new ArrayList<>(Arrays.asList(
            r("terminow-backups/u/1", 1000),
            r("terminow-backups/u/5", 5000),
            r("terminow-backups/u/3", 3000),
            r("terminow-backups/u/2", 2000),
            r("terminow-backups/u/4", 4000)));
        List<String> toDelete = TerminowBackupRetention.keysToDelete(list, 3);
        // keep 5,4,3 (newest); delete 2,1 (oldest)
        assertEquals(Arrays.asList("terminow-backups/u/2", "terminow-backups/u/1"), toDelete);
    }

    @Test
    public void retentionKeepsAllWhenFewerThanRetain() {
        List<TerminowBackupProvider.Remote> list = new ArrayList<>(Arrays.asList(
            r("terminow-backups/u/1", 1000), r("terminow-backups/u/2", 2000)));
        assertTrue(TerminowBackupRetention.keysToDelete(list, 3).isEmpty());
        assertTrue(TerminowBackupRetention.keysToDelete(list, 2).isEmpty());
    }

    @Test
    public void retentionNeverDeletesEverythingOnABadRetain() {
        List<TerminowBackupProvider.Remote> list = new ArrayList<>(Arrays.asList(
            r("terminow-backups/u/1", 1000), r("terminow-backups/u/2", 2000)));
        assertTrue("retain 0 is treated as keep-all", TerminowBackupRetention.keysToDelete(list, 0).isEmpty());
        assertTrue("negative retain keeps all", TerminowBackupRetention.keysToDelete(list, -1).isEmpty());
    }
}
