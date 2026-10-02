#!/usr/bin/env bash
# Start (or "stop") a plain Android 12L / API 32 phone emulator for the phone hub, headless, 1080x2400 @ 420,
# 2 GB RAM. Same AVD home, system image and TMPDIR as start-api32.sh; only one emulator runs at a time on this
# machine, so it refuses to start next to the glasses AVD, another emulator or Cuttlefish.
#   one-time:  tools/emulator/start-phone.sh create
#   then:      tools/emulator/start-phone.sh          # boots and waits, serial emulator-5572
#              tools/emulator/install-phone.sh
# See docs/EMULATION.md ("Fake glasses (phone hub)").
set -euo pipefail
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
AVD_HOME="${AVD_HOME:-$HOME/cuttlefish-api32/avd}"
AVD_NAME="${PHONE_AVD_NAME:-phone32}"
PORT="${PHONE_EMU_PORT:-5572}"
export ANDROID_AVD_HOME="$AVD_HOME" ANDROID_SDK_ROOT="$SDK"
export TMPDIR="${TMPDIR_API32:-$HOME/cuttlefish-api32/tmp}"   # /tmp is a small tmpfs on this machine
mkdir -p "$AVD_HOME" "$TMPDIR"
ADB="$SDK/platform-tools/adb"

case "${1:-start}" in
  create)
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD_NAME" \
      -k "system-images;android-32;default;x86_64" -d pixel --force
    python3 - "$AVD_HOME/$AVD_NAME.avd/config.ini" <<'PY'
import sys
p = sys.argv[1]; d = {}
for l in open(p):
    if '=' in l:
        k, v = l.rstrip('\n').split('=', 1); d[k.strip()] = v.strip()
d.update({'hw.lcd.width': '1080', 'hw.lcd.height': '2400', 'hw.lcd.density': '420', 'hw.ramSize': '2048',
          'hw.cpu.ncore': '2', 'skin.name': '1080x2400', 'skin.dynamic': 'no', 'hw.keyboard': 'yes',
          'disk.dataPartition.size': '4G'})
d.pop('skin.path', None)
open(p, 'w').write('\n'.join(f'{k}={v}' for k, v in d.items()) + '\n')
PY
    ;;
  stop)
    "$ADB" -s "emulator-$PORT" emu kill ;;
  start)
    # One emulator at a time: 11 GB of RAM does not fit two, and the glasses AVD (5570) or a Cuttlefish may be in use.
    others="$("$ADB" devices | awk '$1 ~ /^emulator-/ {print $1}' | tr '\n' ' ')"
    if [ -n "$others" ]; then echo "start-phone.sh: an emulator is already running ($others); stop it first" >&2; exit 1; fi
    if pgrep -x 'launch_cvd|run_cvd|crosvm' >/dev/null 2>&1; then
      echo "start-phone.sh: a Cuttlefish instance is running; stop it first" >&2; exit 1
    fi
    nohup "$SDK/emulator/emulator" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot \
      -gpu swiftshader_indirect -port "$PORT" -memory 2048 > "$TMPDIR/emulator-phone32.log" 2>&1 &
    until "$ADB" devices | grep -q "^emulator-$PORT[[:space:]]*device"; do sleep 2; done   # never a bare wait-for-device: a physical device may be attached
    until [ "$("$ADB" -s "emulator-$PORT" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
    "$ADB" -s "emulator-$PORT" root >/dev/null; sleep 3
    until "$ADB" devices | grep -q "^emulator-$PORT[[:space:]]*device"; do sleep 1; done
    "$ADB" -s "emulator-$PORT" shell getprop ro.build.version.sdk ;;
  *) echo "usage: $0 [create|start|stop]" >&2; exit 2 ;;
esac
