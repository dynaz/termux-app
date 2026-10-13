package com.termux.app.terminow;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.AccountIdentifiers;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Google Play Billing for TermiNow Premium, in the Play build only. */
final class TerminowPlayBilling extends TerminowBilling {

    static final boolean SUPPORTED = true;

    private final BillingClient mClient;
    private final List<Object[]> mWaiting = new ArrayList<>();
    private boolean mConnecting;

    @NonNull
    static TerminowBilling open(@NonNull Activity activity, @NonNull TerminowBilling.Listener listener) {
        return new TerminowPlayBilling(activity, listener);
    }

    private TerminowPlayBilling(@NonNull Activity activity, @NonNull TerminowBilling.Listener listener) {
        mClient = BillingClient.newBuilder(activity.getApplicationContext())
            .setListener((result, purchases) -> {
                int code = result.getResponseCode();
                if (code == BillingClient.BillingResponseCode.OK) listener.onPurchases(owned(purchases));
                else if (code == BillingClient.BillingResponseCode.USER_CANCELED) listener.onCancelled();
                else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) queryOwned((list, error) -> {
                    if (list != null) listener.onPurchases(list);
                    else listener.onError(String.valueOf(error));
                });
                else listener.onError(describe(result));
            })
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build();
    }

    /** Run when connected to Google Play, or report the error. Callbacks come on the main thread. */
    private void connected(@NonNull Runnable action, @NonNull Result<?> onError) {
        if (mClient.isReady()) {
            action.run();
            return;
        }
        mWaiting.add(new Object[]{action, onError});
        if (mConnecting) return;
        mConnecting = true;
        mClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                mConnecting = false;
                List<Object[]> waiting = new ArrayList<>(mWaiting);
                mWaiting.clear();
                boolean ok = result.getResponseCode() == BillingClient.BillingResponseCode.OK;
                for (Object[] pair : waiting) {
                    if (ok) ((Runnable) pair[0]).run();
                    else post((Result<?>) pair[1], null, describe(result));
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                mConnecting = false;
            }
        });
    }

    @Override
    public void queryPlans(@NonNull Result<List<Plan>> result) {
        connected(() -> {
            QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()))
                .build();
            mClient.queryProductDetailsAsync(params, (billingResult, details) -> {
                if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    post(result, null, describe(billingResult));
                    return;
                }
                List<Plan> plans = new ArrayList<>();
                for (ProductDetails product : details.getProductDetailsList()) {
                    List<ProductDetails.SubscriptionOfferDetails> offers = product.getSubscriptionOfferDetails();
                    if (offers == null) continue;
                    for (ProductDetails.SubscriptionOfferDetails offer : offers) {
                        // The base plans themselves, without promotional offers
                        if (offer.getOfferId() != null) continue;
                        List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
                        if (phases.isEmpty()) continue;
                        ProductDetails.PricingPhase phase = phases.get(phases.size() - 1);
                        plans.add(new Plan(offer.getBasePlanId(), phase.getFormattedPrice(), phase.getBillingPeriod(), product, offer.getOfferToken()));
                    }
                }
                Collections.sort(plans, (a, b) -> Integer.compare(months(a.period), months(b.period)));
                post(result, plans, null);
            });
        }, result);
    }

    @Override
    public void buy(@NonNull Activity activity, @NonNull Plan plan, @NonNull String accountId, @NonNull Result<Void> started) {
        connected(() -> {
            BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails((ProductDetails) plan.details)
                    .setOfferToken(plan.offerToken)
                    .build()))
                .setObfuscatedAccountId(accountId)
                .build();
            BillingResult result = mClient.launchBillingFlow(activity, params);
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) started.onResult(null, null);
            else started.onResult(null, describe(result));
        }, started);
    }

    @Override
    public void queryOwned(@NonNull Result<List<Owned>> result) {
        connected(() -> mClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
            (billingResult, purchases) -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) post(result, owned(purchases), null);
                else post(result, null, describe(billingResult));
            }), result);
    }

    @Override
    public void end() {
        mClient.endConnection();
    }

    @NonNull
    private static List<Owned> owned(@Nullable List<Purchase> purchases) {
        List<Owned> list = new ArrayList<>();
        if (purchases == null) return list;
        for (Purchase purchase : purchases) {
            if (!purchase.getProducts().contains(PRODUCT_ID)) continue;
            int state = purchase.getPurchaseState();
            if (state != Purchase.PurchaseState.PURCHASED && state != Purchase.PurchaseState.PENDING) continue;
            AccountIdentifiers ids = purchase.getAccountIdentifiers();
            String accountId = ids == null || ids.getObfuscatedAccountId() == null ? "" : ids.getObfuscatedAccountId();
            list.add(new Owned(purchase.getPurchaseToken(), accountId, purchase.isAcknowledged(), state == Purchase.PurchaseState.PENDING));
        }
        return list;
    }

    /** Some callbacks of the library come on a background thread. */
    private static <T> void post(@NonNull Result<T> result, @Nullable T value, @Nullable String error) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> result.onResult(value, error));
    }

    private static int months(@NonNull String period) {
        if (period.endsWith("Y")) return 12 * number(period);
        if (period.endsWith("M")) return number(period);
        return 0;
    }

    private static int number(@NonNull String period) {
        try {
            return Integer.parseInt(period.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @NonNull
    private static String describe(@NonNull BillingResult result) {
        switch (result.getResponseCode()) {
            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                return "Google Play billing is not available on this device or account";
            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
            case BillingClient.BillingResponseCode.NETWORK_ERROR:
                return "Google Play could not be reached, try again";
            case BillingClient.BillingResponseCode.ITEM_UNAVAILABLE:
                return "Premium is not available in Google Play yet";
            default:
                String message = result.getDebugMessage();
                return "Google Play: " + (message == null || message.isEmpty() ? "error " + result.getResponseCode() : message);
        }
    }

}
