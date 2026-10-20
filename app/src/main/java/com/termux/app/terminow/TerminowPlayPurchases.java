package com.termux.app.terminow;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The Google Play purchases of the signed-in account: every purchase that Google Play has on
 * this device is given to the verification function of Firebase, after a purchase, on a
 * restore and when the app comes back, and Premium is what Firebase answers.
 *
 * Nothing here grants Premium by itself: not the purchase screen closing, not a product id
 * of the app, not a stored flag. The status that is kept only says what to show the user.
 */
public class TerminowPlayPurchases {

    /** What the last purchase or check of the account came to, for the user. */
    public enum Status {
        /** Google Play waits for the payment, like a cash payment at a store. */
        PENDING,
        /** The purchase was made and the server is checking it. */
        VERIFYING,
        ACTIVE,
        /** The server or Google Play could not be reached, TermiNow tries again. */
        UNAVAILABLE,
        /** The server refused the purchase, like one of another account. */
        REJECTED,
        EXPIRED,
        /** On hold or paused in Google Play: the payment method needs attention. */
        ON_HOLD
    }

    /** How often the purchases are checked when the app comes back. */
    static final long RECOVER_INTERVAL_MS = 10 * 60 * 1000;
    /** A purchase that was verified is verified again after this long, besides notifications. */
    static final long VERIFY_AGAIN_MS = 24 * 60 * 60 * 1000;

    private static long sLastRecover;

    private final Activity mActivity;
    private final TerminowStore mStore;
    private final TerminowAccount mAccount;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public TerminowPlayPurchases(@NonNull Activity activity, @NonNull TerminowStore store, @NonNull TerminowAccount account) {
        mActivity = activity;
        mStore = store;
        mAccount = account;
    }

