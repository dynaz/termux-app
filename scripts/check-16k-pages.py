#!/usr/bin/env python3
"""Check that every native library in an APK or app bundle supports 16 KB memory pages.

Google Play rejects updates whose .so files have a LOAD segment aligned to less than 16 KB:
https://developer.android.com/guide/practices/page-sizes

Usage: check-16k-pages.py app-play-release.aab [more.apk ...]; exits 1 if a library is not aligned.
"""
import struct
import sys
import zipfile

PAGE = 16384


def load_alignments(data):
    """The p_align of each PT_LOAD segment of an ELF file."""
    if data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    if is64:
        phoff, = struct.unpack_from("<Q", data, 0x20)
        phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    else:
        phoff, = struct.unpack_from("<I", data, 0x1C)
        phentsize, phnum = struct.unpack_from("<HH", data, 0x2A)
    aligns = []
    for i in range(phnum):
        offset = phoff + i * phentsize
        if struct.unpack_from("<I", data, offset)[0] != 1:
            continue
        aligns.append(struct.unpack_from("<Q", data, offset + 48)[0] if is64 else struct.unpack_from("<I", data, offset + 28)[0])
    return aligns


def main(paths):
    bad = 0
    for path in paths:
        with zipfile.ZipFile(path) as archive:
            for name in sorted(archive.namelist()):
                if not name.endswith(".so"):
                    continue
                aligns = load_alignments(archive.read(name))
                if aligns is None:
                    continue
                ok = all(a >= PAGE for a in aligns)
                bad += 0 if ok else 1
                print(("ok   " if ok else "FAIL ") + name + "  LOAD alignment " + str(min(aligns) if aligns else "-"))
    if bad:
        print(str(bad) + " native libraries do not support 16 KB pages")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
