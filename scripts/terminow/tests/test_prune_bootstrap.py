#!/usr/bin/env python3
"""Regression tests for prune-bootstrap.py. Run: python3 -I scripts/terminow/tests/test_prune_bootstrap.py"""
import importlib.util
import os
import tempfile
import unittest
import zipfile

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "prune-bootstrap.py")
spec = importlib.util.spec_from_file_location("prune_bootstrap", SCRIPT)
prune = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prune)

PREFIX = "/data/data/com.terminow/files/usr"


def status(*packages):
    return "".join(f"Package: {name}\nStatus: install ok installed\nVersion: {version}\nArchitecture: aarch64\n" +
                   "".join(f"{key}: {value}\n" for key, value in fields.items()) + "\n"
                   for name, version, fields in packages)


def make_bootstrap(path):
    """A bootstrap like the one of 2026.10.11-r7: dpkg with dpkg-perl, which needs clang."""
    files = {
        "bin/dpkg": b"elf",
        "bin/dpkg-scanpackages": b"perl",
        "share/perl5/Dpkg/Arch.pm": b"perl",
        "share/perl5/Dpkg/Version.pm": b"perl",
        "share/doc/dpkg/README": b"doc",
        "bin/bash": b"elf",
        "var/lib/dpkg/status": status(
            ("dpkg", "1.22.6-5", {}),
            ("dpkg-perl", "1.22.6-5", {"Depends": "perl, clang, make, dpkg (= 1.22.6-5)"}),
            ("dpkg-scanpackages", "1.22.6-5", {"Depends": "dpkg-perl, dpkg (= 1.22.6-5)"}),
            ("libc++.so", "1", {}),
            ("bash", "5.2", {"Depends": "libc++.so"}),
        ).encode(),
        "var/lib/dpkg/info/dpkg.list": "\n".join([PREFIX, PREFIX + "/bin", PREFIX + "/bin/dpkg", PREFIX + "/share",
                                                   PREFIX + "/share/doc", PREFIX + "/share/doc/dpkg", PREFIX + "/share/doc/dpkg/README"]).encode(),
        # Directories with a trailing slash, as the bootstrap script of termux-packages lists them
        "var/lib/dpkg/info/dpkg-perl.list": "\n".join([PREFIX + "/", PREFIX + "/share/", PREFIX + "/share/perl5/", PREFIX + "/share/perl5/Dpkg/",
                                                        PREFIX + "/share/perl5/Dpkg/Arch.pm", PREFIX + "/share/perl5/Dpkg/Version.pm"]).encode(),
        "var/lib/dpkg/info/dpkg-perl.md5sums": b"x",
        "var/lib/dpkg/info/dpkg-scanpackages.list": "\n".join([PREFIX + "/bin", PREFIX + "/bin/dpkg-scanpackages", PREFIX + "/bin/dpkg-scan"]).encode(),
        "var/lib/dpkg/info/libc++.so.list": b"",
        "var/lib/dpkg/info/bash.list": "\n".join([PREFIX + "/bin", PREFIX + "/bin/bash"]).encode(),
        "SYMLINKS.txt": "dpkg-scanpackages←./bin/dpkg-scan\nbash←./bin/sh\n".encode(),
    }
    with zipfile.ZipFile(path, "w") as archive:
        for directory in ("bin/", "share/", "share/perl5/", "share/perl5/Dpkg/", "share/doc/", "share/doc/dpkg/"):
            archive.writestr(directory, b"")
        for name, data in files.items():
            archive.writestr(name, data)


class PruneBootstrapTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.dir.name, "bootstrap-aarch64.zip")
        make_bootstrap(self.path)
        # The repository has perl and make, but not clang, like the TermiNow repository
        self.index = ("Package: perl\nVersion: 5.40\nArchitecture: aarch64\nFilename: pool/perl.deb\n\n"
                      "Package: make\nVersion: 4.4\nArchitecture: aarch64\nFilename: pool/make.deb\n\n")

    def tearDown(self):
        self.dir.cleanup()

    def test_packages_that_can_never_be_installed_are_left_out(self):
        left_out = prune.prune(self.path, self.index, PREFIX)
        self.assertEqual(left_out, ["dpkg-perl", "dpkg-scanpackages"])
        with zipfile.ZipFile(self.path) as archive:
            names = set(archive.namelist())
            status_text = archive.read("var/lib/dpkg/status").decode()
            symlinks = archive.read("SYMLINKS.txt").decode()
        self.assertNotIn("Package: dpkg-perl\n", status_text)
        self.assertNotIn("Package: dpkg-scanpackages\n", status_text)
        self.assertIn("Package: dpkg\n", status_text)
        self.assertIn("Package: bash\n", status_text)
        # Their files, their info files and their symlinks are gone
        for gone in ("bin/dpkg-scanpackages", "share/perl5/Dpkg/Arch.pm", "share/perl5/Dpkg/", "share/perl5/",
                     "var/lib/dpkg/info/dpkg-perl.list", "var/lib/dpkg/info/dpkg-perl.md5sums",
                     "var/lib/dpkg/info/dpkg-scanpackages.list"):
            self.assertNotIn(gone, names)
        self.assertNotIn("./bin/dpkg-scan", symlinks)
        # Everything that other packages have is kept, also shared directories
        for kept in ("bin/dpkg", "bin/bash", "bin/", "share/", "share/doc/dpkg/README", "var/lib/dpkg/info/dpkg.list",
                     "var/lib/dpkg/info/libc++.so.list"):
            self.assertIn(kept, names)
        self.assertIn("bash←./bin/sh", symlinks)

    def test_an_installable_bootstrap_is_not_rewritten(self):
        with_clang = self.index + "Package: clang\nVersion: 21\nArchitecture: aarch64\nFilename: pool/clang.deb\n\n"
        before = os.path.getmtime(self.path)
        self.assertEqual(prune.prune(self.path, with_clang, PREFIX), [])
        self.assertEqual(before, os.path.getmtime(self.path))

    def test_a_required_package_is_never_left_out(self):
        with self.assertRaises(SystemExit):
            prune.prune(self.path, self.index, PREFIX, required=["dpkg-perl"])


if __name__ == "__main__":
    unittest.main()
