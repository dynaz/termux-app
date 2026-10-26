package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * One compute instance returned by a cloud provider (an AWS EC2 instance or a DigitalOcean Droplet),
 * parsed into a provider-neutral shape. The import UI shows these and lets the person pick the
 * address, username, port, group and Keychain key before any becomes a {@link TerminowImportConnection};
 * the provider id and region are kept so a repeat import can recognise what it imported before.
 *
 * <p>No credential or private key ever lives here: cloud key metadata is only a name, never key
 * material, and the login key is chosen by the person from the Keychain.
 */
public final class TerminowCloudInstance {

    public final String provider;

    public String id = "";
    public String name = "";
    public String region = "";
    public String state = "";
    public String publicIp = "";
    public String privateIp = "";
    public final List<String> tags = new ArrayList<>();

    public TerminowCloudInstance(@NonNull String provider) {
        this.provider = provider;
    }

    /** The address to use: the public IP when asked and present, otherwise the private IP (or vice-versa). */
    @NonNull
    public String address(boolean preferPublic) {
        if (preferPublic) return !publicIp.isEmpty() ? publicIp : privateIp;
        return !privateIp.isEmpty() ? privateIp : publicIp;
    }

    /** Whether this instance has any address at all to connect to. */
    public boolean hasAddress() {
        return !publicIp.isEmpty() || !privateIp.isEmpty();
    }
}
