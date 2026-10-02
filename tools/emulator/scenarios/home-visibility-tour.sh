#!/usr/bin/env bash
# Automated tour of the glasses hub's home-visibility reporter (/core/home/visibility), driven through the
# fake phone (fake-phone.sh) on the glasses emulator. Assumes the emulator is booted (start-api32.sh) with the
# debug glasses hub installed and armed (install-and-arm.sh).
#   EMU_TARGET=api32 tools/emulator/scenarios/home-visibility-tour.sh [--install] [G1 G4 ...]
#     --install  run install-and-arm.sh first (SKIP_BUILD=1 unless BUILD=1; assemble the debug APK beforehand)
#     G<n> ...   run only these scenarios
# Asserts by polling logcat for "FAKE_PHONE outbound /core/home/visibility {...}" lines (epoch timestamps),
# prints PASS/FAIL/SKIP per scenario with the observed payloads, and exits non-zero when any scenario failed.
# Talks to SERIAL only (adb_); EMU_TARGET=api32 is the glasses emulator on emulator-5570.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/../env.sh"
FP="$HERE/../fake-phone.sh"
RING="$HERE/../ring.sh"
VIS=/core/home/visibility

install=0 only=()
for arg in "$@"; do
  case "$arg" in
    --install) install=1 ;;
    G[0-9]*) only+=("$arg") ;;
    *) echo "usage: $0 [--install] [G1 ...]" >&2; exit 2 ;;
  esac
done
if [ "$install" = 1 ]; then
  [ "${BUILD:-0}" = 1 ] || export SKIP_BUILD=1
  "$HERE/../install-and-arm.sh" || { echo "install failed" >&2; exit 1; }
fi
adb_ get-state >/dev/null 2>&1 || { echo "no device $SERIAL; run tools/emulator/start-api32.sh" >&2; exit 1; }
adb_ shell pm list packages "$PKG" | grep -q "$PKG" || { echo "hub not installed; use --install" >&2; exit 1; }

# ---- helpers ------------------------------------------------------------------------------------------------

