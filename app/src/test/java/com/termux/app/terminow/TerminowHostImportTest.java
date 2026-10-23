package com.termux.app.terminow;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Hosts from the CSV of the Termius import and from ssh config files. */
public class TerminowHostImportTest {

    /** Keys that are in ~/.ssh of TermiNow in these tests. */
    private static final TerminowHostImport.KeyResolver KEYS = name ->
        Arrays.asList("id_ed25519", "rose.ppk").contains(name) ? "~/.ssh/" + name : null;

    private static TerminowStore.Host find(List<TerminowStore.Host> hosts, String address) {
        for (TerminowStore.Host host : hosts) if (host.address.equals(address)) return host;
        throw new AssertionError("no host " + address);
    }

    @Test
    public void theCsvOfTheTermiusTemplate() throws Exception {
        String csv = "\uFEFFGroups,Label,Tags,Hostname,Protocol,Port,Username,Password\r\n" +
            "Production,Web 1,\"web,prod\",web1.example.com,ssh,2222,deploy,\"p@ss, \"\"quoted\"\"\"\r\n" +
            ",Router,,192.168.1.1,telnet,,admin,\r\n" +
            "Lab,Mosh box,,10.0.0.5,mosh,,root,\r\n" +
            "Office,Desktop,,10.0.0.9,rdp,3389,me,\r\n" +
            ",No address,,,ssh,22,x,\r\n" +
            ",Bad port,,bad.example.com,ssh,abc,x,\r\n" +
            ",Evil,,evil.example.com,ssh,22,\"x; rm -rf ~\",\r\n" +
            ",Spaces,,\"evil host\",ssh,22,x,\r\n" +
            ",,,,,,,\r\n";
        TerminowHostImport.Result result = TerminowHostImport.read(csv, KEYS);
        assertTrue(result.csv);
        assertEquals(3, result.hosts.size());

        TerminowStore.Host web = find(result.hosts, "web1.example.com");
        assertEquals("Web 1", web.label);
        assertEquals("Production", web.group);
        assertEquals(Arrays.asList("web", "prod"), web.tags);
        assertEquals(2222, web.port);
        assertEquals("deploy", web.username);
        // Commas and quotes in a quoted cell
        assertEquals("p@ss, \"quoted\"", web.password);
        assertEquals("ssh -p 2222 -o StrictHostKeyChecking=accept-new deploy@web1.example.com", web.getCommand(0, 0));

        TerminowStore.Host router = find(result.hosts, "192.168.1.1");
        assertTrue(router.telnet);
        assertEquals(23, router.telnetPort);
        assertTrue(find(result.hosts, "10.0.0.5").mosh);

        // Every row that was left out says why
        assertEquals(5, result.skipped.size());
        String skipped = String.join("\n", result.skipped);
        assertTrue(skipped, skipped.contains("rdp is not SSH"));
        assertTrue(skipped, skipped.contains("no hostname"));
        assertTrue(skipped, skipped.contains("port abc"));
        assertTrue(skipped, skipped.contains("Evil") && skipped.contains("username"));
        assertTrue(skipped, skipped.contains("Spaces"));
        assertEquals(Arrays.asList("Production", "Lab"), Arrays.asList(result.groups.toArray()));
    }

    @Test
    public void otherColumnNamesAndKeys() throws Exception {
        // Like the template with Hostname/IP and SSH_KEY, in another order
        String csv = "Label,Hostname/IP,Username,SSH_KEY,Groups\n" +
            "With key,a.example.com,me,C:\\Users\\me\\.ssh\\id_ed25519,\n" +
            "PuTTY key,b.example.com,me,/document/primary:Download/rose.ppk,\n" +
            "Missing key,c.example.com,me,~/.ssh/id_rsa,\n";
        TerminowHostImport.Result result = TerminowHostImport.read(csv, KEYS);
        assertEquals(3, result.hosts.size());
        assertEquals("~/.ssh/id_ed25519", find(result.hosts, "a.example.com").keyPath);
        assertEquals("~/.ssh/rose.ppk", find(result.hosts, "b.example.com").keyPath);
        // A key that is not in TermiNow: the host without it, and the key is named to import
        assertEquals("", find(result.hosts, "c.example.com").keyPath);
        assertEquals(Arrays.asList("id_rsa"), Arrays.asList(result.missingKeys.toArray()));
    }

