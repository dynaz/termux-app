package com.termux.app.terminow;

import androidx.annotation.NonNull;

/**
 * The line settings of a serial connection: baud rate, data bits, stop bits, parity and flow
 * control. A plain, immutable value object with no Android or USB-library types, so it can be built,
 * validated and summarised (like {@code "115200 8N1"}) in isolation and in tests. The mapping to the
 * USB serial port constants lives in {@link TerminowSerialLine} implementations.
 */
public final class TerminowSerialConfig {

    /** The baud rates offered in the settings, from slow console defaults to fast links. */
    public static final int[] BAUD_PRESETS = {1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600};

    // Stop-bit codes match usb-serial-for-android's UsbSerialPort: 1, 2 and 3 (= 1.5).
    public static final int STOPBITS_1 = 1, STOPBITS_2 = 2, STOPBITS_1_5 = 3;
    // Parity codes match usb-serial-for-android's UsbSerialPort.
    public static final int PARITY_NONE = 0, PARITY_ODD = 1, PARITY_EVEN = 2, PARITY_MARK = 3, PARITY_SPACE = 4;
    // Flow control, mapped to the library in the line implementation.
    public static final int FLOW_NONE = 0, FLOW_RTS_CTS = 1, FLOW_XON_XOFF = 2;

    public final int baudRate;
    public final int dataBits;
    public final int stopBits;
    public final int parity;
    public final int flowControl;

    public TerminowSerialConfig(int baudRate, int dataBits, int stopBits, int parity, int flowControl) {
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
        this.flowControl = flowControl;
    }

    /** The common default for a USB console: 115200 8N1, no flow control. */
    @NonNull
    public static TerminowSerialConfig defaults() {
        return new TerminowSerialConfig(115200, 8, STOPBITS_1, PARITY_NONE, FLOW_NONE);
    }

    @NonNull
    public TerminowSerialConfig withBaud(int baud) {
        return new TerminowSerialConfig(baud, dataBits, stopBits, parity, flowControl);
    }

    @NonNull
    public TerminowSerialConfig withDataBits(int bits) {
        return new TerminowSerialConfig(baudRate, bits, stopBits, parity, flowControl);
    }

    @NonNull
    public TerminowSerialConfig withStopBits(int bits) {
        return new TerminowSerialConfig(baudRate, dataBits, bits, parity, flowControl);
    }

    @NonNull
    public TerminowSerialConfig withParity(int p) {
        return new TerminowSerialConfig(baudRate, dataBits, stopBits, p, flowControl);
    }

    @NonNull
    public TerminowSerialConfig withFlowControl(int flow) {
        return new TerminowSerialConfig(baudRate, dataBits, stopBits, parity, flow);
    }

    /** Whether every field is within the ranges the USB serial drivers accept. */
    public boolean isValid() {
        if (baudRate <= 0) return false;
        if (dataBits < 5 || dataBits > 8) return false;
        if (stopBits != STOPBITS_1 && stopBits != STOPBITS_2 && stopBits != STOPBITS_1_5) return false;
        if (parity < PARITY_NONE || parity > PARITY_SPACE) return false;
        return flowControl >= FLOW_NONE && flowControl <= FLOW_XON_XOFF;
    }

    /** The single-letter parity code used in a summary: N, O, E, M or S. */
    public char parityLetter() {
        switch (parity) {
            case PARITY_ODD: return 'O';
            case PARITY_EVEN: return 'E';
            case PARITY_MARK: return 'M';
            case PARITY_SPACE: return 'S';
            default: return 'N';
        }
    }

    /** The stop-bit count as shown to a person: "1", "2" or "1.5". */
    @NonNull
    public String stopBitsLabel() {
        if (stopBits == STOPBITS_2) return "2";
        if (stopBits == STOPBITS_1_5) return "1.5";
        return "1";
    }

    @NonNull
    public String flowControlLabel() {
        if (flowControl == FLOW_RTS_CTS) return "RTS/CTS";
        if (flowControl == FLOW_XON_XOFF) return "XON/XOFF";
        return "None";
    }

    /** A compact summary like {@code "115200 8N1"} (flow control is left out unless set). */
    @NonNull
    public String summary() {
        String base = baudRate + " " + dataBits + parityLetter() + stopBitsLabel();
        return flowControl == FLOW_NONE ? base : base + " " + flowControlLabel();
    }

    @NonNull
    @Override
    public String toString() {
        return summary();
    }
}
