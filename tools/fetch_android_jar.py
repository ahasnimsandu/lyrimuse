#!/usr/bin/env python3
"""Extract android.jar from a remote SDK platform zip with resumable curl range requests.

Only the android.jar member is downloaded (24 MB instead of the 63 MB zip), the central
directory is cached, and the payload resumes after a dropped connection.
Usage: fetch_android_jar.py <zip-url> <out.jar> [workdir]
"""
import json
import os
import struct
import subprocess
import sys
import time
import zlib

URL = sys.argv[1]
OUT = sys.argv[2]
WORK = sys.argv[3] if len(sys.argv) > 3 else "/tmp/lyrtools"
os.makedirs(WORK, exist_ok=True)
PAYLOAD = os.path.join(WORK, "android_jar.payload")
CD_CACHE = os.path.join(WORK, "platform_cd.bin")
META = os.path.join(WORK, "android_jar_meta.json")


def curl(start, end, tries=6):
    last = ""
    for attempt in range(tries):
        p = subprocess.run(
            ["curl", "-sL", "--max-time", "180", "--retry", "2", "--retry-all-errors",
             "-r", "%d-%d" % (start, end), URL],
            capture_output=True,
        )
        if p.returncode == 0 and p.stdout:
            return p.stdout
        last = p.stderr.decode("utf-8", "replace")[:200]
        print("retry %d for %d-%d (%s)" % (attempt + 1, start, end, last or "empty"), flush=True)
        time.sleep(2)
    raise SystemExit("curl failed for %d-%d: %s" % (start, end, last))


def head_size():
    p = subprocess.run(["curl", "-sIL", "--max-time", "60", URL], capture_output=True)
    for line in p.stdout.decode("utf-8", "replace").splitlines():
        low = line.lower()
        if low.startswith("content-range:"):
            return int(line.split("/")[-1].strip())
        if low.startswith("content-length:"):
            return int(line.split(":")[1].strip())
    raise SystemExit("cannot determine size")


def main():
    if os.path.exists(META):
        with open(META) as f:
            meta = json.load(f)
    else:
        size = head_size()
        print("zip size", size, flush=True)
        tail = curl(max(0, size - 66000), size - 1)
        idx = tail.rfind(b"PK\x05\x06")
        if idx < 0:
            raise SystemExit("EOCD not found")
        cd_size, cd_off = struct.unpack("<II", tail[idx + 12:idx + 20])
        if os.path.exists(CD_CACHE) and os.path.getsize(CD_CACHE) == cd_size:
            with open(CD_CACHE, "rb") as f:
                cd = f.read()
        else:
            print("central dir", cd_off, cd_size, flush=True)
            cd = curl(cd_off, cd_off + cd_size - 1)
            with open(CD_CACHE, "wb") as f:
                f.write(cd)
        pos = 0
        found = None
        while pos + 46 <= len(cd) and cd[pos:pos + 4] == b"PK\x01\x02":
            method, = struct.unpack("<H", cd[pos + 10:pos + 12])
            csize, usize = struct.unpack("<II", cd[pos + 20:pos + 28])
            nlen, elen, clen = struct.unpack("<HHH", cd[pos + 28:pos + 34])
            hoff, = struct.unpack("<I", cd[pos + 42:pos + 46])
            name = cd[pos + 46:pos + 46 + nlen].decode("utf-8", "replace")
            if name == "android.jar" or name.endswith("/android.jar"):
                found = (method, csize, usize, hoff)
                break
            pos += 46 + nlen + elen + clen
        if not found:
            raise SystemExit("android.jar not found in zip")
        method, csize, usize, hoff = found
        head = curl(hoff, hoff + 29 + 1024)
        nlen, elen = struct.unpack("<HH", head[26:30])
        data_start = hoff + 30 + nlen + elen
        meta = {"method": method, "csize": csize, "usize": usize, "data_start": data_start,
                "prefix": head[30 + nlen + elen:].decode("latin-1")}
        with open(META, "w") as f:
            json.dump(meta, f)
        print("android.jar compressed=%d uncompressed=%d" % (csize, usize), flush=True)

    csize, usize, data_start = meta["csize"], meta["usize"], meta["data_start"]
    prefix = meta["prefix"].encode("latin-1")

    if not os.path.exists(PAYLOAD):
        with open(PAYLOAD, "wb") as f:
            f.write(prefix)
    done = os.path.getsize(PAYLOAD)
    chunk = 512 * 1024
    t0 = time.time()
    start_bytes = done
    while done < csize:
        start = data_start + done
        end = min(data_start + csize - 1, start + chunk - 1)
        data = curl(start, end)
        with open(PAYLOAD, "ab") as f:
            f.write(data[: csize - done])
        done = os.path.getsize(PAYLOAD)
        print("%5.1f%% %9d/%d  %.0f KB/s" % (
            100.0 * done / csize, done, csize,
            (done - start_bytes) / 1024.0 / max(1e-9, time.time() - t0)), flush=True)

    with open(PAYLOAD, "rb") as f:
        payload = f.read()
    raw = zlib.decompress(payload, -15) if meta["method"] == 8 else payload
    if len(raw) != usize:
        os.remove(PAYLOAD)
        raise SystemExit("size mismatch %d != %d (payload reset)" % (len(raw), usize))
    with open(OUT, "wb") as f:
        f.write(raw)
    os.remove(PAYLOAD)
    os.remove(META)
    print("wrote", OUT, len(raw), flush=True)


if __name__ == "__main__":
    main()
