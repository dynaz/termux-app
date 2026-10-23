package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse the text of the quick connect field of the connection picker, like
 * `ssh user@hostname -p 2222`, into a host to connect to.
 *
 * The text is only parsed into the fields of a host, which are validated like in the host form,
 * and is never run as a command. Only these forms are supported:
 * - An optional `ssh` or `sftp` command name.
 * - A destination `[user@]hostname`, `[user@]hostname:port` or `ssh://[user@]hostname[:port]`.
 * - The options `-p <port>` (`-P <port>` like for sftp), `-l <user>` and `-i <key file>`.
 */
public class TerminowQuickConnect {

    public static class Result {
        /** The host to connect to, {@code null} if the text is not valid. */
        @Nullable
        public final TerminowStore.Host host;
        /** Why the text is not valid, {@code null} if it is. */
        @Nullable
        public final String error;

        private Result(@Nullable TerminowStore.Host host, @Nullable String error) {
            this.host = host;
            this.error = error;
        }

        /** Whether the host has all that is needed to connect, otherwise the host form is shown for the rest. */
        public boolean isComplete() {
            return host != null && !host.username.isEmpty();
        }
    }

    @NonNull
    private static Result error(@NonNull String message) {
        return new Result(null, message);
    }

    @NonNull
    public static Result parse(@Nullable String text) {
        if (text == null || text.trim().isEmpty())
            return error("Enter a host to connect to");
        if (text.indexOf('"') >= 0 || text.indexOf('\'') >= 0 || text.indexOf('`') >= 0 || text.indexOf('\\') >= 0)
            return error("Quotes and escapes are not supported");
        for (char c : new char[]{';', '|', '&', '$', '<', '>', '(', ')', '{', '}', '*', '?', '#', '!'})
            if (text.indexOf(c) >= 0)
                return error("The character '" + c + "' is not supported");

        List<String> tokens = new ArrayList<>();
        for (String token : text.trim().split("\\s+"))
            if (!token.isEmpty()) tokens.add(token);

        int index = 0;
        if (tokens.get(0).equals("ssh") || tokens.get(0).equals("sftp")) {
            index = 1;
            if (tokens.size() == 1)
                return error("Enter the host after " + tokens.get(0));
        }

        String destination = null;
        String username = null;
        String keyPath = null;
        Integer port = null;
        for (; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith("-")) {
                if (token.length() < 2)
                    return error("Option '-' is not supported");
                char option = token.charAt(1);
                if (option != 'p' && option != 'P' && option != 'l' && option != 'i')
                    return error("Option " + token + " is not supported, only -p, -l and -i are");
                String value;
                if (token.length() > 2) {
                    value = token.substring(2);
                } else if (index + 1 < tokens.size()) {
                    value = tokens.get(++index);
                } else {
                    return error("Option " + token + " needs a value");
                }
                if (option == 'p' || option == 'P') {
                    Integer parsed = parsePort(value);
                    if (parsed == null) return error("The port must be a number from 1 to 65535");
                    port = parsed;
                } else if (option == 'l') {
                    username = value;
                } else {
                    keyPath = value;
                }
            } else if (destination == null) {
                destination = token;
            } else {
                return error("Only one host is supported, commands after the host are not");
            }
        }
        if (destination == null)
            return error("Enter the host to connect to");

        if (destination.startsWith("ssh://") || destination.startsWith("sftp://")) {
            destination = destination.substring(destination.indexOf("://") + 3);
            if (destination.endsWith("/")) destination = destination.substring(0, destination.length() - 1);
        } else if (destination.contains("://")) {
            return error("Only ssh:// and sftp:// addresses are supported");
        }

        int at = destination.lastIndexOf('@');
        if (at >= 0) {
            if (username != null && !username.equals(destination.substring(0, at)))
                return error("The user is given twice");
            username = destination.substring(0, at);
            destination = destination.substring(at + 1);
            if (username.isEmpty()) return error("Enter the user before @");
        }

        // A port after the host name, but not in an IPv6 address, which has several colons
        int colon = destination.indexOf(':');
        if (colon >= 0 && colon == destination.lastIndexOf(':')) {
            Integer parsed = parsePort(destination.substring(colon + 1));
            if (parsed == null) return error("The port must be a number from 1 to 65535");
            if (port != null && !port.equals(parsed)) return error("The port is given twice");
            port = parsed;
            destination = destination.substring(0, colon);
        } else if (destination.startsWith("[") && destination.contains("]")) {
            // [IPv6 address]:port
            int end = destination.indexOf(']');
            String rest = destination.substring(end + 1);
            if (rest.startsWith(":")) {
                Integer parsed = parsePort(rest.substring(1));
                if (parsed == null) return error("The port must be a number from 1 to 65535");
                port = parsed;
            } else if (!rest.isEmpty()) {
                return error("Enter a valid host");
            }
            destination = destination.substring(1, end);
        }

        if (destination.isEmpty())
            return error("Enter the host to connect to");
        if (!TerminowStore.isValidAddress(destination))
            return error("Enter a valid host name or IP address");
        if (username == null) username = "";
        if (!TerminowStore.isValidUsername(username))
            return error("Enter a valid user name");
        if (keyPath == null) keyPath = "";
        if (!TerminowStore.isValidKeyPath(keyPath))
            return error("Enter a valid key file path");

        TerminowStore.Host host = new TerminowStore.Host();
        host.address = destination;
        host.username = username;
        host.port = port != null ? port : 22;
        host.keyPath = keyPath;
        return new Result(host, null);
    }

    @Nullable
    private static Integer parsePort(@NonNull String text) {
        if (text.isEmpty() || text.length() > 5) return null;
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return null;
        int port = Integer.parseInt(text);
        return TerminowStore.isValidPort(port) ? port : null;
    }

}
