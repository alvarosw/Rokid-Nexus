#!/usr/bin/env bash
# Shared settings, sourced by the other scripts. Override any of these from the environment.
# EMU_TARGET picks the device the scripts talk to (each value can still be overridden below):
#   cuttlefish37 (default)  the API 37 Cuttlefish in /opt/cuttlefish/cf
#   api32                   the Android 12L emulator started by start-api32.sh (matches the Rokid OS)
EMU_TARGET="${EMU_TARGET:-cuttlefish37}"
case "$EMU_TARGET" in
  api32)
    ADB_BIN="${ADB_BIN:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/platform-tools/adb}"
    SERIAL="${SERIAL:-emulator-5570}"
    KBD_DEV="${KBD_DEV:-/dev/input/event1}" ;;   # "AT Translated Set 2 keyboard"
  cuttlefish37|*)
    ADB_BIN="${ADB_BIN:-/opt/cuttlefish/cf/bin/adb}"
    SERIAL="${SERIAL:-0.0.0.0:6520}"
    KBD_DEV="${KBD_DEV:-/dev/input/event4}" ;;   # "Cuttlefish Vhost User Keyboard 0"
esac
GLASSES_SIZE="${GLASSES_SIZE:-480x640}"
GLASSES_DENSITY="${GLASSES_DENSITY:-240}"
PKG="${PKG:-com.anezium.rokidbus.glasses}"
OUT_DIR="${OUT_DIR:-/tmp/nexus-emu}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Never touch a physical device: only emulator/Cuttlefish serials are accepted, and adb_ always passes -s.
case "$SERIAL" in
  emulator-*|0.0.0.0:*|127.0.0.1:*|localhost:*) ;;
  *) echo "env.sh: refusing SERIAL=$SERIAL (not an emulator serial)" >&2; exit 1 ;;
esac

adb_() { "$ADB_BIN" -s "$SERIAL" "$@"; }
