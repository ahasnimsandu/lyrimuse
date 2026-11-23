#!/usr/bin/env python3
"""Add classes.dex to the aapt2 output with APP resources.arsc aligned.

Android 11+ (targetSdk 30+) requires resources.arsc to be STORED (uncompressed) and
4-byte aligned. Writing it as the first entry puts its data at 30 + len(name) = 44,
which is aligned, independent of the other entries.

Usage: package.py <base.apk> <classes.dex> <out.apk>
"""
import sys
import zipfile

SRC, DEX, OUT = sys.argv[1], sys.argv[2], sys.argv[3]


def add(zout, name, data, compress_type):
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = compress_type
    info.external_attr = 0o644 << 16
    zout.writestr(info, data)


with zipfile.ZipFile(SRC) as zin:
    entries = {i.filename: (zin.read(i.filename), i.compress_type) for i in zin.infolist()}

with zipfile.ZipFile(OUT, "w") as zout:
    arsc, _ = entries.pop("resources.arsc")
    add(zout, "resources.arsc", arsc, zipfile.ZIP_STORED)
    for name, (data, ctype) in entries.items():
        add(zout, name, data, ctype)
    with open(DEX, "rb") as f:
        add(zout, "classes.dex", f.read(), zipfile.ZIP_DEFLATED)

with zipfile.ZipFile(OUT) as check:
    a = check.getinfo("resources.arsc")
    print("resources.arsc stored=%s offset=%d aligned=%s" % (
        a.compress_type == zipfile.ZIP_STORED, a.header_offset, a.header_offset % 4 == 0))
