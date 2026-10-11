#!/usr/bin/env python3
"""
Leave out the packages of an apt `Packages` index that apt can never install, since a
mandatory dependency of them is not in the index.

A package is left out if any of its `Pre-Depends` or `Depends` can not be satisfied by the
packages that are kept, so a package that depends on a package that is left out is left out
too. A dependency is satisfied by a package with that name whose version matches the version
constraint, or by a package that provides that name, with a matching version if the dependency
has a version constraint. Of a dependency with alternatives (`a | b`), one of them must be
satisfied. `Recommends`, `Suggests` and the other optional fields are not checked, apt installs
the packages of those that exist and skips the others.

The index is the one of a single architecture, with the packages of that architecture and of
the `all` architecture, as written by `apt-ftparchive --arch <arch> packages`.

Usage:
  check-repo-deps.py <Packages input> <Packages output> [--require <package>...]

The kept packages are written to the output file and the packages that are left out are listed
with the reason on stderr. Exits with 1 if a package given with --require is left out or is not
in the index, like a package that the bootstrap or a feature of the app needs.
"""
import functools
import re
import subprocess
import sys

MANDATORY_FIELDS = ("Pre-Depends", "Depends")

OPERATORS = {">=": "ge", "<=": "le", "=": "eq", ">>": "gt", "<<": "lt", ">": "gt", "<": "lt"}

DEPENDENCY_PATTERN = re.compile(r"^\s*([^\s(:]+)(?::\S+)?\s*(?:\(\s*(<<|<=|=|>=|>>|<|>)\s*([^)\s]+)\s*\))?\s*$")


def parse_index(text):
    """Parse a `Packages` index into a list of (fields, paragraph text) in the order of the index."""
    entries = []
    for paragraph in re.split(r"\n\s*\n", text.strip("\n")):
        if not paragraph.strip():
            continue
        fields = {}
        key = None
        for line in paragraph.split("\n"):
            if line[:1].isspace() and key:
                fields[key] += "\n" + line.strip()
            elif ":" in line:
                key, value = line.split(":", 1)
                fields[key] = value.strip()
        if "Package" in fields:
            entries.append((fields, paragraph))
    return entries


def parse_relation(text):
    """Parse a relation field into a list of dependencies, each a list of (name, operator, version) alternatives."""
    dependencies = []
    for dependency in text.split(","):
        if not dependency.strip():
            continue
        alternatives = []
        for alternative in dependency.split("|"):
            match = DEPENDENCY_PATTERN.match(alternative)
            if not match:
                raise ValueError(f"Invalid dependency '{alternative.strip()}'")
            alternatives.append(match.groups())
        dependencies.append(alternatives)
    return dependencies


@functools.lru_cache(maxsize=None)
def version_matches(version, operator, required):
    return subprocess.run(["dpkg", "--compare-versions", version, OPERATORS[operator], required]).returncode == 0


def find_uninstallable(entries):
    """Return {package name: the dependency that can not be satisfied} for the packages to leave out."""
    packages = {}
    for fields, _ in entries:
        # If a name is in the index more than once, apt can use any of its versions
        packages.setdefault(fields["Package"], []).append(fields)

    # name -> [(providing package, provided version or None)]
    providers = {}
    for name, versions in packages.items():
        for fields in versions:
            for provided, operator, version in (alternative for dependency in parse_relation(fields.get("Provides", ""))
                                                 for alternative in dependency):
                providers.setdefault(provided, []).append((name, version if operator == "=" else None))

    broken = {}

    def satisfiable(name, operator, required):
        for fields in packages.get(name, []):
            if name not in broken and (not operator or version_matches(fields["Version"], operator, required)):
                return True
        for provider, provided_version in providers.get(name, []):
            if provider in broken:
                continue
            # A versioned dependency is only satisfied by a versioned provides
            if not operator or (provided_version is not None and version_matches(provided_version, operator, required)):
                return True
        return False

    changed = True
    while changed:
        changed = False
        for name, versions in packages.items():
            if name in broken:
                continue
            # The package can be installed if any of its versions can
            reasons = []
            for fields in versions:
                reason = None
                for field in MANDATORY_FIELDS:
                    for dependency in parse_relation(fields.get(field, "")):
                        if not any(satisfiable(*alternative) for alternative in dependency):
                            reason = f"{field}: " + " | ".join(
                                n + (f" ({o} {v})" if o else "") for n, o, v in dependency)
                            break
                    if reason:
                        break
                if reason is None:
                    break
                reasons.append(reason)
            else:
                broken[name] = reasons[0]
                changed = True
    return broken


def main(argv):
    args = argv[1:]
    required = []
    if "--require" in args:
        index = args.index("--require")
        required = args[index + 1:]
        args = args[:index]
    if len(args) != 2:
        print(__doc__, file=sys.stderr)
        return 2

    entries = parse_index(open(args[0], encoding="utf-8").read())
    broken = find_uninstallable(entries)

    with open(args[1], "w", encoding="utf-8") as out:
        for fields, paragraph in entries:
            if fields["Package"] not in broken:
                out.write(paragraph.strip("\n") + "\n\n")

    for name, reason in sorted(broken.items()):
        print(f"[!] Leaving out {name}, it can not be installed: {reason}", file=sys.stderr)

    names = {fields["Package"] for fields, _ in entries}
    failed = False
    for name in required:
        if name not in names:
            print(f"[!] The required package {name} is not in the index", file=sys.stderr)
            failed = True
        elif name in broken:
            print(f"[!] The required package {name} can not be installed", file=sys.stderr)
            failed = True
    print(f"[*] Kept {len(names) - len(broken)} of {len(names)} packages", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
