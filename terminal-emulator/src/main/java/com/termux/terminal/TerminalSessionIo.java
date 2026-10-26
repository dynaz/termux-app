package com.termux.terminal;

/**
 * An external byte source and sink for a {@link TerminalSession} that is <em>not</em> a forked
 * process with a pseudo-terminal, such as a USB serial console.
 *
 * <p>A normal {@link TerminalSession} forks a process and bridges its pty master file descriptor to
 * the terminal emulator. A device like a USB serial adapter has no process and no file descriptor
 * that an unprivileged forked process could reach: its bytes flow through the Android USB host API
 * in Java. A {@code TerminalSessionIo} lets such a stream drive the same emulator, session list and
 * view as any other tab, so nothing in the display layer is special-cased.
 *
 * <p>Threading: {@link #open}, {@link #read} and {@link #write} run on the session's own reader and
 * writer threads, never the main thread. {@link #resize} runs on the main thread. {@link #close}
 * may run on either and must be safe to call more than once. The implementation lives in the app
 * module; this interface carries no Android or app types so the terminal-emulator module keeps its
 * independence.
 */
public interface TerminalSessionIo {

    /**
     * Open the connection. Called once, before any {@link #read} or {@link #write}. The columns and
     * rows are the initial terminal size, which a serial line normally ignores.
     *
     * @throws Exception if the connection could not be opened; its message is shown in the terminal.
     */
    void open(int columns, int rows) throws Exception;

    /**
     * Read available bytes into {@code buffer}.
     *
     * @return the number of bytes read into {@code buffer} (may be {@code 0} when nothing arrived
     * before an internal timeout, in which case the caller simply reads again), or {@code -1} when
     * the connection has ended and no more bytes will come.
     * @throws Exception if reading failed; the session then ends.
     */
    int read(byte[] buffer) throws Exception;

    /** Write {@code count} bytes from {@code data} starting at {@code offset} to the connection. */
    void write(byte[] data, int offset, int count) throws Exception;

    /** The terminal was resized. A serial line has no window size, so this is usually a no-op. */
    void resize(int columns, int rows);

    /** Close the connection and release its resources. Safe to call more than once. */
    void close();

    /** A short, non-sensitive label for diagnostics, like {@code "CP2102 115200 8N1"}. */
    String describe();
}
