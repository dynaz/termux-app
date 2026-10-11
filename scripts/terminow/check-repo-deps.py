#!/usr/bin/env python3
"""
Find the packages of an apt `Packages` index whose `Depends` or `Pre-Depends` can not be
installed from the index, also through other packages that can not be. apt can not install
them, and if it tries to, like for a recommended package, it may fail the whole installation.

Prints the file names of their .deb files, one per line, and the reasons on stderr.

Usage: check-repo-deps.py <Packages file>
"""
import re
import subprocess
import sys

packages = {}
fields = {}
for line in open(sys.argv[1], encoding="utf-8").read().split("\n") + [""]:
    if not line.strip():
        if "Package" in fields:
            packages[fields["Package"]] = fields
        fields = {}
    elif not line[0].isspace() and ":" in line:
        key, value = line.split(":", 1)
        fields[key] = value.strip()

provides = {}
for name, fields in packages.items():
    for provided in fields.get("Provides", "").split(","):
        provided = provided.strip().split(" ")[0]
        if provided:
            provides.setdefault(provided, []).append(name)

OPERATORS = {">=": "ge", "<=": "le", "=": "eq", ">>": "gt", "<<": "lt"}


def version_matches(version, operator, required):
    return subprocess.run(["dpkg", "--compare-versions", version, OPERATORS[operator], required]).returncode == 0


def can_install(alternative, broken):
    match = re.match(r"\s*([^\s(:]+)(?::\S+)?\s*(?:\(\s*(\S+)\s+([^)\s]+)\s*\))?", alternative)
    name, operator, required = match.groups()
    candidates = [name] if name in packages else []
    if not operator:
        candidates += provides.get(name, [])
    for candidate in candidates:
        if candidate in broken:
            continue
        if candidate == name and operator and not version_matches(packages[name]["Version"], operator, required):
            continue
        return True
    return False


broken = {}
changed = True
while changed:
    changed = False
    for name, fields in packages.items():
        if name in broken:
            continue
        for field in ("Pre-Depends", "Depends"):
            for dependency in fields.get(field, "").split(","):
                if dependency.strip() and not any(can_install(a, broken) for a in dependency.split("|")):
                    broken[name] = dependency.strip()
                    changed = True
                    break
            if name in broken:
                break

for name, dependency in sorted(broken.items()):
    print(f"[!] {name} can not be installed, it depends on: {dependency}", file=sys.stderr)
    print(packages[name]["Filename"])
