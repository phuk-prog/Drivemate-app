#!/usr/bin/env python3
"""Fast, bounded smoke test: does the pinned DriveMate Map Release honor Range?
Never download the 1.87 GB archive; request 127 header bytes and fail closed.
"""
import sys
import urllib.request

URL = ("https://github.com/phuk-prog/Drivemate-map/releases/download/"
       "tiles-20261122T0943Z/united-kingdom.pmtiles")
SIZE = 1_871_405_796
class RangeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.startswith("https://"):
            raise ValueError("Non-HTTPS map redirect")
        next_req = super().redirect_request(req, fp, code, msg, headers, newurl)
        if next_req is not None:
            next_req.add_header("Range", req.headers.get("Range", "bytes=0-126"))
            next_req.add_header("Accept-Encoding", "identity")
        return next_req

def main():
    opener = urllib.request.build_opener(RangeRedirect())
    req = urllib.request.Request(URL, headers={
        "Range": "bytes=0-126",
        "Accept-Encoding": "identity",
        "User-Agent": "DriveMateMapRangeProbe/1.4"
    })
    with opener.open(req, timeout=30) as response:
        status = response.status
        content_range = response.headers.get("Content-Range", "")
        if status != 206 or content_range != f"bytes 0-126/{SIZE}":
            raise ValueError(f"Map release does not serve exact 206 range: {status}, {content_range}")
        data = response.read(128)
        if len(data) != 127 or data[:7] != b"PMTiles" or data[7] != 3:
            raise ValueError("Map release header not PMTiles v3")
    print("DriveMate Map release byte-range test passed (127 bytes only)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
