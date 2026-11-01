package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

/** Packet-capture command building: bounded limits, validated filters, and no shell injection. */
public class TerminowCaptureTest {

    private static TerminowCapture.Spec spec(String iface, String filter, int dur, int size, int packets) {
        return new TerminowCapture.Spec(iface, filter, dur, size, packets);
    }

    @Test
    public void durationAndSizeAreClampedToTheirBounds() {
        TerminowCapture.Spec tooBig = spec("eth0", "", 999999, 999999, 999999999);
        assertEquals(TerminowCapture.DURATION_MAX, tooBig.durationSec);
        assertEquals(TerminowCapture.SIZE_MAX_MB, tooBig.maxSizeMb);
        assertEquals(TerminowCapture.PACKETS_MAX, tooBig.packetLimit);

        TerminowCapture.Spec zeroes = spec("eth0", "", 0, 0, 0);
        assertEquals(TerminowCapture.DURATION_DEFAULT, zeroes.durationSec);
        assertEquals(TerminowCapture.SIZE_DEFAULT_MB, zeroes.maxSizeMb);
        assertEquals("0 means no explicit packet cap", 0, zeroes.packetLimit);
    }

    @Test
    public void captureFilterAcceptsBpfAndRejectsShellMetacharacters() {
        assertTrue(TerminowCapture.isValidCaptureFilter("tcp port 443 and host 10.0.0.1/24"));
        assertTrue(TerminowCapture.isValidCaptureFilter("udp or (icmp and not broadcast)"));
        assertTrue(TerminowCapture.isValidCaptureFilter("tcp[13] & 2 != 0"));   // valid BPF bitwise
        assertTrue(TerminowCapture.isValidCaptureFilter(""));
        assertFalse(TerminowCapture.isValidCaptureFilter("tcp; rm -rf /"));
        assertFalse(TerminowCapture.isValidCaptureFilter("tcp `id`"));
        assertFalse(TerminowCapture.isValidCaptureFilter("tcp $(id)"));
        assertFalse(TerminowCapture.isValidCaptureFilter("port 80 \"x\""));
        assertFalse(TerminowCapture.isValidCaptureFilter("a\\nb"));
    }

    @Test
    public void displayFilterValidation() {
        assertTrue(TerminowCapture.isValidDisplayFilter("http.request.method == \"GET\""));
        assertTrue(TerminowCapture.isValidDisplayFilter("ip.addr == 10.0.0.5 && tcp.port == 443"));
        assertFalse(TerminowCapture.isValidDisplayFilter("http && `id`"));
        assertFalse(TerminowCapture.isValidDisplayFilter("x; reboot"));
    }

    @Test
    public void capturePathValidation() {
        assertTrue(TerminowCapture.isValidCapturePath("/root/.terminow/captures/c1.pcap"));
        assertTrue(TerminowCapture.isValidCapturePath("/tmp/x.pcapng"));
        assertFalse(TerminowCapture.isValidCapturePath("relative.pcap"));
        assertFalse(TerminowCapture.isValidCapturePath("/root/../etc/x.pcap"));
        assertFalse(TerminowCapture.isValidCapturePath("/root/x.txt"));
    }

    @Test
    public void tcpdumpArgvIsBoundedInTimeAndSize() {
        List<String> a = TerminowCapture.tcpdumpArgv(spec("eth0", "tcp port 80", 30, 5, 1000), "/root/c.pcap");
        assertEquals("timeout", a.get(0));
        assertEquals("30", a.get(1));
        assertEquals("tcpdump", a.get(2));
        assertTrue(a.contains("-C"));
        assertEquals("5", a.get(a.indexOf("-C") + 1));
        assertEquals("1", a.get(a.indexOf("-W") + 1));          // one file then stop: total size bounded
        assertEquals("1000", a.get(a.indexOf("-c") + 1));
        assertEquals("eth0", a.get(a.indexOf("-i") + 1));
        assertEquals("/root/c.pcap", a.get(a.indexOf("-w") + 1));
        // the filter is split into separate trailing tokens
        assertTrue(a.contains("tcp") && a.contains("port") && a.contains("80"));
    }

    @Test
    public void remoteCommandQuotesValuesAndNeverInjects() {
        String cmd = TerminowCapture.remoteTcpdumpCommand(spec("eth0", "tcp port 443", 60, 10, 0), "/root/cap.pcap");
        assertTrue(cmd.startsWith("timeout 60 tcpdump -i 'eth0'"));
        assertTrue(cmd.contains("-w '/root/cap.pcap'"));
        assertTrue(cmd.contains("-C 10 -W 1"));
        assertTrue("filter is one single-quoted argument", cmd.endsWith("'tcp port 443'"));
        assertFalse(cmd.contains(";"));
    }