fp() { "$FP" "$@" >/dev/null 2>&1 || { echo "  fake-phone.sh $* failed"; return 1; }; }
now() { adb_ shell date +%s.%N | tr -d '\r'; }
# Hub log (epoch timestamps) newer than $1.
logs_since() {
  adb_ logcat -d -v epoch -s ROKIDBUS:I | tr -d '\r' | awk -v m="$1" '$1 + 0 > m + 0'
}
clock() { printf '%s.%s' "$(date -d "@${1%%.*}" +%T)" "${1#*.}" | cut -c1-12; }
show() { local epoch rest; read -r epoch rest <<<"$1"; printf '      [%s] %s\n' "$(clock "$epoch")" "$(cut -c1-200 <<<"${rest#*: }")"; }
vis_lines() { logs_since "$1" | grep -F "FAKE_PHONE outbound $VIS"; }
# vis_re <screenOn> <homeVisible>: the payload carries both values, key order not assumed.
vis_match() { vis_lines "$1" | grep -F "\"screenOn\":$2" | grep -F "\"homeVisible\":$3"; }
vis_count() { vis_lines "$1" | wc -l; }
# epoch of a log line
epoch_of() { awk '{print $1}' <<<"$1"; }
secs_between() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.3f", b - a }'; }

scenario_fail=0 MATCH="" MARK=0
# wait_vis <screenOn> <homeVisible> <timeout s> [since]: polls for such a report; sets MATCH.
wait_vis() {
  local deadline=$((SECONDS + $3)) since="${4:-$MARK}"
  while :; do
    MATCH="$(vis_match "$since" "$1" "$2" | head -1)"
    [ -n "$MATCH" ] && return 0
    ((SECONDS >= deadline)) && return 1
    sleep 0.3
  done
}
expect_vis() { # <description> <screenOn> <homeVisible> <timeout s> [since]
  if wait_vis "$2" "$3" "$4" "${5:-$MARK}"; then echo "    ok   $1"; show "$MATCH"; return 0; fi
  echo "    FAIL $1 (no report screenOn=$2 homeVisible=$3 within $4 s)"
  vis_lines "${5:-$MARK}" | while read -r l; do show "$l"; done
  scenario_fail=1; return 1
}
# expect_quiet <description> <seconds> [since]: no report at all in the window.
expect_quiet() {
  sleep "$2"
  local hits; hits="$(vis_lines "${3:-$MARK}")"
  if [ -z "$hits" ]; then echo "    ok   $1 (no report for $2 s)"; return 0; fi
  echo "    FAIL $1 (reports seen)"; while read -r l; do show "$l"; done <<<"$hits"; scenario_fail=1; return 1
}

# Launcher state as the hub logged it last ("Launcher overlay opened|closed"); unknown counts as closed.
launcher_open() { adb_ logcat -d -s ROKIDBUS:I | grep -E 'Launcher overlay (opened|closed)' | tail -1 | grep -q opened; }
settle_open() { # <timeout s>: wait for the hub to log the overlay opened
  local d=$((SECONDS + $1)); while ((SECONDS < d)); do launcher_open && return 0; sleep 0.3; done; return 1
}
settle_closed() { local d=$((SECONDS + $1)); while ((SECONDS < d)); do launcher_open || return 0; sleep 0.3; done; return 1; }
open_home() { launcher_open || "$RING" launcher >/dev/null 2>&1; settle_open 8; }
close_home() { launcher_open && "$RING" dismiss >/dev/null 2>&1; settle_closed 8; }
screen_awake() { local p; p="$(adb_ shell dumpsys power | tr -d '\r')"; grep -q 'mWakefulness=Awake' <<<"$p"; }
wake() { adb_ shell input keyevent KEYCODE_WAKEUP; local d=$((SECONDS + 8)); while ((SECONDS < d)); do screen_awake && return 0; sleep 0.3; done; return 1; }

# Fresh state: link down, rules forgotten, screen on, launcher closed, content loaded, then link up. G1 does its own link up.
base_setup() {
  fp link down; fp --reset
  screen_awake || wake || return 1
  close_home || true
  fp plugins8 hud-grid || return 1
  sleep 1
}
setup_or_fail() { base_setup || { echo "    FAIL setup"; scenario_fail=1; return 1; }; }
link_up_and_settle() { fp link up || return 1; MARK="$(now)"; sleep 1; }

RESULTS=()
CUR=""
begin() {
  CUR="$1"; scenario_fail=0
  if ((${#only[@]})) && ! printf '%s\n' "${only[@]}" | grep -qx "$1"; then return 1; fi
  echo; echo "=== $1: $2"
}
finish() {
  if [ "$scenario_fail" = 0 ]; then RESULTS+=("PASS $CUR $1"); echo "  PASS $CUR"
  else RESULTS+=("FAIL $CUR $1"); echo "  FAIL $CUR"; fi
}
skip() { RESULTS+=("SKIP $CUR $1"); echo "  SKIP $CUR: $1"; }

cleanup() {
  adb_ shell svc power stayon true >/dev/null 2>&1
  adb_ shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  launcher_open && "$RING" dismiss >/dev/null 2>&1
  "$FP" link down >/dev/null 2>&1
  "$FP" --reset >/dev/null 2>&1
}
trap cleanup EXIT

echo "home visibility tour on $SERIAL, $(date +%T)"
adb_ shell svc power stayon true
adb_ shell input keyevent KEYCODE_WAKEUP

# ---- G1 -----------------------------------------------------------------------------------------------------
if begin G1 "transport-up report carries the current values"; then
  if setup_or_fail; then
    MARK="$(now)"; fp link up
    expect_vis "report right after link up (screen on, home closed)" true false 6
    caps="$(logs_since "$MARK" | grep -F 'FAKE_PHONE outbound /system/hub/capabilities' | head -1)"
    if [ -n "$caps" ] && [ -n "$MATCH" ]; then
      echo "    capabilities before report: $(secs_between "$(epoch_of "$caps")" "$(epoch_of "$MATCH")") s"
      show "$caps"
    else echo "    note: no capabilities line captured"; fi
  fi
  finish "transport-up"
fi

# ---- G2 -----------------------------------------------------------------------------------------------------
if begin G2 "home open reports screenOn:true homeVisible:true"; then
  if setup_or_fail; then
    link_up_and_settle
    MARK="$(now)"; open_home || { echo "    FAIL launcher did not open"; scenario_fail=1; }
    expect_vis "home open" true true 6
  fi
  finish "home open"
fi

# ---- G3 -----------------------------------------------------------------------------------------------------
if begin G3 "home close reports screenOn:true homeVisible:false"; then
  if setup_or_fail; then
    link_up_and_settle
    open_home; wait_vis true true 6 "$MARK"
    MARK="$(now)"; close_home || { echo "    FAIL launcher did not close"; scenario_fail=1; }
    expect_vis "home closed" true false 6
  fi
  finish "home close"
fi

# ---- G4 -----------------------------------------------------------------------------------------------------
if begin G4 "screen off with home open, then wake"; then
  if setup_or_fail; then
    link_up_and_settle
    open_home; wait_vis true true 6 "$MARK"
    adb_ shell svc power stayon false
    MARK="$(now)"; adb_ shell input keyevent KEYCODE_SLEEP
    expect_vis "screen off: homeVisible forced false" false false 8
    sleep 1
    MARK="$(now)"; adb_ shell input keyevent KEYCODE_WAKEUP
    expect_vis "wake: screenOn true again" true "$(launcher_open && echo true || echo false)" 8 || true
    # What the HUD really shows after wake decides the expected homeVisible.
    sleep 1.5
    if launcher_open; then want=true; else want=false; fi
    echo "    observed after wake: launcher log says $( [ $want = true ] && echo opened || echo closed ) (home on HUD: $want)"
    last="$(vis_lines "$MARK" | tail -1)"
    if grep -F "\"screenOn\":true" <<<"$last" | grep -qF "\"homeVisible\":$want"; then
      echo "    ok   last report after wake matches the HUD (homeVisible=$want)"; show "$last"
    else
      echo "    FAIL last report after wake does not match the HUD (want homeVisible=$want)"
      vis_lines "$MARK" | while read -r l; do show "$l"; done; scenario_fail=1
    fi
    adb_ shell svc power stayon true
  fi
  finish "screen off / wake"
fi

# ---- G5 -----------------------------------------------------------------------------------------------------
if begin G5 "debounce: a flap collapses into its settled value"; then
  if setup_or_fail; then
    link_up_and_settle
    close_home
    MARK="$(now)"
    # open, close, open from one device shell so the gaps are as short as am allows; the verdict uses the gaps the hub logged.
    adb_ shell "am broadcast -a $PKG.action.OPEN_LAUNCHER -n $PKG/.OpenLauncherReceiver >/dev/null; am broadcast -a $PKG.action.OPEN_LAUNCHER -n $PKG/.OpenLauncherReceiver >/dev/null; am broadcast -a $PKG.action.OPEN_LAUNCHER -n $PKG/.OpenLauncherReceiver >/dev/null"
    sleep 2
    edges="$(logs_since "$MARK" | grep -E 'Launcher overlay (opened|closed)')"
    echo "    launcher edges logged:"; while read -r l; do [ -n "$l" ] && show "$l"; done <<<"$edges"
    n_edges="$(grep -c . <<<"$edges")"
    first="$(epoch_of "$(head -1 <<<"$edges")")"; lastE="$(epoch_of "$(tail -1 <<<"$edges")")"
    if [ "$n_edges" -lt 3 ] || awk -v a="$first" -v b="$lastE" 'BEGIN { exit !(b - a >= 0.3) }'; then
      skip "could not generate sub-300 ms flaps (edges=$n_edges span=$(secs_between "${first:-0}" "${lastE:-0}") s; am startup too slow)"
      scenario_skipped=1
    else
      n="$(vis_count "$MARK")"
      if [ "$n" = 1 ] && vis_match "$MARK" true true | grep -q .; then
        echo "    ok   $n_edges edges within 300 ms produced exactly one report"; show "$(vis_lines "$MARK" | head -1)"
      else
        echo "    FAIL expected exactly one homeVisible:true report, got $n"; vis_lines "$MARK" | while read -r l; do show "$l"; done
        scenario_fail=1
      fi
    fi
  fi
  [ "${scenario_skipped:-0}" = 1 ] || finish "debounce"
fi

# ---- G6 -----------------------------------------------------------------------------------------------------
if begin G6 "no report when nothing changed; link down/up re-sends the unchanged value"; then
  if setup_or_fail; then
    link_up_and_settle
    wait_vis true false 6 "$MARK"
    MARK="$(now)"
    expect_quiet "idle with nothing changing" 4
    fp link down; sleep 1
    MARK="$(now)"; fp link up
    expect_vis "value re-sent after link down/up although unchanged" true false 6
  fi
  finish "de-dup / resend"
fi

# ---- G7 -----------------------------------------------------------------------------------------------------
if begin G7 "plugin surface from home: homeVisible false while shown, true back on home"; then
  if setup_or_fail; then
    link_up_and_settle
    open_home; expect_vis "home open" true true 6
    MARK="$(now)"; "$RING" tap >/dev/null 2>&1   # opens the selected plugin; the fake phone answers with its surface
    expect_vis "surface shown: homeVisible false" true false 10
    surf="$MATCH"
    sleep 1.5
    MARK="$(now)"; "$RING" dismiss >/dev/null 2>&1   # BACK from the surface returns to home
    expect_vis "back to home: homeVisible true" true true 8
  fi
  finish "plugin surface"
fi

echo; echo "=== summary"
rc=0
for r in "${RESULTS[@]}"; do echo "  $r"; case "$r" in FAIL*) rc=1 ;; esac; done
exit $rc
