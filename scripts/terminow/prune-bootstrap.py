#!/usr/bin/env python3
"""
Leave out of a bootstrap archive the packages that dpkg could never have installed, since a
mandatory dependency of them is neither in the bootstrap nor in the package repository.

The bootstrap script of termux-packages puts every package that the build produced into the
bootstrap, also subpackages like dpkg-perl, which depends on clang. The TermiNow repository
leaves out the packages that can not be installed (see check-repo-deps.py), so clang is not
there, and with dpkg-perl installed apt refuses to install anything until it is removed. A
bootstrap must only have packages that apt is happy with.

The same rules as check-repo-deps.py decide, over the packages of the bootstrap and of the
repository index of the same architecture. A package that is left out is removed with its
files (from var/lib/dpkg/info/<package>.list, except paths that another package also has), its
symlinks in SYMLINKS.txt, its var/lib/dpkg/info/<package>.* files and its entry in
var/lib/dpkg/status.

Usage:
  prune-bootstrap.py <bootstrap zip> <repository Packages index> <prefix> [--require <package>...]

The prefix is the one of the packages, like /data/data/com.terminow/files/usr, that the paths
in the .list files start with and that the paths in the archive are relative to. The archive is
rewritten in place. Exits with 1 if a package given with --require would be left out.
"""
import importlib.util
import os
import sys
import tempfile
import zipfile

_spec = importlib.util.spec_from_file_location("check_repo_deps", os.path.join(os.path.dirname(os.path.abspath(__file__)), "check-repo-deps.py"))
check_repo_deps = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_repo_deps)

STATUS = "var/lib/dpkg/status"
INFO = "var/lib/dpkg/info/"


def prune(archive_path, index_text, prefix, required=()):
    """Return the names that were left out. The archive is only rewritten if any were."""
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        status = archive.read(STATUS).decode("utf-8")
        bootstrap_entries = check_repo_deps.parse_index(status)
        bootstrap_names = {fields["Package"] for fields, _ in bootstrap_entries}
        repository_entries = check_repo_deps.parse_index(index_text)
        broken = check_repo_deps.find_uninstallable(bootstrap_entries + repository_entries)
        # A package of the bootstrap is only left out if no version of it, in the bootstrap or in
        # the repository, can be installed: find_uninstallable keys by name over both.
        left_out = sorted(name for name in broken if name in bootstrap_names)
        for name in required:
            if name in left_out:
                raise SystemExit(f"[!] The required package {name} can not be installed: {broken[name]}")
        if not left_out:
            return []

        # The files of each package, relative to the prefix
        owned = {}
        for fields, _ in bootstrap_entries:
            package = fields["Package"]
            list_name = INFO + package + ".list"
            paths = set()
            if list_name in names:
                for line in archive.read(list_name).decode("utf-8").splitlines():
                    # Directories may be listed with a trailing slash
                    line = line.rstrip("/")
                    if line == prefix or not line.startswith(prefix + "/"):
                        continue
                    paths.add(line[len(prefix) + 1:])
            owned[package] = paths
        kept_paths = set().union(*(paths for package, paths in owned.items() if package not in left_out))
        removed_paths = set().union(*(owned[package] for package in left_out)) - kept_paths

        removed_info = {name for name in names if name.startswith(INFO) and
                        any(os.path.basename(name).startswith(package + ".") for package in left_out)}
        new_status = "".join(paragraph.strip("\n") + "\n\n" for fields, paragraph in bootstrap_entries
                             if fields["Package"] not in left_out)

        directory = os.path.dirname(os.path.abspath(archive_path))
        fd, temporary = tempfile.mkstemp(dir=directory, suffix=".zip")
        os.close(fd)
        try:
            with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as out:
                for info in archive.infolist():
                    name = info.filename
                    # Files and directories that only left out packages have
                    if name in removed_info or name.rstrip("/") in removed_paths:
                        continue
                    if name == STATUS:
                        out.writestr(info, new_status.encode("utf-8"))
                    elif name == "SYMLINKS.txt":
                        lines = [line for line in archive.read(name).decode("utf-8").splitlines()
                                 if line.split("←", 1)[-1].removeprefix("./") not in removed_paths]
                        out.writestr(info, ("\n".join(lines) + "\n").encode("utf-8"))
                    else:
                        out.writestr(info, archive.read(name))
            os.replace(temporary, archive_path)
        except BaseException:
            os.unlink(temporary)
            raise
    for name in left_out:
        print(f"[!] Leaving {name} out of the bootstrap, it can not be installed: {broken[name]}", file=sys.stderr)
    return left_out


def main(argv):
    args = argv[1:]
    required = []
    if "--require" in args:
        index = args.index("--require")
        required = args[index + 1:]
        args = args[:index]
    if len(args) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    archive_path, index_path, prefix = args
    left_out = prune(archive_path, open(index_path, encoding="utf-8").read(), prefix.rstrip("/"), required)
    print(f"[*] {archive_path}: left out {len(left_out)} packages", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
