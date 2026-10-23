package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The connector that ssh runs as its `ProxyCommand` for a host behind a SOCKS5 or HTTP proxy.
 * OpenSSH can not use such a proxy by itself and the bootstrap has no netcat, so this is a bash
 * script that opens the proxy with `/dev/tcp`, asks it for the host and then copies the bytes
 * both ways. Every argument is a validated address or port, see {@link TerminowStore.Host}.
 *
 * SOCKS5 without a login and HTTP CONNECT without a login are supported.
 */
public final class TerminowProxy {

    static final String SCRIPT =
        "# TermiNow proxy connector, the ProxyCommand of ssh for a host behind a proxy.\n" +
        "# Usage: proxy-connect socks5|http PROXY_HOST PROXY_PORT HOST PORT\n" +
        "set -u\n" +
        "type=$1 phost=$2 pport=$3 host=$4 port=$5\n" +
        "fail() { echo \"TermiNow proxy: $*\" >&2; exit 1; }\n" +
        "bytes() { dd bs=1 count=\"$1\" <&3 2>/dev/null | od -An -tu1; }\n" +
        "{ exec 3<>\"/dev/tcp/$phost/$pport\"; } 2>/dev/null || fail \"can not connect to the proxy $phost:$pport\"\n" +
        "case \"$type\" in\n" +
        "socks5)\n" +
        "  printf '\\005\\001\\000' >&3\n" +
        "  set -- $(bytes 2)\n" +
        "  [ \"${1:-}\" = 5 ] || fail \"$phost:$pport is not a SOCKS5 proxy\"\n" +
        "  [ \"${2:-}\" = 0 ] || fail \"the SOCKS5 proxy wants a login, which is not supported\"\n" +
        "  printf '\\005\\001\\000\\003' >&3\n" +
        "  printf \"\\\\$(printf '%03o' \"${#host}\")\" >&3\n" +
        "  printf '%s' \"$host\" >&3\n" +
        "  printf \"\\\\$(printf '%03o' $((port >> 8)))\\\\$(printf '%03o' $((port & 255)))\" >&3\n" +
        "  set -- $(bytes 4)\n" +
        "  [ \"${2:-}\" = 0 ] || fail \"the SOCKS5 proxy did not connect to $host:$port (reply ${2:-none})\"\n" +
        "  case \"${4:-}\" in\n" +
        "    1) bytes 6 >/dev/null;;\n" +
        "    4) bytes 18 >/dev/null;;\n" +
        "    3) set -- $(bytes 1); bytes $(( ${1:-0} + 2 )) >/dev/null;;\n" +
        "    *) fail \"the SOCKS5 proxy sent an invalid reply\";;\n" +
        "  esac\n" +
        "  ;;\n" +
        "http)\n" +
        "  printf 'CONNECT %s:%s HTTP/1.1\\r\\nHost: %s:%s\\r\\n\\r\\n' \"$host\" \"$port\" \"$host\" \"$port\" >&3\n" +
        "  IFS= read -r status <&3 || fail \"the HTTP proxy closed the connection\"\n" +
        "  status=${status%$'\\r'}\n" +
        "  case \"$status\" in \"HTTP/1.\"[01]\" 2\"[0-9][0-9]*) ;; *) fail \"the HTTP proxy answered: $status\";; esac\n" +
        "  while IFS= read -r line <&3; do line=${line%$'\\r'}; [ -z \"$line\" ] && break; done\n" +
        "  ;;\n" +
        "*) fail \"unknown proxy type $type\";;\n" +
        "esac\n" +
        "cat <&3 &\n" +
        "reader=$!\n" +
        "cat >&3\n" +
        "kill \"$reader\" 2>/dev/null\n" +
        "wait \"$reader\" 2>/dev/null\n" +
        "exit 0\n";

    private TerminowProxy() {}

    /** Where the connector is installed, under the prefix of the app. */
    @NonNull
    public static File getPath(@NonNull File prefixDir) {
        return new File(prefixDir, "libexec/terminow/proxy-connect");
    }

    /**
     * Write the connector if it is missing or differs, and return its path. It is run with
     * `bash`, so it does not need to be executable.
     */
    @NonNull
    public static File install(@NonNull File prefixDir) throws IOException {
        File file = getPath(prefixDir);
        byte[] content = SCRIPT.getBytes(StandardCharsets.UTF_8);
        if (file.isFile() && Arrays.equals(read(file), content)) return file;
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Failed to create " + dir);
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(content);
        }
        if (!tmp.renameTo(file)) throw new IOException("Failed to write " + file);
        return file;
    }

    @NonNull
    private static byte[] read(@NonNull File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 64 * 1024)];
            int total = 0, read;
            while (total < data.length && (read = in.read(data, total, data.length - total)) != -1) total += read;
            return Arrays.copyOf(data, total);
        }
    }

}
