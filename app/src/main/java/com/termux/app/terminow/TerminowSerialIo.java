package com.termux.app.terminow;

import androidx.annotation.NonNull;

import com.termux.terminal.TerminalSessionIo;

import java.io.IOException;

/**
 * Bridges a {@link TerminowSerialLine} (a USB serial port) to a {@link com.termux.terminal.TerminalSession}
 * so a serial console appears as an ordinary terminal tab. The session's reader thread calls
 * {@link #read}, its writer thread calls {@link #write}, and the session closes the line through
 * {@link #close} when the tab ends or the device is unplugged.
 *
 * <p>The small state machine (NEW → OPEN → CLOSED) makes the lifecycle explicit and testable: reads
 * and writes do nothing once closed, and {@link #close} is idempotent so an unplug and a tab close
 * racing each other still release the port exactly once.
 */
public final class TerminowSerialIo implements TerminalSessionIo {

    /** The lifecycle of the bridge. */
    public enum State {NEW, OPEN, CLOSED}

    static final int READ_TIMEOUT_MILLIS = 500;
    static final int WRITE_TIMEOUT_MILLIS = 2000;

    private final TerminowSerialLine mLine;
    private final TerminowSerialConfig mConfig;
    private volatile State mState = State.NEW;

    public TerminowSerialIo(@NonNull TerminowSerialLine line, @NonNull TerminowSerialConfig config) {
        this.mLine = line;
        this.mConfig = config;
    }

    @Override
    public void open(int columns, int rows) throws Exception {
        if (mState == State.CLOSED) throw new IOException("Serial line already closed");
        mLine.open(mConfig);
        mState = State.OPEN;
    }

    @Override
    public int read(byte[] buffer) throws Exception {
        if (mState != State.OPEN) return -1;
        // 0 means nothing arrived before the timeout (the caller reads again); -1 means end.
        return mLine.read(buffer, READ_TIMEOUT_MILLIS);
    }

    @Override
    public void write(byte[] data, int offset, int count) throws Exception {
        if (mState != State.OPEN || count <= 0) return;
        mLine.write(data, offset, count, WRITE_TIMEOUT_MILLIS);
    }

    @Override
    public void resize(int columns, int rows) {
        // A serial line has no window size.
    }

    @Override
    public void close() {
        if (mState == State.CLOSED) return;
        mState = State.CLOSED;
        mLine.close();
    }

    @NonNull
    @Override
    public String describe() {
        return mLine.name() + " " + mConfig.summary();
    }

    /** The current lifecycle state (exposed for tests). */
    public State state() {
        return mState;
    }
}
