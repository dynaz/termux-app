package com.termux.app.terminow;

import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;

/**
 * The Serial Connection screens: pick a plugged-in USB serial adapter, set the line parameters, and
 * open it as a real terminal tab through {@link TerminowSerialManager} and {@link TerminowSerialIo}.
 * Nothing is scanned or installed automatically and no session starts until the person taps Connect.
 * A "How to connect" page explains the cabling, since the wrong adapter or port is the usual problem.
 */
public final class TerminowSerialUi {

    private final TermuxActivity mActivity;
    private final TerminowSerialManager mManager;

    public TerminowSerialUi(@NonNull TermuxActivity activity) {
        this.mActivity = activity;
        this.mManager = new TerminowSerialManager(activity);
    }

    /** Entry point from the "+" menu: show the device picker. */
    public void show() {
        if (!mManager.isSupported()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_serial_no_usb), true);
            return;
        }
        showPicker();
    }

    private void showPicker() {
        List<TerminowSerialManager.SerialDevice> devices = mManager.listDevices();

        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_serial_title)
            .setNeutralButton(R.string.terminow_serial_how, (d, w) -> showHowToConnect())
            .setNegativeButton(android.R.string.cancel, null);

        if (devices.isEmpty()) {
            LinearLayout box = new LinearLayout(mActivity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = dp(20);
            box.setPadding(pad, dp(16), pad, dp(8));

            // A USB console cable in the middle, above the message.
            ImageView cable = new ImageView(mActivity);
            cable.setImageResource(R.drawable.ic_terminow_serial_cable);
            LinearLayout.LayoutParams cableLp = new LinearLayout.LayoutParams(dp(120), dp(120));
            cableLp.bottomMargin = dp(12);
            box.addView(cable, cableLp);

            TextView empty = new TextView(mActivity);
            empty.setText(R.string.terminow_serial_none);
            empty.setGravity(Gravity.CENTER);
            box.addView(empty);

            builder.setView(box);
            // Refresh re-lists without leaving the picker.
            builder.setPositiveButton(R.string.terminow_serial_refresh, (d, w) -> showPicker());
            builder.show();
            return;
        }

        List<CharSequence> labels = new ArrayList<>();
        for (TerminowSerialManager.SerialDevice device : devices)
            labels.add(device.name + "\n" + device.details);

        builder.setItems(labels.toArray(new CharSequence[0]), (d, which) -> showSettings(devices.get(which)));
        builder.show();
    }

    private void showSettings(@NonNull TerminowSerialManager.SerialDevice device) {
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, dp(8), pad, 0);

        Spinner baud = labelledSpinner(content, R.string.terminow_serial_baud, baudLabels(), baudDefaultIndex());
        Spinner dataBits = labelledSpinner(content, R.string.terminow_serial_data_bits,
            new String[]{"5", "6", "7", "8"}, 3);
        Spinner stopBits = labelledSpinner(content, R.string.terminow_serial_stop_bits,
            new String[]{"1", "2", "1.5"}, 0);
        Spinner parity = labelledSpinner(content, R.string.terminow_serial_parity, new String[]{
            mActivity.getString(R.string.terminow_serial_parity_none),
            mActivity.getString(R.string.terminow_serial_parity_odd),
            mActivity.getString(R.string.terminow_serial_parity_even),
            mActivity.getString(R.string.terminow_serial_parity_mark),
            mActivity.getString(R.string.terminow_serial_parity_space)}, 0);
        Spinner flow = labelledSpinner(content, R.string.terminow_serial_flow, new String[]{
            mActivity.getString(R.string.terminow_serial_flow_none),
            mActivity.getString(R.string.terminow_serial_flow_rtscts),
            mActivity.getString(R.string.terminow_serial_flow_xonxoff)}, 0);

        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(content);

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(device.name)
            .setView(scroll)
            .setNeutralButton(R.string.terminow_serial_how, (d, w) -> showHowToConnect())
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.terminow_serial_connect, (d, w) -> {
                TerminowSerialConfig config = new TerminowSerialConfig(
                    TerminowSerialConfig.BAUD_PRESETS[baud.getSelectedItemPosition()],
                    5 + dataBits.getSelectedItemPosition(),
                    stopBitsCode(stopBits.getSelectedItemPosition()),
                    parity.getSelectedItemPosition(),
                    flow.getSelectedItemPosition());
                connect(device, config);
            })
            .show();
    }

    private void connect(@NonNull TerminowSerialManager.SerialDevice device, @NonNull TerminowSerialConfig config) {
        mActivity.showToast(mActivity.getString(R.string.terminow_serial_connecting, device.name), false);
        mManager.requestConnection(device, new TerminowSerialManager.OpenListener() {
            @Override
            public void onLineReady(@NonNull TerminowSerialLine line) {
                openSession(line, config);
            }

            @Override
            public void onPermissionDenied(@NonNull TerminowSerialManager.SerialDevice device) {
                mActivity.showToast(mActivity.getString(R.string.terminow_serial_permission_denied, device.name), true);
            }

            @Override
            public void onError(@NonNull String message) {
                mActivity.showToast(message, true);
            }
        });
    }

    private void openSession(@NonNull TerminowSerialLine line, @NonNull TerminowSerialConfig config) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) {
            line.close();
            mActivity.showToast(mActivity.getString(R.string.terminow_serial_no_service), true);
            return;
        }

        TerminowSerialIo io = new TerminowSerialIo(line, config);
        TerminalSession session = new TerminalSession(io, mActivity.getProperties().getTerminalTranscriptRows(),
            mActivity.getTermuxTerminalSessionClient());

        service.attachExternalTermuxSession(session, io.describe());
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(session);

        if (mActivity.getTerminowHome() != null) mActivity.getTerminowHome().hide();
        if (mActivity.getDrawer() != null) mActivity.getDrawer().closeDrawers();
    }

    private void showHowToConnect() {
        TextView body = new TextView(mActivity);
        body.setText(R.string.terminow_serial_how_body);
        int pad = dp(20);
        body.setPadding(pad, pad, pad, pad);
        body.setLineSpacing(dp(4), 1f);
        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(body);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_serial_how_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    private Spinner labelledSpinner(@NonNull LinearLayout parent, int labelRes, @NonNull String[] options, int selected) {
        TextView label = new TextView(mActivity);
        label.setText(labelRes);
        label.setPadding(0, dp(12), 0, dp(2));
        parent.addView(label);

        Spinner spinner = new Spinner(mActivity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(mActivity, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        spinner.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(spinner, lp);
        return spinner;
    }

    @NonNull
    private static String[] baudLabels() {
        String[] labels = new String[TerminowSerialConfig.BAUD_PRESETS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = String.valueOf(TerminowSerialConfig.BAUD_PRESETS[i]);
        return labels;
    }

    private static int baudDefaultIndex() {
        int[] presets = TerminowSerialConfig.BAUD_PRESETS;
        int defaultBaud = TerminowSerialConfig.defaults().baudRate;
        for (int i = 0; i < presets.length; i++) if (presets[i] == defaultBaud) return i;
        return 0;
    }

    private static int stopBitsCode(int spinnerIndex) {
        // Spinner order is "1", "2", "1.5".
        if (spinnerIndex == 1) return TerminowSerialConfig.STOPBITS_2;
        if (spinnerIndex == 2) return TerminowSerialConfig.STOPBITS_1_5;
        return TerminowSerialConfig.STOPBITS_1;
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
