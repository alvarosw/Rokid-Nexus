#!/usr/bin/env bash
# Start (or "stop") the Android 12L / API 32 emulator that matches the Rokid glasses OS, headless,
# 480x640 @ 240 dpi, 4 GB RAM. Everything lives outside the repo: the AVD in $AVD_HOME, the system
# image and emulator in the machine's existing Android SDK. See docs/EMULATION.md ("API 32").
#   one-time:  sdkmanager --sdk_root=$SDK emulator "system-images;android-32;default;x86_64"
#              tools/emulator/start-api32.sh create
#   then:      tools/emulator/start-api32.sh          # boots and waits
#              EMU_TARGET=api32 tools/emulator/install-and-arm.sh
set -euo pipefail
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
AVD_HOME="${AVD_HOME:-$HOME/cuttlefish-api32/avd}"
AVD_NAME="${AVD_NAME:-glasses32}"
PORT="${EMU_PORT:-5570}"
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
d.update({'hw.lcd.width': '480', 'hw.lcd.height': '640', 'hw.lcd.density': '240', 'hw.ramSize': '4096',
          'hw.cpu.ncore': '4', 'skin.name': '480x640', 'skin.dynamic': 'no', 'hw.keyboard': 'yes',
          'disk.dataPartition.size': '4G'})
d.pop('skin.path', None)
open(p, 'w').write('\n'.join(f'{k}={v}' for k, v in d.items()) + '\n')
PY
    ;;
  stop)
    "$ADB" -s "emulator-$PORT" emu kill ;;
  start)
    nohup "$SDK/emulator/emulator" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot \
      -gpu swiftshader_indirect -port "$PORT" -memory 4096 > "$TMPDIR/emulator-api32.log" 2>&1 &
    until "$ADB" devices | grep -q "^emulator-$PORT[[:space:]]*device"; do sleep 2; done   # never a bare wait-for-device: a physical device may be attached
    until [ "$("$ADB" -s "emulator-$PORT" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
    # sendevent to the keyboard node (ring.sh) needs root: the shell user is not in the input group here
    "$ADB" -s "emulator-$PORT" root >/dev/null; sleep 3
    until "$ADB" devices | grep -q "^emulator-$PORT[[:space:]]*device"; do sleep 1; done
    "$ADB" -s "emulator-$PORT" shell getprop ro.build.version.sdk ;;
  *) echo "usage: $0 [create|start|stop]" >&2; exit 2 ;;
esac
