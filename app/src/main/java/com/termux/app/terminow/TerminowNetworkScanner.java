package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A bounded, cancellable local network scan, separate from any UI. It probes selected TCP ports on the
 * addresses of a range with bounded concurrency, per-probe timeouts and an overall timeout, and streams
 * devices to a {@link Listener} as they are found.
 *
 * <p>Classification follows the spec: a port that connects is an open port and the host is reachable; a
 * <b>refused</b> connection also means the host is reachable (just that port closed); a <b>timeout</b>
 * alone is inconclusive, so a host that only times out is never reported as a device and is never marked
 * offline. Ports are recorded as open ports, not as verified products.
 *
 * <p>The probe is injected so the orchestration — bounded concurrency, cancellation and ignoring late
 * events — is testable without real sockets; {@link #socketProber()} is the real one.
 */
public final class TerminowNetworkScanner {

    private TerminowNetworkScanner() {}

    /** The outcome of one TCP probe. */
    public enum ProbeResult { OPEN, REFUSED, TIMEOUT, ERROR }

    /** How a scan ended. */
    public enum Status { COMPLETED, CANCELLED, TIMED_OUT }

    public interface Prober {
        @NonNull ProbeResult probe(@NonNull String ip, int port, int timeoutMs);
    }

    /**
     * An ICMP-style reachability check for one address, used so a host that answers a ping but has no
     * open TCP port is still discovered. It is injected so the sweep is testable without real ICMP.
     *
     * <p><b>Android limitation:</b> a non-root app cannot open a raw ICMP socket, so the real prober
     * ({@link #icmpReachProber()}) uses {@link java.net.InetAddress#isReachable(int)}, which attempts
     * an ICMP echo and, where that is not permitted, falls back to a TCP connection to port 7. A host
     * that neither answers ICMP nor accepts that fallback is not found by ping alone; its open TCP
     * ports still make it reachable through the ordinary port probe.
     */
    public interface ReachProber {
        boolean reachable(@NonNull String ip, int timeoutMs);
    }

    public interface Listener {
        /** A device became reachable, or its open ports changed. Called on the scan thread. */
        void onDevice(@NonNull TerminowDiscoveredDevice device);
        void onProgress(int probed, int total, int devicesFound);
        void onFinished(@NonNull Status status, @NonNull List<TerminowDiscoveredDevice> devices);
    }

    public static final class Config {
        @NonNull public final String scopeId;
        @NonNull public final List<String> addresses;
        @NonNull public final List<Integer> ports;
        public final int concurrency;
        public final int probeTimeoutMs;
        public final long overallTimeoutMs;

        public Config(@NonNull String scopeId, @NonNull List<String> addresses, @NonNull List<Integer> ports,
                      int concurrency, int probeTimeoutMs, long overallTimeoutMs) {
            this.scopeId = scopeId;
            this.addresses = addresses;
            this.ports = ports;
            this.concurrency = Math.max(1, concurrency);
            this.probeTimeoutMs = Math.max(1, probeTimeoutMs);
            this.overallTimeoutMs = overallTimeoutMs;
        }
    }

    /**
     * Run the scan on the calling thread. Set {@code cancelled} from another thread to stop it; results
     * that complete after that are ignored and no further device is reported.
     */
    public static void scan(@NonNull Config config, @NonNull Prober prober,
                            @NonNull AtomicBoolean cancelled, @NonNull Listener listener) {
        scan(config, prober, null, cancelled, listener);
    }

    /**
     * Run the scan with an optional ICMP reachability sweep. When {@code reach} is non-null, each
     * address is also pinged; a host that answers the ping is reported as a reachable device even if no
     * TCP port is open (its {@link TerminowDiscoveredDevice#respondsToPing} is set). A ping that fails
     * is inconclusive — exactly like a TCP timeout — so it never marks a host offline and never creates
     * a device. The per-address ping is counted as one more probe in the progress total.
     */
    public static void scan(@NonNull Config config, @NonNull Prober prober,
                            @androidx.annotation.Nullable ReachProber reach,
                            @NonNull AtomicBoolean cancelled, @NonNull Listener listener) {
        long deadline = config.overallTimeoutMs > 0 ? System.currentTimeMillis() + config.overallTimeoutMs : Long.MAX_VALUE;
        int perAddress = config.ports.size() + (reach != null ? 1 : 0);
        int total = config.addresses.size() * perAddress;
        // A bounded pool with a bounded queue and caller-runs: submitting blocks instead of queuing the
        // whole range, so memory and in-flight sockets stay bounded.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(config.concurrency, config.concurrency, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(config.concurrency * 2),
            new ThreadPoolExecutor.CallerRunsPolicy());

        Map<String, TerminowDiscoveredDevice> devices = new LinkedHashMap<>();
        final int[] probed = {0};
        Status status = Status.COMPLETED;

        try {
            outer:
            for (String ip : config.addresses) {
                if (reach != null) {
                    if (cancelled.get()) { status = Status.CANCELLED; break outer; }
                    if (System.currentTimeMillis() > deadline) { status = Status.TIMED_OUT; break outer; }
                    final String pIp = ip;
                    final ReachProber pReach = reach;
                    pool.execute(() -> {
                        boolean ok = !cancelled.get() && pReach.reachable(pIp, config.probeTimeoutMs);
                        synchronized (devices) {
                            if (cancelled.get()) return;
                            probed[0]++;
                            if (ok) {
                                TerminowDiscoveredDevice d = devices.get(pIp);
                                if (d == null) {
                                    d = new TerminowDiscoveredDevice(config.scopeId, pIp);
                                    d.firstSeenMs = System.currentTimeMillis();
                                    devices.put(pIp, d);
                                }
                                d.respondsToPing = true;
                                d.lastSeenMs = System.currentTimeMillis();
                                listener.onDevice(d);
                            }
                            listener.onProgress(probed[0], total, devices.size());
                        }
                    });
                }
                for (int port : config.ports) {
                    if (cancelled.get()) { status = Status.CANCELLED; break outer; }
                    if (System.currentTimeMillis() > deadline) { status = Status.TIMED_OUT; break outer; }
                    final String fIp = ip;
                    final int fPort = port;
                    pool.execute(() -> {
                        ProbeResult r = cancelled.get() ? ProbeResult.ERROR : prober.probe(fIp, fPort, config.probeTimeoutMs);
                        synchronized (devices) {
                            // Ignore anything that finishes after a cancel: no late event reaches the UI.
                            if (cancelled.get()) return;
                            probed[0]++;
                            boolean reachable = r == ProbeResult.OPEN || r == ProbeResult.REFUSED;
                            if (reachable) {
                                TerminowDiscoveredDevice d = devices.get(fIp);
                                boolean isNew = d == null;
                                if (isNew) {
                                    d = new TerminowDiscoveredDevice(config.scopeId, fIp);
                                    d.firstSeenMs = System.currentTimeMillis();
                                    devices.put(fIp, d);
                                }
                                if (r == ProbeResult.OPEN) d.openPorts.add(fPort);
                                d.lastSeenMs = System.currentTimeMillis();
                                listener.onDevice(d);
                            }
                            listener.onProgress(probed[0], total, devices.size());
                        }
                    });
                }
            }
        } finally {
            if (status == Status.COMPLETED && cancelled.get()) status = Status.CANCELLED;
            pool.shutdown();
            try {
                long wait = Math.max(0, deadline - System.currentTimeMillis());
                if (!pool.awaitTermination(status == Status.COMPLETED ? Math.min(wait, 60_000) : 0, TimeUnit.MILLISECONDS)) {
                    pool.shutdownNow();
                    if (status == Status.COMPLETED) status = Status.TIMED_OUT;
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        List<TerminowDiscoveredDevice> result;
        synchronized (devices) {
            result = new ArrayList<>(devices.values());
        }
        listener.onFinished(status, result);
    }

    /** The real prober: a bounded TCP connect. Open = connected, refused = host reachable, else timeout. */
    @NonNull
    public static Prober socketProber() {
        return (ip, port, timeoutMs) -> {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(ip, port), timeoutMs);
                return ProbeResult.OPEN;
            } catch (java.net.SocketTimeoutException e) {
                return ProbeResult.TIMEOUT;
            } catch (java.net.ConnectException e) {
                // "Connection refused" means the host answered; a filtered/unreachable error does not.
                String m = e.getMessage();
                return m != null && m.toLowerCase(java.util.Locale.ROOT).contains("refused") ? ProbeResult.REFUSED : ProbeResult.ERROR;
            } catch (IOException e) {
                return ProbeResult.ERROR;
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        };
    }

    /**
     * The real reachability prober: {@link java.net.InetAddress#isReachable(int)}. See {@link ReachProber}
     * for its Android limitation (no raw ICMP without root; TCP-echo fallback). A lookup or I/O error is
     * treated as "not reached", never as an error that stops the sweep.
     */
    @NonNull
    public static ReachProber icmpReachProber() {
        return (ip, timeoutMs) -> {
            try {
                return java.net.InetAddress.getByName(ip).isReachable(Math.max(1, timeoutMs));
            } catch (IOException e) {
                return false;
            }
        };
    }

    /**
     * The default TCP ports, probed alongside the ping sweep so a device's likely type can be inferred
     * from what it exposes (SSH, web, Windows/SMB, printing, NAS, RDP, VNC). Port numbers are hints, not
     * proof of a service or a product.
     */
    @NonNull
    public static List<Integer> defaultPorts() {
        List<Integer> ports = new ArrayList<>();
        for (int p : new int[]{22, 80, 443, 139, 445, 515, 631, 9100, 5000, 8080, 5900, 3389}) ports.add(p);
        return ports;
    }
}
