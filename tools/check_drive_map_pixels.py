#!/usr/bin/env python3
"""Android screenshot gate: reject DriveMate maps showing only a flat background.

Adapted from the previously working DriveMate offline map's screenshot
acceptance check. The checked map regions exclude the GPS marker and UI.
This checks painted pixels, not road-network accuracy or legality.
Use: python3 tools/check_drive_map_pixels.py screenshot.png
     python3 tools/check_drive_map_pixels.py --self-test
"""
import argparse
from collections import Counter
from pathlib import Path
import struct
import sys
import zlib

SIGNATURE = b"\x89PNG\r\n\x1a\n"

def paeth(a, b, c):
    p = a + b - c
    da, db, dc = abs(p-a), abs(p-b), abs(p-c)
    return a if da <= db and da <= dc else b if db <= dc else c

def read_rgb(path):
    data = Path(path).read_bytes()
    if not data.startswith(SIGNATURE): raise ValueError("Not a PNG screenshot")
    offset = len(SIGNATURE)
    fragments=[]
    width=height=channels=None
    while offset < len(data):
        if offset+12>len(data):raise ValueError("Truncated PNG header")
        length=struct.unpack_from(">I",data,offset)[0]
        kind=data[offset+4:offset+8]
        end=offset+length+12
        if end>len(data):raise ValueError("Truncated PNG chunk")
        payload=data[offset+8:offset+8+length]
        if kind==b"IHDR":
            width,height,depth,color,compression,filtr,interlaced=struct.unpack(">IIBBBBB",payload)
            if depth != 8 or color not in (2,6) or compression or filtr or interlaced:
                raise ValueError("Unsupported Android screenshot PNG encoding")
            channels=3 if color==2 else 4
        elif kind==b"IDAT": fragments.append(payload)
        elif kind==b"IEND": break
        offset=end
    if width is None or not fragments: raise ValueError("Screenshot pixels not available")
    raw=zlib.decompress(b"".join(fragments))
    stride=width*channels
    if len(raw)!=(stride+1)*height: raise ValueError("PNG pixel count mismatch")
    rows=[]
    last=bytearray(stride)
    for y in range(height):
        i=y*(stride+1)
        filter_type=raw[i]
        row=bytearray(raw[i+1:i+1+stride])
        if filter_type not in range(5):raise ValueError("Invalid PNG filter")
        if filter_type:
            for j in range(stride):
                left=row[j-channels] if j>=channels else 0
                up=last[j]
                corner=last[j-channels] if j>=channels else 0
                predictor=(left if filter_type==1 else up if filter_type==2
                    else (left+up)//2 if filter_type==3 else paeth(left,up,corner))
                row[j]=(row[j]+predictor)&255
        rows.append(row)
        last=row
    return width,height,channels,rows

def region_nonbackground(width,height,channels,rows,x0,y0,x1,y1):
    frequencies=Counter()
    total=0
    for y in range(int(height*y0),int(height*y1),4):
        row=rows[y]
        for x in range(int(width*x0),int(width*x1),4):
            p=x*channels
            frequencies[bytes(row[p:p+3])]+=1
            total+=1
    return total-max(frequencies.values(),default=0),total

def check_screenshot(path):
    width,height,channels,rows=read_rgb(path)
    if width<280 or height<500: raise ValueError("Unexpected mobile screenshot dimensions")
    # Two left/right road regions, away from central GPS marker and header.
    boxes=[(.08,.25,.35,.43),(.65,.25,.92,.43),
           (.08,.44,.35,.57),(.65,.44,.92,.57)]
    checks=[region_nonbackground(width,height,channels,rows,*box) for box in boxes]
    good=sum(n>=max(18,total//100) for n,total in checks)
    if good<2:
        raise ValueError("DriveMate map appears blank: visible road/map pixels "
                         f"not detected in 2 separate map regions: {checks}")
    return checks

def create_png(width,height,roads=False):
    """Construct a diagnostic screenshot fixture for fail-closed unit checks."""
    raw=bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            rgb=(233,240,224)
            if roads and (x%97 in (0,1,2,3,4) or y%137 in (0,1,2,3,4)):
                rgb=(250,250,250)
            if not roads and abs(x-width//2)<9 and abs(y-height//2)<9:
                rgb=(17,151,220) # a GPS dot alone must not pass
            raw.extend(rgb)
    def chunk(kind,body):
        return (struct.pack(">I",len(body))+kind+body+
                struct.pack(">I",zlib.crc32(kind+body)&0xffffffff))
    return (SIGNATURE + chunk(b"IHDR",struct.pack(">IIBBBBB",width,height,8,2,0,0,0))
            + chunk(b"IDAT",zlib.compress(raw)) + chunk(b"IEND",b""))

def self_test():
    import tempfile
    with tempfile.TemporaryDirectory() as directory:
        blank=Path(directory)/"blank.png"
        roads=Path(directory)/"roads.png"
        blank.write_bytes(create_png(360,760,False))
        roads.write_bytes(create_png(360,760,True))
        try:
            check_screenshot(blank)
        except ValueError as error:
            assert "blank" in str(error)
        else:
            raise AssertionError("Blank map with only a GPS marker incorrectly passed")
        assert len(check_screenshot(roads))==4
    print("PASS: Screenshot regression rejects blank map and accepts drawn roads")

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("screenshot",nargs="?",type=Path)
    parser.add_argument("--self-test",action="store_true")
    args=parser.parse_args()
    try:
        if args.self_test: self_test()
        elif args.screenshot:
            checks=check_screenshot(args.screenshot)
            print("PASS: DriveMate map has non-background painted detail in "
                  f"{sum(n>=max(18,t//100) for n,t in checks)} map regions")
        else: parser.error("Provide a screenshot PNG or --self-test")
    except (ValueError,AssertionError,OSError) as error:
        print("FAIL:",error,file=sys.stderr)
        sys.exit(1)
