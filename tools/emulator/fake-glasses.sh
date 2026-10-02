#!/usr/bin/env bash
# Drive the phone hub as if Rokid glasses were attached; the phone-side mirror of fake-phone.sh.
#   fake-glasses.sh start                      # start the hub service
#   fake-glasses.sh link up|down|release       # force the link bits and capture outbound envelopes / restore
#   fake-glasses.sh handshake                  # what real glasses announce on transport-up (+ home visible)
#   fake-glasses.sh visibility on|hidden|off   # /core/home/visibility
#   fake-glasses.sh grid on|off                # launcher mode through the settings store and hook
#   fake-glasses.sh approve <package>          # approve all requested capabilities of a plugin
#   fake-glasses.sh grace <ms>                 # screen-off lease grace (default 10 min)
#   fake-glasses.sh media play|pause|stop [title [artist [durationMs]]]
#   fake-glasses.sh script <file.json>         # inbound envelopes [{path,payload,delayMs,binaryBase64}]
#   fake-glasses.sh outbound [--clear]         # captured "FAKE_GLASSES outbound" lines / clear the log
#   fake-glasses.sh log                        # every FAKE_GLASSES line
#   fake-glasses.sh reset                      # release overrides, interceptor and media session
# Talks to PHONE_SERIAL only (default emulator-5572). Needs the phone-hub debug APK; see docs/EMULATION.md.
set -euo pipefail
. "$(dirname "$0")/env.sh"

RECEIVER="$PHONE_PKG/.DebugFakeGlassesReceiver"
ACTION="$PHONE_PKG.DEBUG_GLASSES"
REMOTE_DIR=/data/local/tmp/nexus-fake-glasses

# adb joins its arguments for the device shell, which would strip a JSON's quotes and split spaces.
q() { printf %q "$1"; }
broadcast() {
  local out seen
  seen="$(adbp_ logcat -d -s FAKE_GLASSES:I | wc -l)"
  out="$(adbp_ shell am broadcast -a "$ACTION" -n "$RECEIVER" "$@")"
  echo "$out" | grep -qE "Broadcast completed" || { echo "$out" >&2; exit 1; }
  echo "$out" | grep -E "Exception|Error|Permission" >&2 || true
  sleep 0.4
  adbp_ logcat -d -s FAKE_GLASSES:I | tail -n +"$((seen + 1))" | tail -n "${SHOW_LINES:-10}"
}

[ "$#" -gt 0 ] || { sed -n '2,15p' "$0" >&2; exit 2; }
cmd="$1"; shift
case "$cmd" in
  start)      broadcast --ez start true ;;
  link)
    case "${1:?up|down|release}" in
      release) broadcast --es link release --ez release true ;;
      up|down) broadcast --es link "$1" ;;
      *) echo "link up|down|release" >&2; exit 2 ;;
    esac ;;
  handshake)  broadcast --es handshake default ;;
  visibility) broadcast --es visibility "${1:?on|hidden|off}" ;;
  grid)       broadcast --es grid "${1:?on|off}" ;;
  approve)    broadcast --es approve "$(q "${1:?package name}")" ;;
  grace)      broadcast --el screenOffGraceMs "${1:?milliseconds}" ;;
  media)
    args=(--es media "${1:?play|pause|stop}")
    [ -n "${2:-}" ] && args+=(--es title "$(q "$2")")
    [ -n "${3:-}" ] && args+=(--es artist "$(q "$3")")
    [ -n "${4:-}" ] && args+=(--el durationMs "$4")
    broadcast "${args[@]}" ;;
  script)
    src="${1:?script file}"
    [ -f "$src" ] || { echo "no such file: $src" >&2; exit 1; }
    adbp_ shell "mkdir -p $REMOTE_DIR && chmod 755 $REMOTE_DIR"
    adbp_ push "$src" "$REMOTE_DIR/script.json" >/dev/null 2>&1
    adbp_ shell "chmod 644 $REMOTE_DIR/script.json"
    broadcast --es file "$REMOTE_DIR/script.json" ;;
  outbound)
    if [ "${1:-}" = "--clear" ]; then adbp_ logcat -c; echo "cleared"
    else adbp_ logcat -d -s FAKE_GLASSES:I | grep ' outbound ' || true; fi ;;
  log)        adbp_ logcat -d -s FAKE_GLASSES:I ;;
  reset)      broadcast --ez reset true ;;
  *) sed -n '2,15p' "$0" >&2; exit 2 ;;
esac
