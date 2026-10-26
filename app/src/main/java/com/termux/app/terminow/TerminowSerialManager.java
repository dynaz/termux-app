package com.termux.app.terminow;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds USB serial adapters, asks the person for USB permission, and opens a device into a
 * {@link TerminowSerialLine} ready to bridge to a terminal tab. Discovery never starts a session on
 * its own and never installs anything; it only lists what is already plugged in and probed by the
 * usb-serial-for-android drivers (FTDI, CP210x, CH34x, PL2303, CDC-ACM).
 */
public final class TerminowSerialManager {

    private static final String ACTION_USB_PERMISSION = "com.terminow.USB_PERMISSION";

    /** One probed serial endpoint: a driver's port, with a name for the picker. */
    public static final class SerialDevice {
        final UsbSerialDriver driver;
        final int portNumber;
        public final String name;
        public final String details;

        SerialDevice(UsbSerialDriver driver, int portNumber, String name, String details) {
            this.driver = driver;
            this.portNumber = portNumber;
            this.name = name;
            this.details = details;
        }

        UsbDevice usbDevice() {
            return driver.getDevice();
        }
    }

    /** The result of asking for permission and opening a device. */
    public interface OpenListener {
        /** The port is open-able: wrap it in a {@link TerminowSerialIo} and start a tab. */
        void onLineReady(@NonNull TerminowSerialLine line);

        /** The person denied USB permission for the device. */
        void onPermissionDenied(@NonNull SerialDevice device);

        /** Opening failed for another reason (device gone, no connection). */
        void onError(@NonNull String message);
    }

    private final Context mContext;
    private final UsbManager mUsbManager;

    public TerminowSerialManager(@NonNull Context context) {
        this.mContext = context.getApplicationContext();
        this.mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
    }

    /** Whether the device even has a USB manager (some do not). */
    public boolean isSupported() {
        return mUsbManager != null;
    }

    /** The serial endpoints currently plugged in and recognised by a driver. Never null. */
    @NonNull
    public List<SerialDevice> listDevices() {
        List<SerialDevice> result = new ArrayList<>();
        if (mUsbManager == null) return result;
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mUsbManager);
        for (UsbSerialDriver driver : drivers) {
            int ports = driver.getPorts().size();
            for (int port = 0; port < ports; port++) {
                result.add(new SerialDevice(driver, port, nameOf(driver, port, ports), detailsOf(driver)));
            }
        }
        return result;
    }

    /**
     * Ask for USB permission if needed and then open {@code device}. The listener is always called
     * once, on the main thread. Opening the port itself happens later, on the session's reader
     * thread, through {@link TerminowSerialLine#open}.
     */
    public void requestConnection(@NonNull SerialDevice device, @NonNull OpenListener listener) {
        UsbDevice usbDevice = device.usbDevice();
        if (mUsbManager.hasPermission(usbDevice)) {
            openLine(device, listener);
            return;
        }

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                try {
                    context.unregisterReceiver(this);
                } catch (IllegalArgumentException ignored) {
                    // Already unregistered.
                }
                boolean granted = permissionGranted(
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                    mUsbManager.hasPermission(usbDevice));
                if (granted) {
                    openLine(device, listener);
                } else {
                    listener.onPermissionDenied(device);
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(mContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(mContext.getPackageName());
        PendingIntent permissionIntent = PendingIntent.getBroadcast(mContext, 0, intent, flags);
        mUsbManager.requestPermission(usbDevice, permissionIntent);
    }

    /** Open the USB connection (permission is already granted) and hand back a line, or an error. */
    private void openLine(@NonNull SerialDevice device, @NonNull OpenListener listener) {
        UsbSerialPort port = device.driver.getPorts().get(device.portNumber);
        UsbDeviceConnection connection = mUsbManager.openDevice(device.usbDevice());
        if (connection == null) {
            listener.onError(mContext.getString(com.termux.R.string.terminow_serial_open_failed));
            return;
        }
        listener.onLineReady(new TerminowUsbSerialLine(port, connection, device.name));
    }

    @NonNull
    private static String nameOf(@NonNull UsbSerialDriver driver, int port, int portCount) {
        UsbDevice device = driver.getDevice();
        String product = null;
        try {
            product = device.getProductName();
        } catch (Exception ignored) {
            // Some devices report no product name.
        }
        String base = (product != null && !product.trim().isEmpty())
            ? product.trim()
            : driver.getClass().getSimpleName().replace("SerialDriver", "");
        return portCount > 1 ? base + " #" + port : base;
    }

    @NonNull
    private static String detailsOf(@NonNull UsbSerialDriver driver) {
        UsbDevice device = driver.getDevice();
        return String.format("USB %04x:%04x", device.getVendorId(), device.getProductId());
    }

    @Nullable
    public static UsbDevice detachedDevice(@NonNull Intent intent) {
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    public static boolean isDetach(@Nullable Intent intent) {
        return intent != null && UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction());
    }

    /**
     * The permission-result rule, pulled out so it can be tested without Android: access is granted
     * only when the broadcast reports it granted <em>and</em> the USB manager confirms the app now
     * holds permission. If either is false the connection is treated as denied.
     */
    static boolean permissionGranted(boolean broadcastGranted, boolean managerHasPermission) {
        return broadcastGranted && managerHasPermission;
    }
}
