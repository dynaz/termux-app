package com.termux.app.terminow;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.terminow.TerminowStore.Host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The device templates of the Add Environment screen's Remote Devices and Virtual Labs tabs. A
 * template only sets metadata — the device type, its name, help text and default SSH port — and opens
 * the existing Add Host form; it never creates a new SSH engine. The device type drives
 * {@link TerminowDeviceProfile}, so a MikroTik, Cisco or ESXi is never sent a Linux bootstrap.
 *
 * <p>Prefilling fills the address later (the user types it), sets the default SSH port (22, editable),
 * tags the host, and never sets a username — root is not assumed — or any credential.
 */
public final class TerminowRemoteDevices {

    private TerminowRemoteDevices() {}

    public static final class Template {
        public final int nameRes;
        public final int helpRes;
        @NonNull public final TerminowDeviceProfile.Type type;
        public final int defaultPort;

        Template(int nameRes, int helpRes, @NonNull TerminowDeviceProfile.Type type, int defaultPort) {
            this.nameRes = nameRes;
            this.helpRes = helpRes;
            this.type = type;
            this.defaultPort = defaultPort;
        }
    }

    /** Remote devices reached over SSH. */
    @NonNull
    public static List<Template> remoteDevices() {
        List<Template> list = new ArrayList<>();
        list.add(new Template(R.string.terminow_device_generic, R.string.terminow_device_generic_help, TerminowDeviceProfile.Type.GENERIC_LINUX, 22));
        list.add(new Template(R.string.terminow_device_synology, R.string.terminow_device_synology_help, TerminowDeviceProfile.Type.SYNOLOGY, 22));
        list.add(new Template(R.string.terminow_device_mikrotik, R.string.terminow_device_mikrotik_help, TerminowDeviceProfile.Type.MIKROTIK, 22));
        list.add(new Template(R.string.terminow_device_cisco, R.string.terminow_device_cisco_help, TerminowDeviceProfile.Type.CISCO, 22));
        list.add(new Template(R.string.terminow_device_esxi, R.string.terminow_device_esxi_help, TerminowDeviceProfile.Type.ESXI, 22));
        list.add(new Template(R.string.terminow_device_other, R.string.terminow_device_other_help, TerminowDeviceProfile.Type.OTHER_SSH, 22));
        return Collections.unmodifiableList(list);
    }

    /** Virtual labs: VMs/labs running on an external server, reached over SSH (never a local hypervisor). */
    @NonNull
    public static List<Template> virtualLabs() {
        List<Template> list = new ArrayList<>();
        list.add(new Template(R.string.terminow_lab_proxmox, R.string.terminow_lab_proxmox_help, TerminowDeviceProfile.Type.PROXMOX_VE, 22));
        list.add(new Template(R.string.terminow_lab_docker, R.string.terminow_lab_docker_help, TerminowDeviceProfile.Type.DOCKER, 22));
        list.add(new Template(R.string.terminow_lab_linux_vm, R.string.terminow_device_generic_help, TerminowDeviceProfile.Type.GENERIC_LINUX, 22));
        list.add(new Template(R.string.terminow_lab_mikrotik_chr, R.string.terminow_device_mikrotik_help, TerminowDeviceProfile.Type.MIKROTIK, 22));
        list.add(new Template(R.string.terminow_lab_esxi, R.string.terminow_device_esxi_help, TerminowDeviceProfile.Type.ESXI, 22));
        return Collections.unmodifiableList(list);
    }

    /**
     * A new Host prefilled from a template: its device type and default SSH port, tagged with the
     * device type. No username (root is not assumed) and no credential are set.
     */
    @NonNull
    public static Host prefill(@NonNull Template template) {
        Host host = new Host();
        host.protocol = Host.PROTOCOL_SSH;
        host.deviceType = TerminowDeviceProfile.key(template.type);
        host.port = template.defaultPort;
        String tag = tagFor(template.type);
        if (!tag.isEmpty() && !host.tags.contains(tag)) host.tags.add(tag);
        return host;
    }

    /** A short tag for the device type, e.g. NAS, Router, Server. */
    @NonNull
    public static String tagFor(@NonNull TerminowDeviceProfile.Type type) {
        switch (type) {
            case SYNOLOGY: return "NAS";
            case MIKROTIK: case CISCO: return "Router";
            case ESXI: return "Hypervisor";
            case PROXMOX_VE: return "Virtualization";
            case DOCKER: return "Containers";
            case GENERIC_LINUX: return "Server";
            default: return "";
        }
    }
}
