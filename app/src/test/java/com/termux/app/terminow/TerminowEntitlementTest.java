package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class TerminowEntitlementTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    private static JSONObject premium(boolean active, String due, long checked) throws Exception {
        return new JSONObject().put("premium", active).put("plan", "TermiNow Premium")
            .put("nextDueDate", due == null ? JSONObject.NULL : due).put("checked", checked);
    }

    @Test
    public void states() throws Exception {
        long now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).parse("2026-10-12 12:00").getTime();
        assertEquals(TerminowEntitlement.State.SIGNED_OUT, TerminowEntitlement.decide(false, false, null, now));
        assertEquals(TerminowEntitlement.State.UNKNOWN, TerminowEntitlement.decide(true, false, null, now));
        assertEquals(TerminowEntitlement.State.UNKNOWN, TerminowEntitlement.decide(true, true, null, now));
        assertEquals(TerminowEntitlement.State.FREE, TerminowEntitlement.decide(true, true, premium(false, null, now), now));
        assertEquals(TerminowEntitlement.State.ACTIVE, TerminowEntitlement.decide(true, true, premium(true, "2027-10-12", now - DAY), now));
        assertEquals(TerminowEntitlement.State.ACTIVE, TerminowEntitlement.decide(true, true, premium(true, null, now - 6 * DAY), now));
        // Due today is still active, due yesterday is expired
        assertEquals(TerminowEntitlement.State.ACTIVE, TerminowEntitlement.decide(true, true, premium(true, "2026-10-12", now), now));
        assertEquals(TerminowEntitlement.State.EXPIRED, TerminowEntitlement.decide(true, true, premium(true, "2026-10-11", now), now));
        // Offline for longer than a week needs a new check
        assertEquals(TerminowEntitlement.State.NEEDS_CHECK, TerminowEntitlement.decide(true, true, premium(true, null, now - 8 * DAY), now));
        // A check time in the future, like after changing the clock, is not trusted
        assertEquals(TerminowEntitlement.State.NEEDS_CHECK, TerminowEntitlement.decide(true, true, premium(true, null, now + 30 * DAY), now));
        // An unverified email never has Premium, whatever was cached
        assertEquals(TerminowEntitlement.State.UNKNOWN, TerminowEntitlement.decide(true, false, premium(true, null, now), now));
    }

}
