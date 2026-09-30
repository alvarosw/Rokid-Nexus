#!/usr/bin/env python3
"""Contact sheet of raw screencap frames: frame-sheet.py FRAME_DIR FROM_SEC TO_SEC OUT.png [scale]

Frames from dense-capture.sh whose offset lies in [FROM_SEC, TO_SEC], identical consecutive frames
collapsed to one cell (the span is printed), 6 cells per row, downscaled by `scale` (default 3).
"""
import glob
import os
import struct
import sys
import zlib


def main():
    d, lo, hi, out = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
    scale = int(sys.argv[5]) if len(sys.argv) > 5 else 3
    files = sorted((f for f in glob.glob(d + '/*.raw') if lo <= float(os.path.basename(f)[:-4]) <= hi),
                   key=lambda p: float(os.path.basename(p)[:-4]))
    frames, prev = [], None
    for f in files:
        b = open(f, 'rb').read()
        t = float(os.path.basename(f)[:-4])
        if b == prev:
            frames[-1][2] = t
            continue
        prev = b
        frames.append([t, b, t])
    if not frames:
        sys.exit('no frames in that window')
    w0, h0 = struct.unpack('<II', frames[0][1][:8])
    cw, ch, cols = w0 // scale, h0 // scale, 6
    rows = (len(frames) + cols - 1) // cols
    width, height = cols * (cw + 2), rows * (ch + 2)
    img = [bytearray(b'\x30\x30\x30' * width) for _ in range(height)]
    for k, (t, b, t2) in enumerate(frames):
        w = struct.unpack('<I', b[:4])[0]
        px = b[16:]
        ox, oy = (k % cols) * (cw + 2), (k // cols) * (ch + 2)
        for y in range(ch):
            row = img[oy + y]
            for x in range(cw):
                i = ((y * scale) * w + (x * scale)) * 4
                row[(ox + x) * 3:(ox + x) * 3 + 3] = px[i:i + 3]
        print(f'cell {k}: t={t:+.3f}..{t2:+.3f}')
    raw = b''.join(b'\x00' + bytes(r) for r in img)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    with open(out, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b''))


if __name__ == '__main__':
    main()
