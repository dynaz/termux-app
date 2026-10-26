package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The actions the AI Agent may take, as plain functions apart from the model and the network: add a
 * host and find or extract one from an SSH command. Hosts are created through the existing
 * {@link TerminowStore.Host} model and validation — there is no separate host store — and this first
 * version does nothing else: no shell execution, no deleting hosts, no exporting credentials.
 *
 * <p>Pure logic, so the validation, de-duplication and parsing can be tested without the model, the
 * network or Android; the orchestrator supplies the real host store through {@link Hosts}.
 */
public final class TerminowAgentTools {

    /**
     * What a tool call did (or, for {@link #prepareHost}, what it would do): READY means a valid new
     * host has been built but not yet saved — the person confirms first; ADDED means it was saved.
     */
    public enum Status {READY, ADDED, DUPLICATE, NEEDS_INFO, INVALID}

    public static final class Result {
        public final Status status;
        public final String message;
        @Nullable public final TerminowStore.Host host;
        /** For NEEDS_INFO: the one field to ask the person for. */
        public final String missingField;

        private Result(Status status, String message, @Nullable TerminowStore.Host host, String missingField) {
            this.status = status;
            this.message = message;
            this.host = host;
            this.missingField = missingField;
        }

        static Result ready(@NonNull TerminowStore.Host host) {
            return new Result(Status.READY, "Ready to add host " + host.getTitle(), host, "");
        }

        static Result added(@NonNull TerminowStore.Host host) {
            return new Result(Status.ADDED, "Added host " + host.getTitle(), host, "");
        }

        static Result duplicate(@NonNull TerminowStore.Host host) {
            return new Result(Status.DUPLICATE, "A host with the same address, port and user already exists: " + host.getTitle(), host, "");
        }

        static Result needs(@NonNull String field, @NonNull String message) {
            return new Result(Status.NEEDS_INFO, message, null, field);
        }

        static Result invalid(@NonNull String message) {
            return new Result(Status.INVALID, message, null, "");
        }
    }

    /** The host store the agent reads and writes — the real one is {@link TerminowStore}. */
    public interface Hosts {
        @NonNull List<TerminowStore.Host> all();

        void add(@NonNull TerminowStore.Host host);

        /** Persist a change to a host already in the store (same uuid). */
        void update(@NonNull TerminowStore.Host host);
    }

    private TerminowAgentTools() {}

    /**
     * Validate and build a new SSH host, but do not save it. Only the address is required; the port
     * defaults to 22 and the username may be empty (the person is asked at connect time, never forced
     * to root). A missing address asks for just that; an invalid address or port is reported; a host
     * that already exists is reported as a duplicate. On READY the built (unsaved) host is returned so
     * the caller can confirm with the person before saving it with {@link Hosts#add}.
     */
    @NonNull
    public static Result prepareHost(@Nullable String label, @Nullable String address, @Nullable Integer port,
                                     @Nullable String username, @NonNull Hosts hosts) {
        String addr = address == null ? "" : address.trim();
        if (addr.isEmpty()) return Result.needs("address", "What is the address or IP of the host?");
        if (!TerminowStore.isValidAddress(addr)) return Result.invalid("That is not a valid address: " + addr);

        int actualPort = port != null ? port : 22;
        if (!TerminowStore.isValidPort(actualPort)) return Result.invalid("The port must be between 1 and 65535, not " + actualPort + ".");

        String user = username == null ? "" : username.trim();
        if (!user.isEmpty() && !TerminowStore.isValidUsername(user)) return Result.invalid("That is not a valid username: " + user);

        for (TerminowStore.Host existing : hosts.all())
            if (sameHost(existing, addr, actualPort, user)) return Result.duplicate(existing);

        TerminowStore.Host host = new TerminowStore.Host();
        host.uuid = TerminowStore.newHostId();
        host.label = clip((label == null || label.trim().isEmpty()) ? addr : label.replaceAll("\\p{Cntrl}", " ").trim(), 100);
        host.address = addr;
        host.port = actualPort;
        host.username = user;
        host.protocol = TerminowStore.Host.PROTOCOL_SSH;
        return Result.ready(host);
    }

    /**
     * Validate, build and save a new SSH host in one step (no confirmation). Kept for callers that do
     * not confirm; the AI Agent uses {@link #prepareHost} and saves only after the person confirms.
     */
    @NonNull
    public static Result addHost(@Nullable String label, @Nullable String address, @Nullable Integer port,
                                 @Nullable String username, @NonNull Hosts hosts) {
        Result prepared = prepareHost(label, address, port, username, hosts);
        if (prepared.status != Status.READY || prepared.host == null) return prepared;
        hosts.add(prepared.host);
        return Result.added(prepared.host);
    }

