#!/usr/bin/env bash
# Resize the emulator display to the glasses canvas. "reset" restores the device defaults.
set -euo pipefail
. "$(dirname "$0")/env.sh"

if [ "${1:-}" = "reset" ]; then
  adb_ shell wm size reset
  adb_ shell wm density reset
else
  adb_ shell wm size "$GLASSES_SIZE"
  adb_ shell wm density "$GLASSES_DENSITY"
fi
adb_ shell wm size
adb_ shell wm density
