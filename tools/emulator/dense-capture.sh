#!/usr/bin/env bash
# dense-capture.sh <label> <evdev-key-code> [seconds-after]
# Runs a raw `screencap` loop on the device (about 150 ms per frame) around one evdev key press and
# pulls the frames to $OUT_DIR/dense-<label>/, renamed to their offset from the press in seconds
# (`-1.032.raw`, `+0.118.raw`). Feed the directory to analyze-frames.py and frame-sheet.py.
# Env: the settings of env.sh, plus KBD_DEV (the emulator keyboard node, per EMU_TARGET).
set -euo pipefail
. "$(dirname "$0")/env.sh"
label="${1:?usage: dense-capture.sh <label> <evdev-key-code> [seconds-after]}"
code="${2:?evdev key code, e.g. 28 = ENTER, 108 = DOWN, 158 = BACK}"
out="$OUT_DIR/dense-$label"
rm -rf "$out"; mkdir -p "$out"
adb_ shell "rm -rf /data/local/tmp/fr; mkdir -p /data/local/tmp/fr
(while true; do screencap /data/local/tmp/fr/\$(date +%s%N).raw; done) &
L=\$!
sleep 1.2
date +%s%N > /data/local/tmp/fr/action.txt
sendevent $KBD_DEV 1 $code 1; sendevent $KBD_DEV 0 0 0; sendevent $KBD_DEV 1 $code 0; sendevent $KBD_DEV 0 0 0
sleep ${3:-4}
kill \$L; sleep 0.3" || true   # the killed background loop makes the shell exit non-zero
adb_ pull /data/local/tmp/fr/. "$out" >/dev/null 2>&1
python3 - "$out" <<'PY'
import os, sys
d = sys.argv[1]
action = int(open(d + '/action.txt').read())
for f in os.listdir(d):
    if f.endswith('.raw'):
        os.rename(f"{d}/{f}", f"{d}/{(int(f[:-4]) - action) / 1e9:+.3f}.raw")
os.remove(d + '/action.txt')
PY
echo "$out"