    /**
     * Validate a change to an existing host (only the given fields change), without saving. Returns
     * READY with a copy carrying the new values (same uuid) so the caller can confirm before saving,
     * INVALID for a bad value, or DUPLICATE if the change would collide with a different saved host.
     */
    @NonNull
    public static Result prepareUpdate(@NonNull TerminowStore.Host existing, @Nullable String label, @Nullable String address,
                                       @Nullable Integer port, @Nullable String username, @NonNull Hosts hosts) {
        String addr = address == null || address.trim().isEmpty() ? existing.address : address.trim();
        if (!TerminowStore.isValidAddress(addr)) return Result.invalid("That is not a valid address: " + addr);
        int actualPort = port != null ? port : existing.port;
        if (!TerminowStore.isValidPort(actualPort)) return Result.invalid("The port must be between 1 and 65535, not " + actualPort + ".");
        String user = username == null ? existing.username : username.trim();
        if (!user.isEmpty() && !TerminowStore.isValidUsername(user)) return Result.invalid("That is not a valid username: " + user);
        String name = (label == null || label.trim().isEmpty()) ? existing.label : label.replaceAll("\\p{Cntrl}", " ").trim();

        for (TerminowStore.Host other : hosts.all())
            if (!other.uuid.equals(existing.uuid) && sameHost(other, addr, actualPort, user)) return Result.duplicate(other);

        TerminowStore.Host copy = new TerminowStore.Host();
        copy.uuid = existing.uuid;
        copy.label = clip(name, 100);
        copy.address = addr;
        copy.port = actualPort;
        copy.username = user;
        copy.protocol = existing.protocol;
        copy.group = existing.group;
        return Result.ready(copy);
    }

    /** Whether a saved host is the same connection as the given address/port/user. */
    public static boolean sameHost(@NonNull TerminowStore.Host host, @NonNull String address, int port, @NonNull String user) {
        return host.address.equalsIgnoreCase(address.trim()) && host.port == port && host.username.equalsIgnoreCase(user.trim());
    }

    private static final Pattern SSH_COMMAND = Pattern.compile("\\bssh\\s+([^\\n|&;<>]+)");

    /**
     * Extract an SSH target (address, port, username) from an {@code ssh} command somewhere in the
     * text, or null if there is none. Handles {@code ssh [-p PORT] [-l USER] [user@]host} in any
     * order, and {@code [ipv6]} bracket form. Used when the person pastes a script and asks to add it.
     */
    @Nullable
    public static TerminowStore.Host parseSshCommand(@NonNull String text) {
        Matcher matcher = SSH_COMMAND.matcher(text);
        while (matcher.find()) {
            String[] tokens = matcher.group(1).trim().split("\\s+");
            String address = "";
            String user = "";
            int port = 22;
            boolean haveHost = false;
            // A real ssh target is signalled by a user@, a -p/-l option, or a dotted/colon address;
            // this keeps prose like "the ssh command" from being read as a host named "command".
            boolean explicit = false;
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i];
                if (token.equals("-p") && i + 1 < tokens.length) {
                    port = parsePort(tokens[++i], port);
                    explicit = true;
                } else if (token.startsWith("-p") && token.length() > 2) {
                    port = parsePort(token.substring(2), port);
                    explicit = true;
                } else if (token.equals("-l") && i + 1 < tokens.length) {
                    user = tokens[++i];
                    explicit = true;
                } else if (token.startsWith("-")) {
                    // An unknown option; ignore it rather than mistaking it for the host.
                    continue;
                } else if (!haveHost) {
                    int at = token.lastIndexOf('@');
                    if (at >= 0) {
                        user = token.substring(0, at);
                        address = token.substring(at + 1);
                        explicit = true;
                    } else {
                        address = token;
                    }
                    // [2001:db8::1] bracket form for IPv6
                    if (address.startsWith("[") && address.endsWith("]")) address = address.substring(1, address.length() - 1);
                    haveHost = true;
                }
            }
            boolean plausible = explicit || address.contains(".") || address.contains(":");
            if (haveHost && plausible && TerminowStore.isValidAddress(address) && TerminowStore.isValidPort(port)) {
                TerminowStore.Host host = new TerminowStore.Host();
                host.address = address;
                host.port = port;
                host.username = user;
                host.protocol = TerminowStore.Host.PROTOCOL_SSH;
                return host;
            }
        }
        return null;
    }

    private static int parsePort(@NonNull String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @NonNull
    private static String clip(@NonNull String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
