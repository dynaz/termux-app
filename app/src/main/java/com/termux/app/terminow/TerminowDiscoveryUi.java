package com.termux.app.terminow;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.TerminowStore.Host;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Network Discovery screen: scan the local network for reachable devices and act on them with the
 * existing connection and Save-as-Host flows. The scan runs off the UI thread with {@link
 * TerminowNetworkScanner}; results stream in; unknown details stay unknown. No scan starts on its own —
 * only when the user taps Start — and it is limited to the entered range.
 */
public class TerminowDiscoveryUi {

    /** Hooks back into the app's existing connection and host flows, so nothing is duplicated. */
    public interface Actions {
        void connectSsh(@NonNull Host host);
        void saveAsHost(@NonNull Host prefill);
        void openWeb(@NonNull String url);
        void copyText(@NonNull String label, @NonNull String text);
    }

    private static final int BG = 0xFF14161C, CARD = 0xFF1A1D25, ROW = 0xFF222631;
    private static final int TEXT = 0xFFF5F7FA, SECONDARY = 0xFFA8B0BF, ACCENT = 0xFF21C17A, WARN = 0xFFF2C14E;
    private static final int MAX_ADDRESSES = 1024;

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final Actions mActions;

    @Nullable private AtomicBoolean mCancel;
    @Nullable private TextView mStatus;
    @Nullable private TextView mButton;
    @Nullable private LinearLayout mResults;
    private boolean mScanning;

