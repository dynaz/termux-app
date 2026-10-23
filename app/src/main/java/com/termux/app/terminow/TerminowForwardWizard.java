package com.termux.app.terminow;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.view.ViewUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The panel to set up a port forwarding, shown at the side of the screen like the host form.
 * A new forwarding is set up in the steps of a wizard, which can be skipped for a form with all
 * the fields, which is also shown to edit a forwarding.
 */
public class TerminowForwardWizard {

    public interface OnSaveListener {
        void onSave(@NonNull TerminowStore.Forward forward);
    }

    private static final int STEP_INTRO = 0;
    private static final int STEP_PORT = 1;
    private static final int STEP_HOST = 2;
    private static final int STEP_DESTINATION = 3;
    private static final int STEP_LABEL = 4;
    private static final int STEP_FORM = 5;

    private static final String[] TYPES = {TerminowStore.Forward.TYPE_LOCAL, TerminowStore.Forward.TYPE_REMOTE, TerminowStore.Forward.TYPE_DYNAMIC};

    /** The part of the screen width that the panel takes on wide screens. */
    private static final float PANEL_SCREEN_WIDTH_FRACTION = 0.48f;
    private static final int PANEL_MIN_WIDTH_DP = 340;
    private static final int PANEL_MIN_SCREEN_WIDTH_DP = 600;

    /** The ports below this can only be opened by root. */
    private static final int UNPRIVILEGED_PORT_MIN = 1024;

    private final TermuxActivity mActivity;
    private final List<TerminowStore.Host> mHosts;
    private final OnSaveListener mOnSaveListener;
    private final Dialog mDialog;
    private final ImageView mBackView;
    private final TextView mTitleView;
    private final LinearLayout mContentView;

    /** The forwarding that is being set up. */
    private final TerminowStore.Forward mForward = new TerminowStore.Forward();
    private int mStep;