    @Test
    public void builderRejectsInvalidFilterAndPath() {
        try { TerminowCapture.tcpdumpArgv(spec("eth0", "tcp; evil", 10, 5, 0), "/root/c.pcap"); fail(); }
        catch (IllegalArgumentException expected) { /* ok */ }
        try { TerminowCapture.tcpdumpArgv(spec("eth0", "tcp", 10, 5, 0), "/root/c.exe"); fail(); }
        catch (IllegalArgumentException expected) { /* ok */ }
        try { TerminowCapture.remoteTcpdumpCommand(spec("eth0", "a ; b", 10, 5, 0), "/root/c.pcap"); fail(); }
        catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void tsharkReadSummaryAndExport() {
        List<String> read = TerminowCapture.tsharkReadArgv("/root/c.pcap", "ip.addr == 10.0.0.1", 500);
        assertEquals("tshark", read.get(0));
        assertEquals("/root/c.pcap", read.get(read.indexOf("-r") + 1));
        assertTrue(read.contains("-n"));
        assertEquals("ip.addr == 10.0.0.1", read.get(read.indexOf("-Y") + 1));
        assertEquals("500", read.get(read.indexOf("-c") + 1));

        List<String> summary = TerminowCapture.tsharkSummaryArgv("/root/c.pcap");
        assertTrue(summary.contains("-q"));
        assertEquals("io,phs", summary.get(summary.indexOf("-z") + 1));

        List<String> csv = TerminowCapture.tsharkExportArgv("/root/c.pcap", "fields", "");
        assertEquals("fields", csv.get(csv.indexOf("-T") + 1));
        assertTrue(csv.contains("ip.src"));
        assertTrue(csv.contains("separator=,"));
    }

    @Test
    public void exportFormatValidationAndBadDisplayFilter() {
        assertTrue(TerminowCapture.isValidFormat("json"));
        assertFalse(TerminowCapture.isValidFormat("exe"));
        try { TerminowCapture.tsharkExportArgv("/root/c.pcap", "exe", ""); fail(); }
        catch (IllegalArgumentException expected) { /* ok */ }
        try { TerminowCapture.tsharkReadArgv("/root/c.pcap", "bad; cmd", 0); fail(); }
        catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void shellQuotingEscapesSingleQuotes() {
        assertEquals("'a'", TerminowCapture.sq("a"));
        assertEquals("'a'\\''b'", TerminowCapture.sq("a'b"));
    }

    @Test
    public void capturePrivilegeIsParsedFromTheProbeMarker() {
        assertEquals(TerminowCapture.CapturePrivilege.OK,
            TerminowCapture.parseCapturePrivilege("TERMINOW_CAP_PRIV=ok\nTERMINOW_CAP_DETAIL=\n"));
        assertEquals(TerminowCapture.CapturePrivilege.DENIED,
            TerminowCapture.parseCapturePrivilege("TERMINOW_CAP_PRIV=denied\nTERMINOW_CAP_DETAIL=tcpdump: eth0: Operation not permitted\n"));
        assertEquals(TerminowCapture.CapturePrivilege.NO_DEVICE,
            TerminowCapture.parseCapturePrivilege("TERMINOW_CAP_PRIV=nodev\n"));
        assertEquals(TerminowCapture.CapturePrivilege.NO_TCPDUMP,
            TerminowCapture.parseCapturePrivilege("TERMINOW_CAP_PRIV=notcpdump\n"));
        assertEquals(TerminowCapture.CapturePrivilege.UNKNOWN,
            TerminowCapture.parseCapturePrivilege("TERMINOW_CAP_PRIV=weird\n"));
        // No marker at all (e.g. the command never ran) is UNKNOWN, never a false OK.
        assertEquals(TerminowCapture.CapturePrivilege.UNKNOWN,
            TerminowCapture.parseCapturePrivilege("bash: tcpdump: command not found\n"));
    }

    @Test
    public void probeDetailIsExtractedAndProbeScriptIsBounded() {
        assertEquals("tcpdump: eth0: Operation not permitted",
            TerminowCapture.parseProbeDetail("TERMINOW_CAP_PRIV=denied\nTERMINOW_CAP_DETAIL=tcpdump: eth0: Operation not permitted\n"));
        String script = TerminowCapture.probeScript("eth0");
        assertTrue("probe is bounded by timeout", script.contains("timeout 5 tcpdump"));
        assertTrue("probe writes nothing real", script.contains("-w /dev/null"));
        assertTrue("probe uses the validated interface", script.contains("-i eth0"));
        // An invalid interface can never be embedded: it falls back to 'any'.
        assertTrue(TerminowCapture.probeScript("a; rm -rf /").contains("-i any"));
    }
}