    public TerminowDiscoveryUi(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull Actions actions) {
        mActivity = activity;
        mStore = store;
        mActions = actions;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, mActivity.getResources().getDisplayMetrics()));
    }

    public void show() {
        Dialog dialog = new Dialog(mActivity, android.R.style.Theme_DeviceDefault_NoActionBar);
        if (dialog.getWindow() != null) dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = text(root, mActivity.getString(R.string.terminow_discovery_title), 20, TEXT, true);
        title.setPadding(0, 0, 0, dp(4));
        text(root, mActivity.getString(R.string.terminow_discovery_source_this_device), 13, SECONDARY, false);

        // Editable CIDR, prefilled from the first detected interface (its real prefix, not /24).
        final EditText cidr = new EditText(mActivity);
        cidr.setTextColor(TEXT);
        cidr.setHintTextColor(SECONDARY);
        cidr.setTextSize(15);
        cidr.setSingleLine(true);
        cidr.setBackground(rounded(ROW, dp(10)));
        cidr.setPadding(dp(14), dp(12), dp(14), dp(12));
        cidr.setHint("192.168.1.0/24");
        List<TerminowNetworkInterfaces.Iface> ifaces = TerminowNetworkInterfaces.list();
        for (TerminowNetworkInterfaces.Iface i : ifaces) {
            String c = i.cidr();
            if (c != null) { cidr.setText(c); break; }
        }
        LinearLayout.LayoutParams cidrLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cidrLp.topMargin = dp(10);
        root.addView(cidr, cidrLp);

        mButton = pill(mActivity.getString(R.string.terminow_discovery_start), ACCENT, 0xFF0B1F17);
        mButton.setOnClickListener(v -> { if (mScanning) stopScan(); else startScan(cidr.getText().toString()); });
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(12);
        root.addView(mButton, bLp);

        mStatus = text(root, mActivity.getString(R.string.terminow_discovery_initial), 13, SECONDARY, false);
        mStatus.setPadding(0, dp(12), 0, dp(8));

        ScrollView scroll = new ScrollView(mActivity);
        mResults = new LinearLayout(mActivity);
        mResults.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mResults);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView close = pill(mActivity.getString(R.string.terminow_close_dialog), ROW, TEXT);
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(close);

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> stopScan());
        dialog.show();
    }

    private void startScan(@NonNull String cidrText) {
        TerminowCidr.Cidr cidr = TerminowCidr.parse(cidrText.trim());
        if (cidr == null) { setStatus(mActivity.getString(R.string.terminow_discovery_bad_cidr), WARN); return; }
        if (!cidr.withinLimit(MAX_ADDRESSES)) {
            setStatus(mActivity.getString(R.string.terminow_discovery_too_large, cidr.addressCount(), MAX_ADDRESSES), WARN);
            return;
        }
        final List<String> addresses = cidr.addresses(MAX_ADDRESSES);
        final String scope = "local:" + cidr.baseAddress() + "/" + cidr.prefix;
        if (mResults != null) mResults.removeAllViews();
        mScanning = true;
        mCancel = new AtomicBoolean(false);
        if (mButton != null) mButton.setText(mActivity.getString(R.string.terminow_discovery_stop));
        final AtomicBoolean cancel = mCancel;
        final long started = System.currentTimeMillis();

        TerminowNetworkScanner.Config config = new TerminowNetworkScanner.Config(
            scope, addresses, TerminowNetworkScanner.defaultPorts(), 64, 600, 120_000);
        new Thread(() -> TerminowNetworkScanner.scan(config, TerminowNetworkScanner.socketProber(), cancel,
            new TerminowNetworkScanner.Listener() {
                @Override public void onDevice(@NonNull TerminowDiscoveredDevice device) {
                    mActivity.runOnUiThread(() -> { if (cancel == mCancel) showDevice(device); });
                }
                @Override public void onProgress(int probed, int total, int found) {
                    mActivity.runOnUiThread(() -> {
                        if (cancel != mCancel) return;
                        long secs = (System.currentTimeMillis() - started) / 1000;
                        setStatus(mActivity.getString(R.string.terminow_discovery_scanning, probed, total, found, secs), SECONDARY);
                    });
                }
                @Override public void onFinished(@NonNull TerminowNetworkScanner.Status status, @NonNull List<TerminowDiscoveredDevice> devices) {
                    mActivity.runOnUiThread(() -> {
                        if (cancel != mCancel) return;
                        mScanning = false;
                        if (mButton != null) mButton.setText(mActivity.getString(R.string.terminow_discovery_start));
                        int n = devices.size();
                        String msg = status == TerminowNetworkScanner.Status.CANCELLED
                            ? mActivity.getString(R.string.terminow_discovery_stopped, n)
                            : n == 0 ? mActivity.getString(R.string.terminow_discovery_none)
                            : mActivity.getString(R.string.terminow_discovery_done, n);
                        setStatus(msg, n == 0 ? SECONDARY : ACCENT);
                    });
                }
            }), "TermiNow discovery").start();
    }

    private void stopScan() {
        if (mCancel != null) mCancel.set(true);
        mScanning = false;
        if (mButton != null) mButton.setText(mActivity.getString(R.string.terminow_discovery_start));
    }

    /** Add or refresh a device row (icon + name/IP + service chips + chevron). On the UI thread. */
    private void showDevice(@NonNull TerminowDiscoveredDevice device) {
        if (mResults == null) return;
        // One row per IP: replace if it already exists (ports may have grown).
        View existing = mResults.findViewWithTag(device.ip);
        if (existing != null) mResults.removeView(existing);

        LinearLayout row = new LinearLayout(mActivity);
        row.setTag(device.ip);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(CARD, dp(12)));
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        row.setLayoutParams(rowLp);

        // Generic device icon with a green "reachable" dot (the device type is not claimed from ports).
        FrameLayout iconFrame = new FrameLayout(mActivity);
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_terminow_hosts);
        icon.setColorFilter(SECONDARY);
        iconFrame.addView(icon, new FrameLayout.LayoutParams(dp(26), dp(26)));
        View dot = new View(mActivity);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(ACCENT);
        dotBg.setStroke(dp(2), CARD);
        dot.setBackground(dotBg);
        iconFrame.addView(dot, new FrameLayout.LayoutParams(dp(11), dp(11), Gravity.BOTTOM | Gravity.END));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        iconLp.rightMargin = dp(12);
        row.addView(iconFrame, iconLp);

        // Name + IP
        LinearLayout col = new LinearLayout(mActivity);
        col.setOrientation(LinearLayout.VERTICAL);
        String name = !device.hostname.isEmpty() ? device.hostname : mActivity.getString(R.string.terminow_discovery_unknown_device);
        TextView nameView = textPlain(name, 15, TEXT, true);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nameView);
        col.addView(textPlain(device.ip, 12, SECONDARY, false));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Service chips (and a Saved chip), then a chevron
        LinearLayout chips = new LinearLayout(mActivity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        if (TerminowDiscoveryHosts.findPossibleDuplicate(mStore.getHosts(), device.ip) != null)
            chips.addView(chip(mActivity.getString(R.string.terminow_discovery_saved), 0xFF234634, ACCENT));
        for (int p : device.openPorts) chips.addView(chip(portLabel(p), chipBg(p), chipFg(p)));
        row.addView(chips);
        TextView chevron = textPlain("›", 22, SECONDARY, false);
        chevron.setPadding(dp(6), 0, 0, 0);
        row.addView(chevron);

        row.setOnClickListener(v -> deviceActions(device));
        mResults.addView(row);
    }

    @NonNull
    private static String portLabel(int p) {
        switch (p) {
            case 22: return "SSH";
            case 80: return "HTTP";
            case 443: return "HTTPS";
            case 3389: return "RDP";
            default: return String.valueOf(p);
        }
    }

    private static int chipBg(int p) {
        if (p == 22) return 0xFF1E3A2E;
        if (p == 80 || p == 443) return 0xFF1C2A3E;
        if (p == 3389) return 0xFF3A2E1C;
        return ROW;
    }

    private static int chipFg(int p) {
        if (p == 22) return ACCENT;
        if (p == 80 || p == 443) return 0xFF5AA0F2;
        if (p == 3389) return 0xFFF2A14E;
        return SECONDARY;
    }

    private TextView chip(@NonNull String label, int bg, int fg) {
        TextView c = new TextView(mActivity);
        c.setText(label);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        c.setTextColor(fg);
        c.setTypeface(Typeface.DEFAULT_BOLD);
        c.setPadding(dp(7), dp(3), dp(7), dp(3));
        c.setBackground(rounded(bg, dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView textPlain(@NonNull String s, float size, int color, boolean bold) {
        TextView t = new TextView(mActivity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void deviceActions(@NonNull TerminowDiscoveredDevice device) {
        boolean web = device.openPorts.contains(80) || device.openPorts.contains(443);
        List<String> labels = new java.util.ArrayList<>();
        List<Runnable> runs = new java.util.ArrayList<>();
        if (device.openPorts.contains(22)) {
            labels.add(mActivity.getString(R.string.terminow_discovery_connect_ssh));
            runs.add(() -> mActions.connectSsh(TerminowDiscoveryHosts.prefill(device)));
        }
        if (web) {
            boolean https = device.openPorts.contains(443);
            String url = (https ? "https://" : "http://") + device.ip;
            labels.add(mActivity.getString(R.string.terminow_discovery_open_web));
            runs.add(() -> mActions.openWeb(url));
        }
        labels.add(mActivity.getString(R.string.terminow_discovery_save_host));
        runs.add(() -> mActions.saveAsHost(TerminowDiscoveryHosts.prefill(device)));
        labels.add(mActivity.getString(R.string.terminow_discovery_copy_ip));
        runs.add(() -> mActions.copyText("IP", device.ip));

        final Runnable[] actions = runs.toArray(new Runnable[0]);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(device.ip)
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions[which].run())
            .show();
    }

    private void setStatus(@NonNull String text, int color) {
        if (mStatus != null) { mStatus.setText(text); mStatus.setTextColor(color); }
    }

    private TextView text(@NonNull LinearLayout parent, @NonNull String s, float size, int color, boolean bold) {
        TextView t = new TextView(mActivity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(t);
        return t;
    }

    private TextView pill(@NonNull String label, int bg, int fg) {
        TextView b = new TextView(mActivity);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setBackground(rounded(bg, dp(12)));
        b.setClickable(true);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
}
