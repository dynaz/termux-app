package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

/**
 * The serial-to-terminal bridge lifecycle, with a fake line instead of a USB port:
 * <ul>
 *   <li>state moves NEW → OPEN → CLOSED and reads/writes are gated by it,</li>
 *   <li>close releases the port exactly once even when called twice (an unplug racing a tab close),</li>
 *   <li>a disconnect while reading surfaces as an exception so the session ends,</li>
 *   <li>and the permission rule denies unless the broadcast and the manager both agree.</li>
 * </ul>
 */
public class TerminowSerialIoTest {

    /** A {@link TerminowSerialLine} that records calls and can be told to fail its next read. */
    private static final class FakeSerialLine implements TerminowSerialLine {
        boolean open;
        int openCalls, closeCalls;
        TerminowSerialConfig openedWith;
        byte[] lastWrite;
        int nextReadBytes;
        IOException failReadWith;

        @Override public void open(TerminowSerialConfig config) {
            openCalls++;
            openedWith = config;
            open = true;
        }
        @Override public int read(byte[] buffer, int timeoutMillis) throws IOException {
            if (failReadWith != null) throw failReadWith;
            return nextReadBytes;
        }
        @Override public void write(byte[] data, int offset, int count, int timeoutMillis) {
            lastWrite = new byte[count];
            System.arraycopy(data, offset, lastWrite, 0, count);
        }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { closeCalls++; open = false; }
        @Override public String name() { return "CP2102"; }
    }

    @Test
    public void startsNew_thenOpens() throws Exception {
        FakeSerialLine line = new FakeSerialLine();
        TerminowSerialConfig config = TerminowSerialConfig.defaults();
        TerminowSerialIo io = new TerminowSerialIo(line, config);

        assertEquals(TerminowSerialIo.State.NEW, io.state());
        io.open(80, 24);
        assertEquals(TerminowSerialIo.State.OPEN, io.state());
        assertEquals(1, line.openCalls);
        assertEquals(config, line.openedWith);
    }

    @Test
    public void readBeforeOpenReturnsEnd() throws Exception {
        TerminowSerialIo io = new TerminowSerialIo(new FakeSerialLine(), TerminowSerialConfig.defaults());
        assertEquals(-1, io.read(new byte[16]));
    }

    @Test
    public void readAndWriteDelegateWhileOpen() throws Exception {
        FakeSerialLine line = new FakeSerialLine();
        TerminowSerialIo io = new TerminowSerialIo(line, TerminowSerialConfig.defaults());
        io.open(80, 24);

        line.nextReadBytes = 4;
        assertEquals(4, io.read(new byte[16]));

        byte[] data = {'l', 's', '\r'};
        io.write(data, 0, data.length);
        assertEquals("ls\r", new String(line.lastWrite));
    }

    @Test
    public void closeIsIdempotentAndReleasesOnce() throws Exception {
        FakeSerialLine line = new FakeSerialLine();
        TerminowSerialIo io = new TerminowSerialIo(line, TerminowSerialConfig.defaults());
        io.open(80, 24);

        io.close();
        io.close();
        assertEquals(TerminowSerialIo.State.CLOSED, io.state());
        assertEquals("port released exactly once", 1, line.closeCalls);

        // After close, reads report end and writes are dropped without touching the line.
        line.nextReadBytes = 9;
        assertEquals(-1, io.read(new byte[16]));
        io.write(new byte[]{1}, 0, 1);
        assertFalse(line.open);
    }

    @Test
    public void openAfterCloseIsRejected() throws Exception {
        TerminowSerialIo io = new TerminowSerialIo(new FakeSerialLine(), TerminowSerialConfig.defaults());
        io.open(80, 24);
        io.close();
        try {
            io.open(80, 24);
            fail("Expected opening a closed line to throw");
        } catch (IOException expected) {
            // The bridge must not reopen a closed line.
        }
    }

    @Test
    public void disconnectWhileReadingSurfacesAsException() throws Exception {
        FakeSerialLine line = new FakeSerialLine();
        TerminowSerialIo io = new TerminowSerialIo(line, TerminowSerialConfig.defaults());
        io.open(80, 24);
        line.failReadWith = new IOException("device disconnected");
        try {
            io.read(new byte[16]);
            fail("Expected the disconnect to propagate so the session ends");
        } catch (IOException expected) {
            assertEquals("device disconnected", expected.getMessage());
        }
    }

    @Test
    public void describeNamesTheDeviceAndSettings() {
        TerminowSerialIo io = new TerminowSerialIo(new FakeSerialLine(), TerminowSerialConfig.defaults());
        assertEquals("CP2102 115200 8N1", io.describe());
    }

    @Test
    public void permissionDeniedUnlessBroadcastAndManagerAgree() {
        assertTrue(TerminowSerialManager.permissionGranted(true, true));
        assertFalse(TerminowSerialManager.permissionGranted(true, false));
        assertFalse(TerminowSerialManager.permissionGranted(false, true));
        assertFalse(TerminowSerialManager.permissionGranted(false, false));
    }
}
