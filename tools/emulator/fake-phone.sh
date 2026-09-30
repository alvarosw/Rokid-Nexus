#!/usr/bin/env bash
# Play a scenario or envelope file into the running glasses hub as if a phone had sent it.
#   fake-phone.sh plugins8                     # scenarios/plugins8.json
#   fake-phone.sh empty hud-grid               # several, in order
#   fake-phone.sh my-envelope.json             # any envelope / envelope array / scenario file
#   fake-phone.sh --envelope /launcher/list '{"plugins":[]}'
#   fake-phone.sh --hud grid | --hud list      # launcher mode, through /glasses/hud-mode/config
#   fake-phone.sh --reset                      # forget the /launcher/open rules
# Needs the debug APK (DebugFakePhoneReceiver); see docs/EMULATION.md.
set -euo pipefail
. "$(dirname "$0")/env.sh"

HERE="$(cd "$(dirname "$0")" && pwd)"
RECEIVER="$PKG/.DebugFakePhoneReceiver"
ACTION="$PKG.DEBUG_PHONE"
REMOTE_DIR=/data/local/tmp/nexus-fake-phone

broadcast() { adb_ shell am broadcast -a "$ACTION" -n "$RECEIVER" "$@" | grep -E "result=|Exception|Error" || true; }

[ "$#" -gt 0 ] || { sed -n '2,9p' "$0" >&2; exit 2; }
n=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --reset) broadcast --ez reset true ;;
    --hud)   broadcast --es hudMode "${2:?--hud list|grid}"; shift ;;
    --envelope)
      # adb joins its arguments for the device shell, which would strip the JSON's double quotes.
      broadcast --es path "$(printf %q "${2:?path}")" --es payload "$(printf %q "${3:-{\}}")"; shift 2 ;;
    *)
      src="$1"
      [ -f "$src" ] || src="$HERE/scenarios/$1.json"
      [ -f "$src" ] || { echo "no scenario or file: $1" >&2; exit 1; }
      n=$((n + 1))
      tmp="$(mktemp)"
      python3 "$HERE/fake-phone-build.py" "$src" > "$tmp"
      adb_ shell "mkdir -p $REMOTE_DIR && chmod 755 $REMOTE_DIR"
      adb_ push "$tmp" "$REMOTE_DIR/script$n.json" >/dev/null
      adb_ shell "chmod 644 $REMOTE_DIR/script$n.json"
      rm -f "$tmp"
      echo "playing $1"
      broadcast --es file "$REMOTE_DIR/script$n.json"
      ;;
  esac
  shift
done
