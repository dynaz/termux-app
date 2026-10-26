package com.termux.app.terminow;

/**
 * The narrow slice of a serial port that {@link TerminowSerialIo} needs: open with a configuration,
 * read, write, and close. A real implementation ({@link TerminowUsbSerialLine}) wraps a USB serial
 * port; tests use a fake. Keeping this interface free of Android and USB-library types lets the
 * session bridge and its lifecycle be tested without hardware.
 */
public interface TerminowSerialLine {

    /** Open the port and apply the line settings. Throws if the device rejects them or is gone. */
    void open(TerminowSerialConfig config) throws Exception;

    /**
     * Read available bytes into {@code buffer}, waiting up to {@code timeoutMillis}.
     *
     * @return the number of bytes read, {@code 0} if none arrived before the timeout, or {@code -1}
     * if the port has reached its end. (The USB driver reports a disconnect by throwing instead.)
     */
    int read(byte[] buffer, int timeoutMillis) throws Exception;

    /** Write {@code count} bytes from {@code data} at {@code offset}, waiting up to the timeout. */
    void write(byte[] data, int offset, int count, int timeoutMillis) throws Exception;

    /** Whether the port is currently open. */
    boolean isOpen();

    /** Close the port and release the USB connection. Safe to call more than once. */
    void close();

    /** A short, non-sensitive device name for diagnostics, like {@code "CP2102"} or {@code "ttyUSB0"}. */
    String name();
}
