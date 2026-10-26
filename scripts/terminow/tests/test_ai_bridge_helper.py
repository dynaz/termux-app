#!/usr/bin/env python3
"""Regression tests for the Local AI Bridge helper (terminal reconnect + bridge restart).

Run: python3 -I scripts/terminow/tests/test_ai_bridge_helper.py
"""
import importlib.util
import os
import socket
import tempfile
import threading
import time
import unittest

HELPER = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "..", "..", "app", "src", "main", "assets", "terminow-ai", "terminow-ai-bridge.py",
)


def load_helper():
    spec = importlib.util.spec_from_file_location("terminow_ai_bridge", HELPER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeBridge:
    """A loopback listener that does the AUTH <token>\\n -> OK/ERR handshake, like the app's server."""

    def __init__(self, token):
        self.token = token
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(4)
        self.port = self.sock.getsockname()[1]
        self._thread = threading.Thread(target=self._serve, daemon=True)
        self._thread.start()

    def _serve(self):
        while True:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            line = b""
            while not line.endswith(b"\n"):
                chunk = conn.recv(64)
                if not chunk:
                    break
                line += chunk
            ok = line.strip() == b"AUTH " + self.token.encode()
            try:
                conn.sendall(b"OK\n" if ok else b"ERR unauthorized\n")
            except OSError:
                pass
            # Keep the peer open so connect_with_backoff returns a live socket.
            if not ok:
                conn.close()

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


class ReadPortTest(unittest.TestCase):

    def setUp(self):
        self.helper = load_helper()
        for key in ("TERMINOW_AI_PORT", "TERMINOW_AI_PORT_FILE"):
            os.environ.pop(key, None)

    def test_reads_the_live_port_file_fresh_each_call(self):
        with tempfile.NamedTemporaryFile("w", delete=False) as f:
            path = f.name
            f.write("41465\n")
        try:
            os.environ["TERMINOW_AI_PORT_FILE"] = path
            self.assertEqual(41465, self.helper.read_port())
            # A terminal reconnect rebinds on a new port; the next read must pick it up.
            with open(path, "w") as f:
                f.write("52000\n")
            self.assertEqual(52000, self.helper.read_port())
        finally:
            os.unlink(path)

    def test_falls_back_to_env_when_file_missing_or_bad(self):
        os.environ["TERMINOW_AI_PORT_FILE"] = "/no/such/port/file"
        os.environ["TERMINOW_AI_PORT"] = "40000"
        self.assertEqual(40000, self.helper.read_port())

    def test_zero_when_nothing_configured(self):
        self.assertEqual(0, self.helper.read_port())


class BackoffTest(unittest.TestCase):

    def setUp(self):
        self.helper = load_helper()
        self.helper.CONNECT_DEADLINE_S = 10.0
        for key in ("TERMINOW_AI_SOCK", "TERMINOW_AI_HOST", "TERMINOW_AI_TOKEN_FILE"):
            os.environ.pop(key, None)
        os.environ["TERMINOW_AI_TRANSPORT"] = "tcp"
        os.environ["TERMINOW_AI_HOST"] = "127.0.0.1"
        self._bridges = []
        self._portfile = tempfile.NamedTemporaryFile("w", delete=False).name

    def tearDown(self):
        for b in self._bridges:
            b.close()
        os.unlink(self._portfile)

    def _write_port(self, port):
        with open(self._portfile, "w") as f:
            f.write(str(port))

    def test_reconnects_to_a_new_port_after_a_restart(self):
        token = "tok-regression-not-a-real-secret"
        os.environ["TERMINOW_AI_TOKEN"] = token
        os.environ["TERMINOW_AI_PORT_FILE"] = self._portfile
        # Start pointed at a dead port (bridge down / stale port after reconnect).
        dead = socket.socket()
        dead.bind(("127.0.0.1", 0))
        dead_port = dead.getsockname()[1]
        dead.close()
        self._write_port(dead_port)

        # The bridge comes back on a NEW port shortly after, writing its live port file.
        def restart():
            time.sleep(1.0)
            bridge = FakeBridge(token)
            self._bridges.append(bridge)
            self._write_port(bridge.port)

        threading.Thread(target=restart, daemon=True).start()
        sock, error = self.helper.connect_with_backoff()
        self.assertIsNone(error)
        self.assertIsNotNone(sock)
        sock.close()

    def test_rejects_a_bad_token_without_leaking_it(self):
        self.helper.CONNECT_DEADLINE_S = 2.0
        bridge = FakeBridge("the-right-token")
        self._bridges.append(bridge)
        secret = "wrong-secret-token-value"
        os.environ["TERMINOW_AI_TOKEN"] = secret
        os.environ["TERMINOW_AI_PORT_FILE"] = self._portfile
        self._write_port(bridge.port)
        sock, error = self.helper.connect_with_backoff()
        self.assertIsNone(sock)
        self.assertIsNotNone(error)
        self.assertNotIn(secret, error)  # the error is actionable, never the token

    def test_gives_an_actionable_error_when_nothing_listens(self):
        self.helper.CONNECT_DEADLINE_S = 2.0
        os.environ["TERMINOW_AI_TOKEN"] = "tok-x"
        dead = socket.socket()
        dead.bind(("127.0.0.1", 0))
        dead_port = dead.getsockname()[1]
        dead.close()
        os.environ["TERMINOW_AI_PORT_FILE"] = self._portfile
        self._write_port(dead_port)
        start = time.monotonic()
        sock, error = self.helper.connect_with_backoff()
        self.assertIsNone(sock)
        self.assertIsNotNone(error)
        # It kept trying (bounded backoff), it did not give up on the first refusal.
        self.assertGreaterEqual(time.monotonic() - start, 1.0)


if __name__ == "__main__":
    unittest.main()