    @Nullable
    public static Status getStatus(@NonNull TerminowStore store) {
        JSONObject status = store.getPlayPurchaseStatus();
        if (status == null) return null;
        try {
            return Status.valueOf(status.optString("status"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Whether buying again must not be offered: a purchase waits for its payment or its check. */
    public static boolean isPurchaseInProgress(@NonNull TerminowStore store) {
        Status status = getStatus(store);
        return status == Status.PENDING || status == Status.VERIFYING || status == Status.UNAVAILABLE;
    }

    /**
     * Check the purchases that Google Play has on this device for the account.
     *
     * @param restore Verify every purchase of the account, like after a reinstall, not only the due ones.
     * @param onDone Called on the main thread, with an error to show or {@code null}.
     */
    public void recover(boolean restore, @NonNull Callback onDone) {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null || !session.emailVerified) {
            onDone.done(null);
            return;
        }
        long now = System.currentTimeMillis();
        if (!restore && now - sLastRecover < RECOVER_INTERVAL_MS) {
            onDone.done(null);
            return;
        }
        sLastRecover = now;
        TerminowBilling billing = TerminowBilling.create(mActivity, new IgnoringListener());
        if (billing == null) {
            refreshEntitlementAsync(session, onDone);
            return;
        }
        billing.queryOwned((owned, error) -> {
            billing.end();
            if (owned == null) {
                // Google Play is not reachable: the entitlement of the server still counts
                refreshEntitlementAsync(session, e -> onDone.done(restore ? error : e));
                return;
            }
            verifyAsync(session, owned, restore, onDone);
        });
    }

    /** Verify purchases of the account in the background; a purchase of another account is reported. */
    public void verifyAsync(@NonNull TerminowAccount.Session session, @NonNull List<TerminowBilling.Owned> owned, boolean all,
                            @NonNull Callback onDone) {
        String accountId = TerminowBilling.accountId(session.uid);
        List<TerminowBilling.Owned> mine = new ArrayList<>();
        boolean otherAccount = false;
        boolean pending = false;
        for (TerminowBilling.Owned purchase : owned) {
            if (!accountId.equals(purchase.accountId)) {
                otherAccount = true;
            } else if (purchase.pending) {
                pending = true;
            } else if (all || !purchase.acknowledged ||
                System.currentTimeMillis() - mStore.getPlayVerifiedTime(hash(purchase.token)) > VERIFY_AGAIN_MS) {
                mine.add(purchase);
            }
        }
        if (pending && mine.isEmpty()) mStore.setPlayPurchaseStatus(Status.PENDING.name(), null, false);
        if (mine.isEmpty()) {
            String finalError = otherAccount && owned.size() == 1 ? mActivity.getString(com.termux.R.string.terminow_premium_not_yours) : null;
            refreshEntitlementAsync(session, e -> onDone.done(finalError != null ? finalError : e));
            return;
        }
        mStore.setPlayPurchaseStatus(Status.VERIFYING.name(), null, false);
        new Thread(() -> {
            String error = null;
            for (TerminowBilling.Owned purchase : mine) {
                try {
                    JSONObject result = mAccount.verifyPlayPurchase(session, purchase.token);
                    mStore.setPlayVerifiedTime(hash(purchase.token), System.currentTimeMillis());
                    applyResult(mStore, result);
                } catch (TerminowAccount.FunctionException e) {
                    mStore.setPlayPurchaseStatus((e.transientFailure ? Status.UNAVAILABLE : Status.REJECTED).name(), e.code, false);
                    error = e.getMessage() + " (" + e.code + ")";
                } catch (TerminowAccount.AccountException e) {
                    mStore.setPlayPurchaseStatus(Status.UNAVAILABLE.name(), e.code, false);
                    error = e.getMessage();
                }
            }
            String finalError = error;
            mHandler.post(() -> {
                // The session may have a new refresh token
                if (mStore.getAccount() != null) mStore.setAccount(session);
                onDone.done(finalError);
            });
        }).start();
    }

    /** Keep what the verification function answered: the status of the purchase and the entitlement. */
    static void applyResult(@NonNull TerminowStore store, @NonNull JSONObject result) {
        String status = result.optString("status");
        Status shown;
        switch (status) {
            case "ACTIVE": shown = Status.ACTIVE; break;
            case "PENDING": shown = Status.PENDING; break;
            case "ON_HOLD":
            case "PAUSED": shown = Status.ON_HOLD; break;
            default: shown = Status.EXPIRED;
        }
        // A plan change replaced this purchase: the account has a newer one, which decides
        if ("REPLACED".equals(status)) shown = getStatus(store) != null ? getStatus(store) : Status.EXPIRED;
        store.setPlayPurchaseStatus(shown.name(), null, result.optBoolean("test"));
        JSONObject entitlement = result.optJSONObject("entitlement");
        if (entitlement != null) store.setPlayEntitlement(entitlement);
    }

    /**
     * The entitlement that the backend signs, with a Play Integrity token for it to observe, on a
     * background thread. {@code null} when the backend signs none yet or can not be reached: the
     * entitlement that was read then decides as before.
     */
    @Nullable
    private JSONObject fetchSigned(@NonNull TerminowAccount.Session session) {
        String nonce = TerminowIntegrity.newNonce();
        String[] integrity = new String[2];
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        mHandler.post(() -> TerminowPlayIntegrity.request(mActivity, com.termux.BuildConfig.TERMINOW_INTEGRITY_PROJECT_NUMBER,
            TerminowIntegrity.requestHash(session.uid, nonce), (token, error) -> {
                integrity[0] = token;
                integrity[1] = error;
                done.countDown();
            }));
        try {
            if (!done.await(15, java.util.concurrent.TimeUnit.SECONDS)) integrity[1] = "TIMEOUT";
            JSONObject result = mAccount.signedEntitlement(session, nonce, integrity[0], integrity[1],
                TerminowPlayIntegrity.SUPPORTED ? "play" : "full");
            return result.optJSONObject("signed");
        } catch (TerminowAccount.AccountException e) {
            // Observed only: the old backend has no such function, and that changes nothing
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Read the entitlement that the server keeps, after a renewal or an expiry that it found itself. */
    private void refreshEntitlementAsync(@NonNull TerminowAccount.Session session, @NonNull Callback onDone) {
        if (!mAccount.hasFirebaseBackend()) {
            onDone.done(null);
            return;
        }
        new Thread(() -> {
            String error = null;
            try {
                JSONObject entitlement = mAccount.getPlayEntitlement(session);
                JSONObject play = entitlement != null && entitlement.optJSONObject("sources") != null ?
                    entitlement.optJSONObject("sources").optJSONObject("google_play") : null;
                JSONObject kept = new JSONObject();
                kept.put("premium", entitlement != null && entitlement.optBoolean("premium"));
                if (play != null) kept.put("google_play", play);
                JSONObject signed = fetchSigned(session);
                if (signed != null) kept.put("signed", signed);
                mStore.setPlayEntitlement(kept);
                if (play != null && !play.optBoolean("premium") && getStatus(mStore) == Status.ACTIVE)
                    mStore.setPlayPurchaseStatus(Status.EXPIRED.name(), null, play.optBoolean("test"));
            } catch (TerminowAccount.AccountException | org.json.JSONException e) {
                error = e.getMessage();
            }
            String finalError = error;
            mHandler.post(() -> {
                if (mStore.getAccount() != null) mStore.setAccount(session);
                onDone.done(finalError);
            });
        }).start();
    }

    public interface Callback {
        void done(@Nullable String error);
    }

    @NonNull
    static String hash(@NonNull String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** For checking purchases only, outside a purchase. */
    static class IgnoringListener implements TerminowBilling.Listener {
        @Override public void onPurchases(@NonNull List<TerminowBilling.Owned> purchases) {}
        @Override public void onCancelled() {}
        @Override public void onError(@NonNull String message) {}
    }

}
