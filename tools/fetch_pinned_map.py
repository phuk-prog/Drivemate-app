#!/usr/bin/env python3
"""Fetch the immutable DriveMate Map basic world overview for APK packaging.
Fail closed on size or SHA-256 mismatch: no unverified pack is included.
"""
import hashlib
import os
import pathlib
import sys
import urllib.request

URL = "https://github.com/phuk-prog/Drivemate-map/releases/download/tiles-20261122T0943Z/world-z0-6.pmtiles"
EXPECTED_SIZE = 45_133_663
EXPECTED_SHA256 = "7226891e634cc6b0733e298cd96fedd5f27c878ef03177f439a23c1e7a956c98"
OUTPUT = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/assets/world-z0-6.pmtiles"

def digest(path):
    h = hashlib.sha256()
    with open(path,"rb") as fp:
        for part in iter(lambda: fp.read(1 << 20), b""):
            h.update(part)
    return h.hexdigest()

def main():
    if OUTPUT.is_file() and OUTPUT.stat().st_size == EXPECTED_SIZE and digest(OUTPUT) == EXPECTED_SHA256:
        print("DriveMate world map already verified")
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temp = OUTPUT.with_name(OUTPUT.name + ".downloading")
    try:
        with urllib.request.urlopen(urllib.request.Request(URL, headers={
            "User-Agent": "DriveMateMapPin/1.4"
        }), timeout=90) as response, open(temp, "wb") as out:
            count = 0
            while chunk := response.read(1 << 20):
                count += len(chunk)
                if count > EXPECTED_SIZE: raise ValueError("World PMTiles is larger than pinned release")
                out.write(chunk)
        if count != EXPECTED_SIZE or digest(temp) != EXPECTED_SHA256:
            raise ValueError("World PMTiles size or SHA-256 mismatch")
        temp.replace(OUTPUT)
        print("DriveMate offline world map verified and installed")
        return 0
    finally:
        if temp.exists(): temp.unlink()

if __name__ == "__main__":
    sys.exit(main())
