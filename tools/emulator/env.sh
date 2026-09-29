#!/usr/bin/env bash
# Shared settings, sourced by the other scripts. Override any of these from the environment.
ADB_BIN="${ADB_BIN:-/opt/cuttlefish/cf/bin/adb}"
SERIAL="${SERIAL:-0.0.0.0:6520}"
GLASSES_SIZE="${GLASSES_SIZE:-480x640}"
GLASSES_DENSITY="${GLASSES_DENSITY:-240}"
PKG="${PKG:-com.anezium.rokidbus.glasses}"
OUT_DIR="${OUT_DIR:-/tmp/nexus-emu}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

adb_() { "$ADB_BIN" -s "$SERIAL" "$@"; }
