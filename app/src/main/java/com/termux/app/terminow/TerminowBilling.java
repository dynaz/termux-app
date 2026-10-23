package com.termux.app.terminow;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Buying TermiNow Premium with Google Play. Only the Play build has it, see
 * {@link TerminowPlayBilling}; the full build for direct installs returns {@code null} from
 * {@link #create}.
 *
 * The app never decides Premium from a purchase itself: it sends the purchase token to the
 * server, which checks it with Google, keeps it for the account and acknowledges it. Premium is
 * then what the server answers, like for accmove.com.
 */
public abstract class TerminowBilling {

    /** The subscription in Play Console, with the base plans "monthly" and "yearly". */
    public static final String PRODUCT_ID = "terminow_premium";

    /** A base plan the user can buy, with its price from Google Play. */
    public static class Plan {
        public final String basePlanId;
        /** Like "US$4.99", in the currency of the user. */
        public final String price;
        /** ISO 8601, like P1M or P1Y. */
        public final String period;
        /** For the library: which product and offer to buy. */
        final Object details;
        final String offerToken;

        public Plan(@NonNull String basePlanId, @NonNull String price, @NonNull String period, @NonNull Object details, @NonNull String offerToken) {
            this.basePlanId = basePlanId;
            this.price = price;
            this.period = period;
            this.details = details;
            this.offerToken = offerToken;
        }
    }

    /** A Premium purchase that Google Play has on this device. */
    public static class Owned {
        public final String token;
        /** The obfuscated account id it was bought for, empty if none. */
        public final String accountId;
        public final boolean acknowledged;
        public final boolean pending;

        public Owned(@NonNull String token, @NonNull String accountId, boolean acknowledged, boolean pending) {
            this.token = token;
            this.accountId = accountId;
            this.acknowledged = acknowledged;
            this.pending = pending;
        }
    }

    public interface Listener {
        /** Purchases finished or changed, also those that are still pending payment. */
        void onPurchases(@NonNull List<Owned> purchases);
        /** The user closed the purchase screen. */
        void onCancelled();
        void onError(@NonNull String message);
    }

    public interface Result<T> {
        void onResult(@Nullable T value, @Nullable String error);
    }

    /** Google Play billing for this activity, or {@code null} in a build without it. */
    @Nullable
    public static TerminowBilling create(@NonNull Activity activity, @NonNull Listener listener) {
        return TerminowPlayBilling.open(activity, listener);
    }

    /** Whether this build sells Premium with Google Play. */
    public static boolean isSupported() {
        return TerminowPlayBilling.SUPPORTED;
    }

    /** The plans of Premium, cheapest period first. */
    public abstract void queryPlans(@NonNull Result<List<Plan>> result);

    /** Show the purchase screen of Google Play; the outcome goes to the listener. */
    public abstract void buy(@NonNull Activity activity, @NonNull Plan plan, @NonNull String accountId, @NonNull Result<Void> started);

    /** The Premium purchases Google Play has for the user of this device. */
    public abstract void queryOwned(@NonNull Result<List<Owned>> result);

    public abstract void end();

    /** The subscriptions page of Google Play for Premium, where it is changed or cancelled. */
    @NonNull
    public static String manageUrl(@NonNull String packageName) {
        return "https://play.google.com/store/account/subscriptions?sku=" + PRODUCT_ID + "&package=" + packageName;
    }

    /**
     * The obfuscated account id of a purchase for this TermiNow account: a hash of the uid, so
     * that Google never gets the uid, and the server can tell whose purchase it is.
     * play_lib.php account_id() computes the same.
     */
    @NonNull
    public static String accountId(@NonNull String uid) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(("terminow:" + uid).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
