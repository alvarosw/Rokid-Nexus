#!/usr/bin/env bash
# capture.sh <name> [seconds]  -> $OUT_DIR/<name>.png, or $OUT_DIR/<name>.mp4 when seconds is given
set -euo pipefail
. "$(dirname "$0")/env.sh"
name="${1:?usage: capture.sh <name> [record-seconds]}"
mkdir -p "$OUT_DIR"
if [ -n "${2:-}" ]; then
  adb_ shell screenrecord --time-limit "$2" /sdcard/nexus-emu.mp4
  adb_ pull /sdcard/nexus-emu.mp4 "$OUT_DIR/$name.mp4" >/dev/null
  adb_ shell rm /sdcard/nexus-emu.mp4
  echo "$OUT_DIR/$name.mp4"
else
  adb_ exec-out screencap -p > "$OUT_DIR/$name.png"
  echo "$OUT_DIR/$name.png"
fi
