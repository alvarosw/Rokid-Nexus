#!/usr/bin/env python3
"""Classify raw screencap frames as the ROM home wallpaper or something Nexus owns.

Usage: analyze-frames.py FRAME_DIR [--home-ref FILE] [--from SEC] [--to SEC] [--fail-on-home]

FRAME_DIR holds `<seconds>.raw` files as written by dense-capture.sh (the name is the offset from
the key press; Android `screencap` output: a 16-byte header of w, h, format, colorspace, then RGBA).
Two classes only:

  home   the ROM home wallpaper is showing, i.e. no Nexus window covers the screen
  nexus  anything else: black ground, the launcher, a plugin surface, an image, a mid-morph frame

Nexus draws one hue on a black ground (docs/ui-rewrite/00-architecture.md), except decoded images,
so a colorful image or a green panel is never mistaken for the launcher: a frame is `home` only when
it matches the wallpaper. With --home-ref (a raw frame taken on the home screen, ideally the frame
before the flow) that means at least 60 % of the sampled pixels are within a small distance of the
reference. Without it the heuristic keys on the wallpaper's dark navy ground (a slightly blue black
that covers most of the screen), which Nexus never draws: its ground is pure black and its lit
pixels are the one green hue. A photo or a green panel has no such ground, so neither is taken for
the home; a frame where the wallpaper shows around a Nexus window counts as `home`.

--from/--to restrict the window (seconds from the key press) that the summary and the exit code
cover; every frame is still listed. With --fail-on-home the exit status is 1 when any frame of the
window is `home`, which is how a regression tour asserts "no frame without a Nexus window".
"""
import argparse
import glob
import os
import struct
import sys

STEP = 7            # sample every 7th pixel: the classes are broad, not pixel-exact
NAVY_SHARE = 0.25   # share of sampled pixels of the wallpaper's navy ground that means "home"
REF_TOLERANCE = 6   # per-channel distance to the reference wallpaper pixel
REF_MATCH = 0.60    # share of sampled pixels that must match the reference


def read_raw(path):
    data = open(path, 'rb').read()
    w, h, _fmt = struct.unpack('<III', data[:12])
    return w, h, data[16:16 + w * h * 4]


def offset_of(path):
    return float(os.path.basename(path)[:-4])


def stats(px, w, h):
    total = lit = navy = green = 0
    for i in range(0, w * h * 4, 4 * STEP):
        r, g, b = px[i], px[i + 1], px[i + 2]
        total += 1
        if max(r, g, b) <= 3:
            continue
        if b > r + 3 and b >= g and max(r, g, b) <= 70:
            navy += 1
        elif g >= r and g >= b * 0.9 and g > 60:
            green += 1
        if max(r, g, b) > 24:
            lit += 1
    return total, lit, navy, green


def matches_reference(px, ref, w, h):
    if len(ref) != len(px):
        return False
    match = total = 0
    for i in range(0, w * h * 4, 4 * STEP):
        total += 1
        if (abs(px[i] - ref[i]) <= REF_TOLERANCE and abs(px[i + 1] - ref[i + 1]) <= REF_TOLERANCE
                and abs(px[i + 2] - ref[i + 2]) <= REF_TOLERANCE):
            match += 1
    return match / total >= REF_MATCH


def classify(px, w, h, ref):
    total, lit, navy, green = stats(px, w, h)
    if ref is not None:
        kind = 'home' if matches_reference(px, ref, w, h) else 'nexus'
    else:
        kind = 'home' if navy / total >= NAVY_SHARE else 'nexus'
    return kind, lit / total, navy / total, green / total


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('frame_dir')
    ap.add_argument('--home-ref', help='raw frame of the ROM home screen (recommended)')
    ap.add_argument('--from', dest='lo', type=float, default=float('-inf'))
    ap.add_argument('--to', dest='hi', type=float, default=float('inf'))
    ap.add_argument('--fail-on-home', action='store_true')
    args = ap.parse_args()

    ref = read_raw(args.home_ref)[2] if args.home_ref else None
    files = sorted(glob.glob(os.path.join(args.frame_dir, '*.raw')), key=offset_of)
    if not files:
        sys.exit(f'no .raw frames in {args.frame_dir}')

    in_window = home_in_window = 0
    for path in files:
        t = offset_of(path)
        w, h, px = read_raw(path)
        kind, lit, navy, green = classify(px, w, h, ref)
        inside = args.lo <= t <= args.hi
        if inside:
            in_window += 1
            home_in_window += kind == 'home'
        print(f'{t:+8.3f}s {w}x{h} lit={lit:5.1%} navy={navy:5.1%} green={green:5.1%} -> {kind}'
              + ('' if inside else '  (outside window)'))
    print(f'frames in window: {in_window}, without a Nexus window (home): {home_in_window}')
    if args.fail_on_home and home_in_window:
        sys.exit(1)


if __name__ == '__main__':
    main()
