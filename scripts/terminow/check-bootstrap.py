#!/usr/bin/env python3
"""
Check that a bootstrap archive does not use the data directory of another app package, like
`/data/data/com.termux/`, which happens if a package is built with the default paths of its
own build system instead of the ones of termux-packages.

Header files are skipped since their paths are only defaults for `#ifndef` macros that the
packages are compiled with, and so are lines where the path is in a comment.

Also check that no maintainer script starts a program with an empty LD_PRELOAD, which unloads
termux-exec, without which nothing in the app data directory can be executed at the API level
that the app targets (see configure-termux-packages.sh).

Usage: check-bootstrap.py <bootstrap zip> <package name>
"""
import re
import sys
import zipfile

archive_path, package_name = sys.argv[1:3]
wrong_path = re.compile(rb"/data/data/(?!" + re.escape(package_name.encode()) + rb"/)[A-Za-z0-9_.]+/(files|cache)\b")

problems = []
unloading = []
with zipfile.ZipFile(archive_path) as archive:
    for info in archive.infolist():
        if info.is_dir() or info.filename.startswith("include/"):
            continue
        data = archive.read(info)
        if info.filename.startswith("var/lib/dpkg/info/") and re.search(rb"LD_PRELOAD=(''|\"\")\s", data):
            unloading.append(info.filename)
        for line in data.split(b"\n"):
            match = wrong_path.search(line)
            if not match:
                continue
            # A path in a comment of a script or config file is not used
            comment = line.find(b"#")
            if 0 <= comment < match.start() and b"\0" not in data[:1024]:
                continue
            problems.append((info.filename, line.strip()[:160].decode(errors="replace")))
            break

for filename, line in problems:
    print(f"[!] {filename}: {line}")
for filename in unloading:
    print(f"[!] {filename}: starts a program with an empty LD_PRELOAD, which unloads termux-exec")
if problems:
    print(f"[!] {len(problems)} files of the bootstrap use the data directory of another app package")
if problems or unloading:
    sys.exit(1)
print(f"[*] No file of the bootstrap uses the data directory of another app package than {package_name}")
