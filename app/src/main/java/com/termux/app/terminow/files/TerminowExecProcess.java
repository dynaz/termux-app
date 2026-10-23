package com.termux.app.terminow.files;

import androidx.annotation.NonNull;

import com.jcraft.jsch.ChannelExec;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * A command running on an SSH host over a JSch exec channel, seen as a {@link Process}: its
 * output, error and input streams, and its exit code. It never asks the host for a terminal, so
 * it does not touch any interactive session of the host, and it is the way {@link
 * com.termux.app.terminow.TerminowToolInstaller} installs tools on a server with the same code
 * that installs them in a local environment.
 */
public final class TerminowExecProcess extends Process {

    private final ChannelExec mChannel;
    private final InputStream mIn;
    private final InputStream mErr;
    private final OutputStream mOut;

    TerminowExecProcess(@NonNull ChannelExec channel, @NonNull InputStream in, @NonNull InputStream err,
                        @NonNull OutputStream out) {
        mChannel = channel;
        mIn = in;
        mErr = err;
        mOut = out;
    }

    @NonNull
    @Override
    public OutputStream getOutputStream() {
        return mOut;
    }

    @NonNull
    @Override
    public InputStream getInputStream() {
        return mIn;
    }

    @NonNull
    @Override
    public InputStream getErrorStream() {
        return mErr;
    }

    @Override
    public int waitFor() throws InterruptedException {
        while (!mChannel.isClosed()) {
            if (Thread.interrupted()) throw new InterruptedException();
            Thread.sleep(50);
        }
        // The exit status can lag a moment behind the channel closing
        for (int i = 0; i < 40 && mChannel.getExitStatus() == -1; i++) Thread.sleep(50);
        return mChannel.getExitStatus();
    }

    @Override
    public int exitValue() {
        if (!mChannel.isClosed()) throw new IllegalThreadStateException("The command is still running");
        // -1 when the command was killed by a signal or no status came: a failure, like any non-zero
        return mChannel.getExitStatus();
    }

    @Override
    public void destroy() {
        try {
            mChannel.sendSignal("TERM");
        } catch (Exception ignored) {
            // The server may not support signals; disconnecting the channel still stops it
        }
        mChannel.disconnect();
    }
}
