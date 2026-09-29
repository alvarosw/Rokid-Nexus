#!/usr/bin/env bash
# Simulate R08 ring input: fwd | back | tap | dismiss | launcher | overlay | state
set -euo pipefail
. "$(dirname "$0")/env.sh"

# `input keyevent` injects above the accessibility key filter on the API 37 image, so the hub's
# onKeyEvent never sees it (no "key code=" log line). Raw evdev events on the emulator keyboard do
# reach it, but that keyboard has no media keys, so the ring is driven through the touchpad/keyboard
# path the hub also honors: DPAD down/up = swipe, ENTER = tap, BACK = dismiss. The real R08 media
# keycodes (87/88/85) are only routed for a device named R08, which the emulator cannot create.
# INPUT_MODE=inject falls back to `input keyevent` with the old media keycodes.
# INPUT_MODE=hud broadcasts the raw keys to DebugHudInputReceiver tagged as device R08, so the ring
# pipeline of HudInput runs with the real 85/87/88 keycodes. It only takes effect once the service
# wires HudInputSeam (delivery U3); before that the receiver logs "no HudInput is wired".
KBD_DEV="${KBD_DEV:-/dev/input/event4}"   # "Cuttlefish Vhost User Keyboard 0"; see `getevent -il`
key() {  # <inject keycode> <linux evdev code>
  if [ "${INPUT_MODE:-evdev}" = "hud" ]; then
    # BACK is not a ring key: the ring dismisses with a double tap, so send it as the generic device.
    local dev=R08; [ "$1" = 4 ] && dev=KEYBOARD_DPAD
    adb_ shell am broadcast -a "$PKG.DEBUG_HUD_INPUT" -n "$PKG/.DebugHudInputReceiver" \
      --es device "$dev" --ei key "$1" | grep -E "result=|Exception|Error" || true
    return
  fi
  if [ "${INPUT_MODE:-evdev}" = "inject" ]; then adb_ shell input keyevent "$1"; return; fi
  adb_ shell "sendevent $KBD_DEV 1 $2 1; sendevent $KBD_DEV 0 0 0; sendevent $KBD_DEV 1 $2 0; sendevent $KBD_DEV 0 0 0"
}

case "${1:-}" in
  fwd)      key 87 108 ;;   # KEY_DOWN  (inject: MEDIA_NEXT)
  back)     key 88 103 ;;   # KEY_UP    (inject: MEDIA_PREVIOUS)
  tap)      key 85 28 ;;    # KEY_ENTER (inject: MEDIA_PLAY_PAUSE)
  dismiss)  key 4 158 ;;    # KEY_BACK
  launcher) adb_ shell am broadcast -a "$PKG.action.OPEN_LAUNCHER" -n "$PKG/.OpenLauncherReceiver" ;;  # toggles
  overlay)  adb_ shell am broadcast -a "$PKG.PROBE" -n "$PKG/.ProbeBroadcastReceiver" --es probe surface-overlay ;;
  state)    adb_ shell am broadcast -a "$PKG.PROBE" -n "$PKG/.ProbeBroadcastReceiver" --es probe state ;;
  *) echo "usage: $0 fwd|back|tap|dismiss|launcher|overlay|state" >&2; exit 2 ;;
esac