    public TerminowForwardWizard(@NonNull TermuxActivity activity, @NonNull List<TerminowStore.Host> hosts,
                                 @Nullable TerminowStore.Forward existingForward, @NonNull OnSaveListener onSaveListener) {
        mActivity = activity;
        mHosts = hosts;
        mOnSaveListener = onSaveListener;

        if (existingForward != null) {
            mForward.label = existingForward.label;
            mForward.type = existingForward.type;
            mForward.host = existingForward.host;
            mForward.port = existingForward.port;
            mForward.bindAddress = existingForward.bindAddress;
            mForward.destinationAddress = existingForward.destinationAddress;
            mForward.destinationPort = existingForward.destinationPort;
        } else {
            mForward.destinationAddress = "localhost";
        }
        mStep = existingForward != null ? STEP_FORM : STEP_INTRO;

        mDialog = new Dialog(activity, R.style.TerminowPanelDialog);

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(ContextCompat.getColor(activity, R.color.terminow_panel_scrim));
        root.setFitsSystemWindows(true);
        // Tapping the dimmed home screen next to the panel closes it
        root.setOnClickListener(v -> mDialog.dismiss());

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.terminow_wizard_panel);
        panel.setClickable(true);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        panelParams.setMargins(dp(10), dp(10), dp(10), dp(10));
        root.addView(panel, panelParams);
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int rootWidth = right - left;
            int width = rootWidth < dp(PANEL_MIN_SCREEN_WIDTH_DP) ? ViewGroup.LayoutParams.MATCH_PARENT :
                Math.max(dp(PANEL_MIN_WIDTH_DP), Math.round(rootWidth * PANEL_SCREEN_WIDTH_FRACTION));
            if (panelParams.width != width) {
                panelParams.width = width;
                panel.post(() -> panel.setLayoutParams(panelParams));
            }
        });

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(10), dp(20), 0);
        mBackView = new ImageView(activity);
        mBackView.setPadding(dp(10), dp(10), dp(10), dp(10));
        mBackView.setOnClickListener(v -> onBack());
        header.addView(mBackView, new LinearLayout.LayoutParams(dp(44), dp(44)));
        mTitleView = new TextView(activity);
        mTitleView.setTextColor(color(R.color.terminow_text));
        mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        mTitleView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMarginStart(dp(8));
        header.addView(mTitleView, titleParams);
        panel.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setVerticalScrollBarEnabled(false);
        mContentView = new LinearLayout(activity);
        mContentView.setOrientation(LinearLayout.VERTICAL);
        mContentView.setPadding(dp(20), dp(6), dp(20), dp(24));
        scrollView.addView(mContentView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        mDialog.setContentView(root);
        mDialog.setOnKeyListener((dialog, keyCode, event) -> {
            // Back goes to the previous step instead of closing the panel
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK || event.getAction() != android.view.KeyEvent.ACTION_UP) return false;
            onBack();
            return true;
        });
    }

    public void show() {
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        showStep();
        mDialog.show();
    }



    private boolean isDynamic() {
        return mForward.isDynamic();
    }

    private boolean isRemote() {
        return TerminowStore.Forward.TYPE_REMOTE.equals(mForward.type);
    }

    private void onBack() {
        if (mStep == STEP_INTRO || mStep == STEP_FORM) {
            mDialog.dismiss();
            return;
        }
        // The destination step is not shown for a dynamic forwarding
        mStep = mStep == STEP_LABEL && isDynamic() ? STEP_HOST : mStep - 1;
        showStep();
    }

    private void showStep() {
        mContentView.removeAllViews();
        mFormReader = null;
        boolean first = mStep == STEP_INTRO || mStep == STEP_FORM;
        mBackView.setImageResource(first ? R.drawable.ic_terminow_close : R.drawable.ic_terminow_back);
        mBackView.setColorFilter(color(R.color.terminow_text), PorterDuff.Mode.SRC_IN);
        mTitleView.setText(first ? R.string.terminow_forward_title :
            isDynamic() ? R.string.terminow_forward_title_dynamic :
            isRemote() ? R.string.terminow_forward_title_remote : R.string.terminow_forward_title_local);

        switch (mStep) {
            case STEP_INTRO: showIntro(); break;
            case STEP_PORT: showPort(); break;
            case STEP_HOST: showHost(); break;
            case STEP_DESTINATION: showDestination(); break;
            case STEP_LABEL: showLabel(); break;
            default: showForm(); break;
        }
    }

    private void showIntro() {
        addTabs();
        addDiagram();
        addBody(isDynamic() ? R.string.terminow_forward_intro_dynamic :
            isRemote() ? R.string.terminow_forward_intro_remote : R.string.terminow_forward_intro_local);
        addButton(R.string.terminow_forward_continue, true, () -> {
            mStep = STEP_PORT;
            showStep();
        });
        addButton(R.string.terminow_forward_skip_wizard, false, () -> {
            mStep = STEP_FORM;
            showStep();
        });
    }

    private void showPort() {
        addDiagram();
        addHeading(isRemote() ? R.string.terminow_forward_port_remote_title : R.string.terminow_forward_port_local_title);
        addBody(isDynamic() ? R.string.terminow_forward_port_dynamic_summary :
            isRemote() ? R.string.terminow_forward_port_remote_summary : R.string.terminow_forward_port_local_summary);
        EditText portView = addField(R.string.terminow_forward_port_number, InputType.TYPE_CLASS_NUMBER, mForward.port > 0 ? String.valueOf(mForward.port) : "");
        EditText bindView = addField(R.string.terminow_forward_bind_address, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, mForward.bindAddress);
        TextView continueView = addButton(R.string.terminow_forward_continue, true, () -> {
            if (!readPort(portView, bindView)) return;
            mStep = STEP_HOST;
            showStep();
        });
        enableWhenFilled(continueView, portView);
        portView.requestFocus();
    }

    private void showHost() {
        addHeading(R.string.terminow_forward_host_title);
        addBody(R.string.terminow_forward_host_summary);
        for (TerminowStore.Host host : mHosts) {
            addHostRow(host, () -> {
                mForward.host = host.getTitle();
                mStep = isDynamic() ? STEP_LABEL : STEP_DESTINATION;
                showStep();
            });
        }
    }

    private void showDestination() {
        addHeading(R.string.terminow_forward_destination_title);
        addBody(isRemote() ? R.string.terminow_forward_destination_remote_summary : R.string.terminow_forward_destination_local_summary);
        EditText addressView = addField(R.string.terminow_forward_destination, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, mForward.destinationAddress);
        EditText portView = addField(R.string.terminow_forward_destination_port, InputType.TYPE_CLASS_NUMBER, mForward.destinationPort > 0 ? String.valueOf(mForward.destinationPort) : "");
        TextView continueView = addButton(R.string.terminow_forward_continue, true, () -> {
            if (!readDestination(addressView, portView)) return;
            mStep = STEP_LABEL;
            showStep();
        });
        enableWhenFilled(continueView, addressView, portView);
        portView.requestFocus();
    }

    private void showLabel() {
        addHeading(R.string.terminow_forward_label_title);
        addBody(R.string.terminow_forward_label_summary);
        EditText labelView = addField(R.string.terminow_forward_label, InputType.TYPE_CLASS_TEXT, mForward.label);
        addButton(R.string.terminow_forward_done, true, () -> {
            mForward.label = labelView.getText().toString().trim();
            save();
        });
        labelView.requestFocus();
    }

    /** Show all the fields of the forwarding at once. */
    private void showForm() {
        addTabs();
        EditText labelView = addField(R.string.terminow_forward_label, InputType.TYPE_CLASS_TEXT, mForward.label);
        EditText portView = addField(isRemote() ? R.string.terminow_forward_port_remote : R.string.terminow_forward_port_local,
            InputType.TYPE_CLASS_NUMBER, mForward.port > 0 ? String.valueOf(mForward.port) : "");
        EditText bindView = addField(R.string.terminow_forward_bind_address, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, mForward.bindAddress);

        // Not editable, tapping it shows the hosts to pick from
        EditText hostView = addField(R.string.terminow_forward_host, InputType.TYPE_NULL, mForward.host);
        hostView.setFocusable(false);
        hostView.setCursorVisible(false);
        hostView.setOnClickListener(v -> {
            CharSequence[] titles = new CharSequence[mHosts.size()];
            for (int i = 0; i < titles.length; i++)
                titles[i] = mHosts.get(i).getTitle();
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_forward_host)
                .setItems(titles, (dialog, which) -> hostView.setText(titles[which]))
                .show();
        });

        final EditText addressView = isDynamic() ? null : addField(R.string.terminow_forward_destination, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, mForward.destinationAddress);
        final EditText destinationPortView = isDynamic() ? null : addField(R.string.terminow_forward_destination_port, InputType.TYPE_CLASS_NUMBER,
            mForward.destinationPort > 0 ? String.valueOf(mForward.destinationPort) : "");

        // Keep what was entered when the type is changed with the tabs, which shows the form again
        mFormReader = () -> {
            mForward.label = labelView.getText().toString().trim();
            mForward.port = parsePort(portView);
            mForward.bindAddress = bindView.getText().toString().trim();
            mForward.host = hostView.getText().toString();
            if (addressView != null) {
                mForward.destinationAddress = addressView.getText().toString().trim();
                mForward.destinationPort = parsePort(destinationPortView);
            }
        };

        addButton(R.string.terminow_save, true, () -> {
            mForward.label = labelView.getText().toString().trim();
            mForward.host = hostView.getText().toString();
            if (!readPort(portView, bindView)) return;
            if (addressView != null && !readDestination(addressView, destinationPortView)) return;
            if (mForward.host.isEmpty()) {
                toast(R.string.terminow_forward_host_required);
                return;
            }
            save();
        });
    }

    /** Reads the fields of the form into {@link #mForward} without validating them. */
    @Nullable
    private Runnable mFormReader;

    private void save() {
        if (isDynamic()) {
            mForward.destinationAddress = "";
            mForward.destinationPort = 0;
        }
        if (!TerminowStore.isValidForward(mForward)) {
            toast(R.string.terminow_host_invalid_address);
            return;
        }
        mDialog.dismiss();
        mOnSaveListener.onSave(mForward);
    }



    private static int parsePort(@NonNull EditText view) {
        try {
            return Integer.parseInt(view.getText().toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Read and check the port and bind address. Returns {@code false} and shows why if they are not valid. */
    private boolean readPort(@NonNull EditText portView, @NonNull EditText bindView) {
        int port = parsePort(portView);
        String bindAddress = bindView.getText().toString().trim();
        if (!TerminowStore.isValidPort(port)) {
            toast(R.string.terminow_forward_invalid_port);
            portView.requestFocus();
            return false;
        }
        // The port is opened on this device, except for a remote forwarding
        if (!isRemote() && port < UNPRIVILEGED_PORT_MIN) {
            toast(R.string.terminow_forward_privileged_port);
            portView.requestFocus();
            return false;
        }
        if (!bindAddress.isEmpty() && !TerminowStore.isValidAddress(bindAddress)) {
            toast(R.string.terminow_forward_invalid_bind_address);
            bindView.requestFocus();
            return false;
        }
        mForward.port = port;
        mForward.bindAddress = bindAddress;
        return true;
    }

    private boolean readDestination(@NonNull EditText addressView, @NonNull EditText portView) {
        String address = addressView.getText().toString().trim();
        int port = parsePort(portView);
        if (!TerminowStore.isValidAddress(address)) {
            toast(R.string.terminow_host_invalid_address);
            addressView.requestFocus();
            return false;
        }
        if (!TerminowStore.isValidPort(port)) {
            toast(R.string.terminow_forward_invalid_port);
            portView.requestFocus();
            return false;
        }
        mForward.destinationAddress = address;
        mForward.destinationPort = port;
        return true;
    }

    private void toast(int messageRes) {
        mActivity.showToast(mActivity.getString(messageRes), true);
    }



    private void addTabs() {
        final int[] labels = {R.string.terminow_forward_local, R.string.terminow_forward_remote, R.string.terminow_forward_dynamic};
        LinearLayout tabs = new LinearLayout(mActivity);
        for (int i = 0; i < TYPES.length; i++) {
            final String type = TYPES[i];
            boolean selected = type.equals(mForward.type);

            LinearLayout tab = new LinearLayout(mActivity);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER_HORIZONTAL);

            TextView labelView = new TextView(mActivity);
            labelView.setText(labels[i]);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            labelView.setTypeface(null, Typeface.BOLD);
            labelView.setTextColor(color(selected ? R.color.terminow_accent : R.color.terminow_text_secondary));
            labelView.setGravity(Gravity.CENTER);
            tab.addView(labelView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));

            View indicator = new View(mActivity);
            indicator.setBackgroundColor(selected ? color(R.color.terminow_accent) : 0);
            tab.addView(indicator, new LinearLayout.LayoutParams(dp(40), dp(2)));

            tab.setOnClickListener(v -> {
                if (mFormReader != null && mStep == STEP_FORM) mFormReader.run();
                mForward.type = type;
                showStep();
            });
            tabs.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        mContentView.addView(tabs, params);
    }

    /** Add a diagram of the path of the forwarded traffic: this device, the host and the destination. */
    private void addDiagram() {
        LinearLayout diagram = new LinearLayout(mActivity);
        diagram.setGravity(Gravity.CENTER);
        // A remote forwarding goes from the host to this device
        int[] icons = isRemote() ? new int[]{R.drawable.ic_terminow_hosts, R.drawable.ic_terminow_terminal, R.drawable.ic_terminow_hosts} :
            new int[]{R.drawable.ic_terminow_terminal, R.drawable.ic_terminow_hosts, R.drawable.ic_terminow_hosts};
        int count = isDynamic() ? 2 : 3;
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                View line = new View(mActivity);
                line.setBackgroundColor(color(i == 1 ? R.color.terminow_panel_accent : R.color.terminow_text_secondary));
                LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(dp(44), dp(3));
                lineParams.setMargins(dp(8), 0, dp(8), 0);
                diagram.addView(line, lineParams);
            }
            ImageView iconView = new ImageView(mActivity);
            iconView.setImageResource(icons[i]);
            iconView.setBackgroundResource(R.drawable.terminow_panel_button);
            iconView.setPadding(dp(12), dp(12), dp(12), dp(12));
            iconView.setColorFilter(color(i == 0 ? R.color.terminow_text : R.color.terminow_text_secondary), PorterDuff.Mode.SRC_IN);
            diagram.addView(iconView, new LinearLayout.LayoutParams(dp(52), dp(52)));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(28);
        params.bottomMargin = dp(32);
        mContentView.addView(diagram, params);
    }

    private void addHeading(int textRes) {
        TextView view = new TextView(mActivity);
        view.setText(textRes);
        view.setTextColor(color(R.color.terminow_text));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        mContentView.addView(view, params);
    }

    private void addBody(int textRes) {
        TextView view = new TextView(mActivity);
        view.setText(textRes);
        view.setTextColor(color(R.color.terminow_text));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setLineSpacing(dp(4), 1);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        params.bottomMargin = dp(10);
        mContentView.addView(view, params);
    }

    @NonNull
    private EditText addField(int hintRes, int inputType, @Nullable String text) {
        EditText field = new EditText(mActivity);
        field.setHint(hintRes);
        field.setInputType(inputType);
        field.setText(text);
        field.setMaxLines(1);
        field.setBackgroundResource(R.drawable.terminow_panel_field);
        field.setPadding(0, 0, 0, 0);
        field.setTextColor(color(R.color.terminow_text));
        field.setHintTextColor(color(R.color.terminow_text_secondary));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        params.topMargin = dp(10);
        mContentView.addView(field, params);
        return field;
    }

    @NonNull
    private TextView addButton(int textRes, boolean primary, @NonNull Runnable onClick) {
        TextView button = new TextView(mActivity);
        button.setText(textRes);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(color(R.color.terminow_text));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTypeface(null, Typeface.BOLD);
        button.setBackgroundResource(primary ? R.drawable.terminow_fab : R.drawable.terminow_wizard_button);
        button.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.topMargin = dp(16);
        mContentView.addView(button, params);
        return button;
    }

    /** Only enable a button while all the fields have text, like the continue button of a step. */
    private void enableWhenFilled(@NonNull TextView button, @NonNull EditText... fields) {
        Runnable update = () -> {
            boolean filled = true;
            for (EditText field : fields)
                filled &= !field.getText().toString().trim().isEmpty();
            button.setEnabled(filled);
            button.setAlpha(filled ? 1f : 0.4f);
        };
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { update.run(); }
        };
        for (EditText field : fields)
            field.addTextChangedListener(watcher);
        update.run();
    }

    private void addHostRow(@NonNull TerminowStore.Host host, @NonNull Runnable onClick) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.terminow_panel_card);
        row.setPadding(dp(12), 0, dp(12), 0);

        ImageView iconView = new ImageView(mActivity);
        iconView.setImageResource(R.drawable.ic_terminow_hosts);
        iconView.setColorFilter(color(R.color.terminow_text), PorterDuff.Mode.SRC_IN);
        row.addView(iconView, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(mActivity);
        titleView.setText(host.getTitle());
        titleView.setTextColor(color(R.color.terminow_text));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setMaxLines(1);
        texts.addView(titleView);
        TextView subtitleView = new TextView(mActivity);
        subtitleView.setText((host.username.isEmpty() ? "" : host.username + "@") + host.address);
        subtitleView.setTextColor(color(R.color.terminow_text_secondary));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitleView.setMaxLines(1);
        texts.addView(subtitleView);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textsParams.setMarginStart(dp(14));
        row.addView(texts, textsParams);

        row.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        params.topMargin = dp(10);
        mContentView.addView(row, params);
    }

    private int color(int colorRes) {
        return ContextCompat.getColor(mActivity, colorRes);
    }

    private int dp(float dp) {
        return (int) ViewUtils.dpToPx(mActivity, dp);
    }

}
