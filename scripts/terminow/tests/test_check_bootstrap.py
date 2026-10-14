#!/usr/bin/env python3
"""Regression tests for check-bootstrap.py. Run: python3 -I scripts/terminow/tests/test_check_bootstrap.py"""
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "check-bootstrap.py")
PREFIX = "/data/data/com.terminow/files/usr"


def check(files):
    with tempfile.TemporaryDirectory() as directory:
        path = os.path.join(directory, "bootstrap-aarch64.zip")
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in files.items():
                archive.writestr(name, data)
        result = subprocess.run([sys.executable, "-I", SCRIPT, path, "com.terminow"], capture_output=True, text=True)
        return result.returncode, result.stdout


class CheckBootstrapTest(unittest.TestCase):

    def test_a_clean_bootstrap_passes(self):
        code, out = check({
            "bin/hello": f"#!{PREFIX}/bin/sh\necho hi\n",
            "var/lib/dpkg/info/python.postinst": f'"{PREFIX}/bin/py3compile" -p "python" "{PREFIX}/lib/python3.14/"\n',
        })
        self.assertEqual(code, 0, out)

    def test_a_script_that_unloads_termux_exec_fails(self):
        # As termux-packages generates them for Python packages: py3compile can then not start bash
        code, out = check({
            "var/lib/dpkg/info/python.postinst": f"LD_PRELOAD='' \"{PREFIX}/bin/py3compile\" -p \"python\" \"{PREFIX}/lib/python3.14/\"\n",
        })
        self.assertEqual(code, 1, out)
        self.assertIn("var/lib/dpkg/info/python.postinst", out)
        self.assertIn("unloads termux-exec", out)

    def test_the_data_directory_of_another_app_fails(self):
        code, out = check({"bin/tool": "PREFIX=/data/data/com.termux/files/usr\n"})
        self.assertEqual(code, 1, out)


if __name__ == "__main__":
    unittest.main()
