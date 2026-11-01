package com.termux.app.terminow;

import static com.termux.app.terminow.TerminowNetworkScanner.ProbeResult;
import static com.termux.app.terminow.TerminowNetworkScanner.Status;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * The scan orchestration: OPEN and REFUSED make a host reachable (a timeout-only host is never
 * reported and never marked offline), open ports are recorded, cancellation stops the scan and no
 * device is reported for probes that finish after the cancel, and progress is counted.
 */
public class TerminowNetworkScannerTest {

    private static final class Recorder implements TerminowNetworkScanner.Listener {
        final List<TerminowDiscoveredDevice> devices = new ArrayList<>();
        Status status;
        List<TerminowDiscoveredDevice> finalDevices;
        int progressCalls;
        @Override public synchronized void onDevice(TerminowDiscoveredDevice device) { if (!devices.contains(device)) devices.add(device); }
        @Override public synchronized void onProgress(int probed, int total, int found) { progressCalls++; }
        @Override public void onFinished(Status status, List<TerminowDiscoveredDevice> devices) { this.status = status; this.finalDevices = devices; }
    }

    private static TerminowNetworkScanner.Config config(List<String> ips, List<Integer> ports) {
        return new TerminowNetworkScanner.Config("test", ips, ports, 8, 100, 0);
    }

    @Test public void openAndRefusedAreReachableTimeoutIsNot() {
        TerminowNetworkScanner.Prober prober = (ip, port, t) -> {
            if (ip.equals("10.0.0.1") && port == 22) return ProbeResult.OPEN;
            if (ip.equals("10.0.0.1") && port == 80) return ProbeResult.REFUSED;
            return ProbeResult.TIMEOUT; // 10.0.0.2 only times out -> not a device
        };
        Recorder r = new Recorder();
        TerminowNetworkScanner.scan(config(Arrays.asList("10.0.0.1", "10.0.0.2"), Arrays.asList(22, 80, 443)),
            prober, new AtomicBoolean(false), r);
        assertEquals(Status.COMPLETED, r.status);
        assertEquals(1, r.finalDevices.size());
        assertEquals("10.0.0.1", r.finalDevices.get(0).ip);
        assertTrue(r.finalDevices.get(0).openPorts.contains(22));   // OPEN recorded
        assertFalse(r.finalDevices.get(0).openPorts.contains(80));  // REFUSED is reachable, not an open port
    }

    @Test public void allTimeoutsFindsNothingButDoesNotMarkOffline() {
        Recorder r = new Recorder();
        TerminowNetworkScanner.scan(config(Arrays.asList("10.0.0.5"), Arrays.asList(22, 80)),
            (ip, port, t) -> ProbeResult.TIMEOUT, new AtomicBoolean(false), r);
        assertEquals(Status.COMPLETED, r.status);
        assertTrue(r.finalDevices.isEmpty());
    }

    @Test public void cancellationStopsAndIgnoresLateResults() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger probes = new AtomicInteger();
        TerminowNetworkScanner.Prober prober = (ip, port, t) -> {
            // Cancel during the very first probe; its result and all later ones must be ignored.
            if (probes.incrementAndGet() == 1) cancelled.set(true);
            return ProbeResult.OPEN;
        };
        Recorder r = new Recorder();
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= 50; i++) many.add("10.0.0." + i);
        TerminowNetworkScanner.scan(config(many, Arrays.asList(22)), prober, cancelled, r);
        assertEquals(Status.CANCELLED, r.status);
        assertTrue("no device reported after cancel", r.finalDevices.isEmpty());
    }

    @Test public void reportsProgress() {
        Recorder r = new Recorder();
        TerminowNetworkScanner.scan(config(Arrays.asList("10.0.0.1", "10.0.0.2"), Arrays.asList(22, 80)),
            (ip, port, t) -> ProbeResult.TIMEOUT, new AtomicBoolean(false), r);
        assertEquals(4, r.progressCalls); // 2 addresses x 2 ports
    }

    @Test public void pingOnlyHostIsDiscoveredEvenWithNoOpenPorts() {
        // Every TCP port times out, but the host answers the reachability sweep: it is still a device.
        TerminowNetworkScanner.Prober ports = (ip, port, t) -> ProbeResult.TIMEOUT;
        TerminowNetworkScanner.ReachProber reach = (ip, t) -> ip.equals("10.0.0.7");
        Recorder r = new Recorder();
        TerminowNetworkScanner.scan(config(Arrays.asList("10.0.0.7", "10.0.0.8"), Arrays.asList(22, 80)),
            ports, reach, new AtomicBoolean(false), r);
        assertEquals(Status.COMPLETED, r.status);
        assertEquals(1, r.finalDevices.size());
        TerminowDiscoveredDevice d = r.finalDevices.get(0);
        assertEquals("10.0.0.7", d.ip);
        assertTrue("marked reachable by ping", d.respondsToPing);
        assertTrue("no open port recorded", d.openPorts.isEmpty());
    }

    @Test public void pingAndOpenPortMergeIntoOneDevice() {
        TerminowNetworkScanner.Prober ports = (ip, port, t) ->
            ip.equals("10.0.0.7") && port == 22 ? ProbeResult.OPEN : ProbeResult.TIMEOUT;
        TerminowNetworkScanner.ReachProber reach = (ip, t) -> ip.equals("10.0.0.7");
        Recorder r = new Recorder();
        TerminowNetworkScanner.scan(config(Arrays.asList("10.0.0.7"), Arrays.asList(22, 80)),
            ports, reach, new AtomicBoolean(false), r);
        assertEquals(1, r.finalDevices.size());
        TerminowDiscoveredDevice d = r.finalDevices.get(0);
        assertTrue(d.respondsToPing);
        assertTrue(d.openPorts.contains(22));
        // total probes = 1 address x (2 ports + 1 ping) = 3
        assertEquals(3, r.progressCalls);
    }

    @Test public void failedPingIsInconclusiveAndFindsNothing() {
        Recorder r = new Recorder();
        TerminowNetworkScanner.scan(config(Arrays.asList("10.0.0.9"), Arrays.asList(22)),
            (ip, port, t) -> ProbeResult.TIMEOUT, (ip, t) -> false, new AtomicBoolean(false), r);
        assertEquals(Status.COMPLETED, r.status);
        assertTrue(r.finalDevices.isEmpty());
    }
}
