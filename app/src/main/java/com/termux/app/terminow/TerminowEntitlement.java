package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Whether Premium features may be used, decided from the two sources that the server decides:
 * an active service of WHMCS (accmove.com, checked with premium.php) and a Google Play
 * subscription (verified by Firebase, read from Firestore). There is no other Premium flag.
 *
 * A Premium status that was checked stays valid offline for {@link #OFFLINE_DAYS} days, and
 * not past the due date of the service. After that the features wait for a new check, but
 * nothing of the user is deleted.
 */
public class TerminowEntitlement {

    public static final int OFFLINE_DAYS = 7;

    public enum State {
        /** Not signed in to a TermiNow account. */
        SIGNED_OUT,
        /** Signed in, Premium was never checked yet, or the email is not verified. */
        UNKNOWN,
        ACTIVE,
        /** Premium was active, but the last check is too old to trust offline. */
        NEEDS_CHECK,
        /** The service was due and no check since says it was renewed. */
        EXPIRED,
        /** Signed in, and the last check says there is no Premium. */
        FREE
    }

    @NonNull
    public static State get(@NonNull TerminowStore store) {
        TerminowAccount.Session session = store.getAccount();
        long now = System.currentTimeMillis();
        State accmove = decide(session != null, session != null && session.emailVerified, store.getPremium(), now);
        if (session == null) return accmove;
        return combine(accmove, decidePlay(store.getPlayEntitlement(), now));
    }

    /**
     * The Google Play entitlement that Firebase gave: Premium until its verified expiry, and
     * offline for {@link #OFFLINE_DAYS} days after it was last read.
     *
     * @return {@code null} if there is none.
     */
    @Nullable
    static State decidePlay(@Nullable JSONObject entitlement, long now) {
        JSONObject play = entitlement != null ? entitlement.optJSONObject("google_play") : null;
        if (play == null) return null;
        long expires = play.optLong("expiresAt");
        long checked = entitlement.optLong("checked");
        if (!play.optBoolean("premium")) return "NONE".equals(play.optString("subscriptionState")) ? null : State.EXPIRED;
        if (expires <= now) return State.EXPIRED;
        if (checked > now + 60_000 || now - checked > OFFLINE_DAYS * 24L * 60 * 60 * 1000) return State.NEEDS_CHECK;
        return State.ACTIVE;
    }

    /** Premium if either source says so. Otherwise what needs the user, a check before an expiry. */
    @NonNull
    static State combine(@NonNull State accmove, @Nullable State play) {
        if (play == null || accmove == State.ACTIVE) return accmove;
        if (play == State.ACTIVE) return State.ACTIVE;
        if (play == State.NEEDS_CHECK || accmove == State.NEEDS_CHECK) return State.NEEDS_CHECK;
        if (accmove == State.SIGNED_OUT) return accmove;
        return play == State.EXPIRED && (accmove == State.FREE || accmove == State.UNKNOWN) ? State.EXPIRED : accmove;
    }

    @NonNull
    static State decide(boolean signedIn, boolean emailVerified, @Nullable JSONObject premium, long now) {
        if (!signedIn) return State.SIGNED_OUT;
        if (!emailVerified || premium == null) return State.UNKNOWN;
        if (!premium.optBoolean("premium")) return State.FREE;
        String due = premium.isNull("nextDueDate") ? "" : premium.optString("nextDueDate");
        if (!due.isEmpty() && due.compareTo(today(now)) < 0) return State.EXPIRED;
        long checked = premium.optLong("checked");
        if (checked > now + 60_000 || now - checked > OFFLINE_DAYS * 24L * 60 * 60 * 1000) return State.NEEDS_CHECK;
        return State.ACTIVE;
    }

    @NonNull
    private static String today(long now) {
        // WHMCS due dates are dates without a time
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(now));
    }

}