    @Test
    public void theColumnsOfTermiusExportWithAProxy() throws Exception {
        String csv = "Groups,Label,Tags,Hostname,Protocol,Port,Username,Password,IdentityFile,KeyLabel,KeyPassphrase,ProxyType,ProxyHost,ProxyPort\n" +
            "Home,NAS,,nas.lan,ssh,22,admin,,keys/id_ed25519,Laptop,,socks5,127.0.0.1,1080\n";
        TerminowHostImport.Result result = TerminowHostImport.read(csv, KEYS);
        TerminowStore.Host nas = result.hosts.get(0);
        assertEquals(TerminowStore.Host.PROXY_SOCKS5, nas.proxyType);
        assertEquals("127.0.0.1", nas.proxyHost);
        assertEquals(1080, nas.proxyPort);
        assertEquals("~/.ssh/id_ed25519", nas.keyPath);
        assertTrue(TerminowStore.isRunnable(nas));
    }

    @Test
    public void anSshConfigWithAJumpHost() throws Exception {
        String config = "Host bastion\n  HostName bastion.example.com\n  User jump\n  IdentityFile ~/.ssh/id_ed25519\n\n" +
            "Host app\n  HostName 10.1.2.3\n  User deploy\n  Port 2200\n  ProxyJump bastion\n  IdentityFile ~/.ssh/missing_key\n\n" +
            "Host *\n  ServerAliveInterval 30\n";
        TerminowHostImport.Result result = TerminowHostImport.read(config, KEYS);
        assertFalse(result.csv);
        assertEquals(2, result.hosts.size());
        TerminowStore.Host bastion = find(result.hosts, "bastion.example.com");
        TerminowStore.Host app = find(result.hosts, "10.1.2.3");
        assertEquals("~/.ssh/id_ed25519", bastion.keyPath);
        assertEquals(bastion.getKey(), app.jumpHost);
        assertEquals(2200, app.port);
        assertEquals("", app.keyPath);
        assertTrue(result.missingKeys.contains("missing_key"));
        // The command goes through the jump host
        String command = app.getCommand(bastion, "", 0, 0);
        assertTrue(command, command.contains("ProxyCommand=ssh -i ~/.ssh/id_ed25519 -o StrictHostKeyChecking=accept-new -W %h:%p jump@bastion.example.com"));
    }

    @Test
    public void csvCells() {
        List<List<String>> rows = TerminowHostImport.parseCsv("a,\"b\nc\",\"d\"\"e\"\r\n,,\nlast");
        assertEquals(Arrays.asList("a", "b\nc", "d\"e"), rows.get(0));
        assertEquals(Arrays.asList("", "", ""), rows.get(1));
        assertEquals(Arrays.asList("last"), rows.get(2));
    }


    @Test
    public void hostsRememberTheKeyTheyWaitFor() throws Exception {
        String csv = "Label,Hostname,Username,Key\n" +
            "web,web.example.com,root,Work key\n" +
            "db,db.example.com,root,Work key\n" +
            "known,k.example.com,root,id_ed25519\n" +
            "none,n.example.com,root,\n";
        TerminowHostImport.Result result = TerminowHostImport.read(csv, KEYS);
        assertEquals(4, result.hosts.size());
        assertEquals("Work key", result.waitingForKey.get(result.hosts.get(0)));
        assertEquals("Work key", result.waitingForKey.get(result.hosts.get(1)));
        assertEquals(2, result.waitingForKey.size());
        assertEquals(Arrays.asList("Work key"), new java.util.ArrayList<>(result.missingKeys));

        String config = "Host jump\n  HostName j.example.com\n  User me\n  IdentityFile C:\\Users\\me\\.ssh\\laptop_key\n";
        result = TerminowHostImport.read(config, KEYS);
        assertEquals("laptop_key", result.waitingForKey.get(result.hosts.get(0)));
    }

}
