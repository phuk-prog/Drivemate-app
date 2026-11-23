#!/usr/bin/env python3
"""Live DriveMate Map integration gate: prove real UK street geometry exists.

Only downloads requested PMTiles directory/road-tile ranges (not the full 1.9GB
archive). Verifies actual Shortbread road-feature contents and expected style
source layers for the GPS display around Stockport.
"""
import gzip
import json
import math
import pathlib
import struct
import sys
import urllib.request
from probe_drive_map_range import URL, SIZE, RangeRedirect

ROOT = pathlib.Path(__file__).resolve().parents[1]
STYLE = ROOT / "app/src/main/assets/drivemate_vector_style.json"
opener = urllib.request.build_opener(RangeRedirect())

def fetch(offset, length):
    if not 0 <= offset < SIZE or not 0 < length <= 4_194_304 or offset + length > SIZE:
        raise ValueError("Invalid bounded PMTiles range")
    req = urllib.request.Request(URL, headers={
        "Range": f"bytes={offset}-{offset + length - 1}",
        "Accept-Encoding": "identity",
        "User-Agent": "DriveMateRealRoadTest/1.4.1",
    })
    with opener.open(req, timeout=40) as response:
        expected = f"bytes {offset}-{offset + length - 1}/{SIZE}"
        got = response.headers.get("Content-Range")
        if response.status != 206 or got != expected:
            raise ValueError(f"PMTiles host returned wrong byte range: {response.status}, {got}")
        data = response.read(length + 1)
        if len(data) != length: raise ValueError("PMTiles range truncated")
        return data

def varint(data, offset):
    value = 0
    for shift in range(0, 70, 7):
        if offset >= len(data): raise ValueError("Truncated vector map varint")
        v = data[offset]
        offset += 1
        value |= (v & 127) << shift
        if not (v & 128): return value, offset
    raise ValueError("Overlong varint")

def directory(data):
    pos = 0
    n, pos = varint(data, pos)
    if n > 200_000: raise ValueError("Oversize map directory")
    arrays = []
    for col in range(4):
        values = []
        for _ in range(n):
            value, pos = varint(data, pos)
            values.append(value)
        arrays.append(values)
    ids, runs, lengths, offsets = arrays
    absolute = []
    for i in range(n):
        if i: ids[i] += ids[i-1]
        if i and offsets[i] == 0: absolute.append(absolute[-1] + lengths[i-1])
        else: absolute.append(offsets[i] - 1)
    return list(zip(ids, runs, lengths, absolute))

def tile_id(z, x, y):
    total = ((1 << (2*z)) - 1) // 3
    n = 1 << z
    d = 0
    s = n // 2
    while s:
        rx = 1 if x & s else 0
        ry = 1 if y & s else 0
        d += s*s*((3*rx) ^ ry)
        if ry == 0:
            if rx == 1: x, y = n-1-x, n-1-y
            x, y = y, x
        s //= 2
    return total + d

def xy(lat, lon, z):
    x = int((lon + 180) / 360 * (1<<z))
    r = math.radians(lat)
    y = int((1-math.log(math.tan(r) + 1/math.cos(r))/math.pi)/2 * (1<<z))
    return x, y

def decompress(data, kind):
    if kind == 1: return data
    if kind == 2: return gzip.decompress(data)
    raise ValueError("Unsupported PMTiles compression")

def find(entries, tid):
    matches = [e for e in entries if e[0] <= tid]
    return matches[-1] if matches else None

def fetch_tile(header, z, x, y):
    root_offset, root_length = struct.unpack_from("<QQ", header, 8)
    leaf_offset = struct.unpack_from("<Q", header, 40)[0]
    data_offset = struct.unpack_from("<Q", header, 56)[0]
    comp, tile_comp = header[97:99]
    entries = directory(decompress(fetch(root_offset, root_length), comp))
    tid = tile_id(z, x, y)
    e = find(entries, tid)
    if not e: return b""
    if e[1] == 0:
        entries = directory(decompress(fetch(leaf_offset+e[3], e[2]), comp))
        e = find(entries, tid)
        if not e: return b""
    if not e[1] or tid >= e[0]+e[1]: return b""
    return decompress(fetch(data_offset+e[3], e[2]), tile_comp)

def fields(message):
    pos=0
    while pos < len(message):
        key,pos=varint(message,pos)
        field,wire=key>>3,key&7
        if wire==0:
            value,pos=varint(message,pos)
        elif wire==1:
            if pos+8>len(message):raise ValueError("Truncated fixed64")
            value=message[pos:pos+8];pos+=8
        elif wire==2:
            n,pos=varint(message,pos)
            if n<0 or pos+n>len(message):raise ValueError("Truncated field")
            value=message[pos:pos+n];pos+=n
        elif wire==5:
            if pos+4>len(message):raise ValueError("Truncated fixed32")
            value=message[pos:pos+4];pos+=4
        else: raise ValueError("Invalid MVT wire type")
        yield field,wire,value

def layers_from_mvt(tile):
    layers={}
    for field,wire,value in fields(tile):
        if field!=3 or wire!=2: continue
        name=None
        features=0
        for fid,fw,fv in fields(value):
            if fid==1 and fw==2: name=fv.decode("utf8")
            elif fid==2 and fw==2: features+=1
        if name: layers[name]=features
    return layers

def check_style():
    s=json.loads(STYLE.read_text())
    sources=s["sources"]
    assert "uk" in sources and sources["uk"]["maxzoom"]==14
    spec={layer["id"]:layer for layer in s["layers"]}
    for id, expected in [("uk-roads","streets"),("uk-trunk","streets"),
                         ("uk-street-labels","street_labels"),
                         ("uk-place-labels","place_labels")]:
        assert spec[id]["source"]=="uk",id
        assert spec[id]["source-layer"]==expected,id
    assert "pmtiles://asset://world-z0-6.pmtiles" == sources["world"]["url"]
    return spec

def main():
    check_style()
    header=fetch(0,127)
    assert header[:7]==b"PMTiles" and header[7]==3
    assert header[99]==1 and header[101]==14, "Unexpected UK tile archive metadata"
    samples=[(53.405,-2.165),(53.410,-2.171),(53.400,-2.154)]
    seen={}
    for lat,lon in samples:
        x,y=xy(lat,lon,14)
        layers=layers_from_mvt(fetch_tile(header,14,x,y))
        for name,count in layers.items(): seen[name]=seen.get(name,0)+count
    assert seen.get("streets",0)>=3, f"UK Stockport map has no streets: {seen}"
    assert seen.get("street_labels",0)+seen.get("place_labels",0)>0, (
        f"UK Stockport tiles do not contain expected labels: {seen}"
    )
    print("PASS: Real UK PMTiles z14 street features and street/place labels "
          f"are present and match DriveMate style: {seen.get('streets',0)} "
          f"street features, {seen.get('street_labels',0)} street labels.")
    return 0

if __name__=="__main__":
    sys.exit(main())
