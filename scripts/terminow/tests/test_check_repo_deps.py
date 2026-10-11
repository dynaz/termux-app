#!/usr/bin/env python3
"""Regression tests for check-repo-deps.py. Run: python3 -I scripts/terminow/tests/test_check_repo_deps.py"""
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "check-repo-deps.py")
spec = importlib.util.spec_from_file_location("check_repo_deps", SCRIPT)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def index(*packages):
    """Make an index from (name, version, architecture, {field: value})."""
    paragraphs = []
    for name, version, arch, fields in packages:
        lines = [f"Package: {name}", f"Version: {version}", f"Architecture: {arch}"]
        lines += [f"{key}: {value}" for key, value in fields.items()]
        lines.append(f"Filename: pool/main/{name}_{version}_{arch}.deb")
        paragraphs.append("\n".join(lines))
    return "\n\n".join(paragraphs) + "\n"


def uninstallable(text):
    return set(check.find_uninstallable(check.parse_index(text)))


class CheckRepoDepsTest(unittest.TestCase):

    def test_missing_mandatory_dependency(self):
        # The defect that was published: dpkg-perl depends on clang, which is not built
        self.assertEqual(uninstallable(index(
            ("dpkg", "1.22.6-5", "aarch64", {}),
            ("dpkg-perl", "1.22.6-5", "all", {"Depends": "perl, clang, make, dpkg (= 1.22.6-5)"}),
            ("perl", "5.42.2", "aarch64", {}),
            ("make", "4.4", "aarch64", {}),
        )), {"dpkg-perl"})

    def test_missing_pre_depends(self):
        self.assertEqual(uninstallable(index(("a", "1", "aarch64", {"Pre-Depends": "missing"}))), {"a"})

    def test_cascading_exclusion(self):
        # dpkg-scanpackages depends on dpkg-perl, which is left out, and so on
        self.assertEqual(uninstallable(index(
            ("dpkg", "1.22.6-5", "aarch64", {}),
            ("dpkg-perl", "1.22.6-5", "all", {"Depends": "clang, dpkg (= 1.22.6-5)"}),
            ("dpkg-scanpackages", "1.22.6-5", "all", {"Depends": "dpkg-perl, dpkg (= 1.22.6-5)"}),
            ("uses-scanpackages", "1", "aarch64", {"Pre-Depends": "dpkg-scanpackages"}),
        )), {"dpkg-perl", "dpkg-scanpackages", "uses-scanpackages"})

    def test_version_constraints(self):
        self.assertEqual(uninstallable(index(
            ("proot", "5.1.107.96", "aarch64", {}),
            ("proot-distro", "5.9.0", "all", {"Depends": "proot (>= 5.1.107-71)"}),
            ("too-new", "1", "all", {"Depends": "proot (>= 6)"}),
            ("exact", "1", "all", {"Depends": "proot (= 5.1.107.96)"}),
            ("older", "1", "all", {"Depends": "proot (<< 5.1.107.96)"}),
        )), {"too-new", "older"})

    def test_alternatives(self):
        self.assertEqual(uninstallable(index(
            ("b", "1", "aarch64", {}),
            ("first-missing", "1", "all", {"Depends": "missing | b"}),
            ("all-missing", "1", "all", {"Depends": "missing | also-missing"}),
            ("alternative-left-out", "1", "all", {"Depends": "left-out | b"}),
            ("left-out", "1", "all", {"Depends": "missing"}),
        )), {"all-missing", "left-out"})

    def test_virtual_providers(self):
        self.assertEqual(uninstallable(index(
            ("mawk", "1", "aarch64", {"Provides": "awk"}),
            ("needs-awk", "1", "all", {"Depends": "awk"}),
            ("provider-left-out", "1", "aarch64", {"Provides": "editor", "Depends": "missing"}),
            ("needs-editor", "1", "all", {"Depends": "editor"}),
            ("versioned-provider", "1", "aarch64", {"Provides": "libfoo (= 2.0)"}),
            ("needs-libfoo-2", "1", "all", {"Depends": "libfoo (>= 2)"}),
            ("needs-libfoo-3", "1", "all", {"Depends": "libfoo (>= 3)"}),
            # An unversioned provides does not satisfy a versioned dependency
            ("needs-awk-versioned", "1", "all", {"Depends": "awk (>= 1)"}),
        )), {"provider-left-out", "needs-editor", "needs-libfoo-3", "needs-awk-versioned"})

    def test_optional_relations_are_not_checked(self):
        # python-pip recommends clang and pkg-config, which are not built, apt skips them
        self.assertEqual(uninstallable(index(
            ("python", "3.14.6-1", "aarch64", {"Recommends": "python-pip"}),
            ("python-pip", "26.2.1", "all", {"Depends": "python (>= 3.11.1-1)",
                                             "Recommends": "clang, make, pkg-config",
                                             "Suggests": "missing", "Enhances": "missing"}),
        )), set())

    def test_architecture_all_and_qualifiers(self):
        self.assertEqual(uninstallable(index(
            ("python", "3", "aarch64", {}),
            ("tool", "1", "all", {"Depends": "python:any"}),
        )), set())

    def test_proot_distro_chain_is_kept(self):
        text = index(
            ("libtalloc", "2", "aarch64", {}),
            ("libandroid-shmem", "1", "aarch64", {}),
            ("proot", "5.1.107.96", "aarch64", {"Depends": "libandroid-shmem, libtalloc"}),
            ("python", "3.14.6-1", "aarch64", {"Recommends": "python-pip"}),
            ("python-pip", "26.2.1", "all", {"Depends": "python (>= 3.11.1-1)", "Recommends": "clang, make, pkg-config"}),
            ("proot-distro", "5.9.0", "all", {"Depends": "proot (>= 5.1.107-71), python, python-pip"}),
            ("dpkg", "1.22.6-5", "aarch64", {}),
            ("dpkg-perl", "1.22.6-5", "all", {"Depends": "perl, clang, make, dpkg (= 1.22.6-5)"}),
            ("dpkg-scanpackages", "1.22.6-5", "all", {"Depends": "dpkg-perl, dpkg (= 1.22.6-5)"}),
        )
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "Packages.unchecked")
            output = os.path.join(directory, "Packages")
            open(source, "w").write(text)
            result = subprocess.run([sys.executable, "-I", SCRIPT, source, output, "--require", "proot", "proot-distro"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            kept = {fields["Package"] for fields, _ in check.parse_index(open(output).read())}
            self.assertEqual(kept, {"libtalloc", "libandroid-shmem", "proot", "python", "python-pip", "proot-distro", "dpkg"})

            # Fails if a required package is left out
            result = subprocess.run([sys.executable, "-I", SCRIPT, source, output, "--require", "dpkg-perl"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn("required package dpkg-perl can not be installed", result.stderr)


if __name__ == "__main__":
    unittest.main()
