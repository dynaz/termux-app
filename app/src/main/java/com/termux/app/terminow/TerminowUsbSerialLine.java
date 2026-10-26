package com.termux.app.terminow;

import android.hardware.usb.UsbDeviceConnection;

import androidx.annotation.NonNull;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.util.Arrays;

/**
 * The real {@link TerminowSerialLine}, backed by a usb-serial-for-android {@link UsbSerialPort} and
 * the {@link UsbDeviceConnection} that {@link TerminowSerialManager} opened for it. All of the USB
 * library contact is here, so {@link TerminowSerialIo} and its tests stay free of it.
 */
public final class TerminowUsbSerialLine implements TerminowSerialLine {

    private final UsbSerialPort mPort;
    private final UsbDeviceConnection mConnection;
    private final String mName;

    public TerminowUsbSerialLine(@NonNull UsbSerialPort port, @NonNull UsbDeviceConnection connection, @NonNull String name) {
        this.mPort = port;
        this.mConnection = connection;
        this.mName = name;
    }

    @Override
    public void open(TerminowSerialConfig config) throws Exception {
        mPort.open(mConnection);
        // The data-bit count and the stop-bit and parity codes match the library's constants.
        mPort.setParameters(config.baudRate, config.dataBits, config.stopBits, config.parity);

        // Flow control is best-effort: some drivers do not support every mode.
        try {
            switch (config.flowControl) {
                case TerminowSerialConfig.FLOW_RTS_CTS:
                    mPort.setFlowControl(UsbSerialPort.FlowControl.RTS_CTS);
                    break;
                case TerminowSerialConfig.FLOW_XON_XOFF:
                    mPort.setFlowControl(UsbSerialPort.FlowControl.XON_XOFF);
                    break;
                default:
                    mPort.setFlowControl(UsbSerialPort.FlowControl.NONE);
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Driver without configurable flow control; leave it at the device default.
        }

        // Many consoles only come alive once DTR and RTS are asserted; ignore if unsupported.
        try {
            mPort.setDTR(true);
            mPort.setRTS(true);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Driver without modem-control lines.
        }
    }

    @Override
    public int read(byte[] buffer, int timeoutMillis) throws Exception {
        // The library returns the byte count (0 on timeout) and throws on disconnect.
        return mPort.read(buffer, timeoutMillis);
    }

    @Override
    public void write(byte[] data, int offset, int count, int timeoutMillis) throws Exception {
        byte[] chunk = (offset == 0 && count == data.length) ? data : Arrays.copyOfRange(data, offset, offset + count);
        mPort.write(chunk, timeoutMillis);
    }

    @Override
    public boolean isOpen() {
        return mPort.isOpen();
    }

    @Override
    public void close() {
        try {
            mPort.close();
        } catch (IOException ignored) {
            // Already closed or the device is gone.
        }
        try {
            mConnection.close();
        } catch (Exception ignored) {
            // Already closed.
        }
    }

    @NonNull
    @Override
    public String name() {
        return mName;
    }
}
