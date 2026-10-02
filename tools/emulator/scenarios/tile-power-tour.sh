#!/usr/bin/env bash
# Automated tour of the tile lease resilience and home-visibility gating features, driven through the fake
# glasses harness (fake-glasses.sh) on the phone emulator. Assumes start-phone.sh has booted emulator-5572.
#   tools/emulator/scenarios/tile-power-tour.sh [--install] [--slow] [S1 S4 ...]
#     --install  run install-phone.sh first (no build: assemble the debug APKs beforehand, see docs/EMULATION.md)
#     --slow     also run S8, which waits up to ~6 minutes for the hub's 5-minute refresh minimum to pass
#     S<n> ...   run only these scenarios
# Every scenario starts with `reset` and a fresh link up / handshake / grid on / approve, asserts by polling
# logcat (the hub's ROKIDBUS-PHONE log and the harness's FAKE_GLASSES outbound capture) with timeouts, prints
# PASS or FAIL with the evidence lines, and the exit status is non-zero when any scenario failed.
# Pacing: the hub lets each plugin publish a burst of 4 tiles, then one per 15 s, so a tile that is expected
# to arrive is given up to TILE_WAIT seconds. Talks to PHONE_SERIAL only (adbp_), never a bare adb.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
FG="$(dirname "${BASH_SOURCE[0]}")/../fake-glasses.sh"

MEDIA=com.anezium.rokidbus.plugin.media
TILE_WAIT="${TILE_WAIT:-35}"       # a publish held by pacing waits at most 15 s for a token, plus slack
install=0 slow=0 only=()
for arg in "$@"; do
  case "$arg" in
    --install) install=1 ;;
    --slow) slow=1 ;;
    S[0-9]*) only+=("$arg") ;;
    *) echo "usage: $0 [--install] [--slow] [S1 ...]" >&2; exit 2 ;;
  esac
done

if [ "$install" = 1 ]; then "$(dirname "${BASH_SOURCE[0]}")/../install-phone.sh" || { echo "install failed" >&2; exit 1; }; fi
adbp_ get-state >/dev/null 2>&1 || { echo "no device $PHONE_SERIAL; run tools/emulator/start-phone.sh" >&2; exit 1; }
adbp_ shell pm list packages "$PHONE_PKG" | grep -q "$PHONE_PKG" || { echo "hub not installed; use --install" >&2; exit 1; }
adbp_ shell pm list packages "$MEDIA" | grep -q "$MEDIA" || { echo "media plugin not installed; use --install" >&2; exit 1; }
adbp_ shell svc power stayon true

# ---- helpers ------------------------------------------------------------------------------------------------

fg() {
  local err
  err="$(SHOW_LINES=1 "$FG" "$@" 2>&1 >/dev/null)" || { echo "  fake-glasses.sh $* failed: $err"; return 1; }
}
now() { adbp_ shell date +%s.%N | tr -d '\r'; }
# Hub log and harness capture with epoch timestamps, only lines newer than $1.
logs_since() {
  adbp_ logcat -d -v epoch -s FAKE_GLASSES:I ROKIDBUS-PHONE:I | tr -d '\r' | awk -v m="$1" '$1 + 0 > m + 0'
}
clock() { printf '%s.%s' "$(date -d "@${1%%.*}" +%T)" "${1#*.}" | cut -c1-12; }
# "<epoch> <pid> <tid> I TAG: message" -> "[HH:MM:SS.mmm] message"
show() { local epoch rest; read -r epoch rest <<<"$1"; printf '      [%s] %s\n' "$(clock "$epoch")" "$(cut -c1-210 <<<"${rest#*: }")"; }
fmt_epoch() { awk '{print $1}' <<<"$1"; }
secs_between() { awk -v a="$1" -v b="$2" 'BEGIN { d = b - a; printf "%.1f", d < 0 ? 0 : d }'; }

scenario_fail=0
MATCH=""
MARK=0
# wait_for <regex> <timeout s> [since]: polls for the first matching line; sets MATCH.
wait_for() {
  local deadline=$((SECONDS + $2)) since="${3:-$MARK}"
  while :; do
    MATCH="$(logs_since "$since" | grep -E -m1 -- "$1")" && return 0
    ((SECONDS >= deadline)) && return 1
    sleep 0.5
  done
}
# expect <description> <regex> <timeout s> [since]
expect() {
  if wait_for "$2" "$3" "${4:-$MARK}"; then echo "    ok   $1"; show "$MATCH"; return 0; fi
  echo "    FAIL $1 (not seen within $3 s: $2)"; scenario_fail=1; return 1
}
# expect_none <description> <regex> <seconds> [since]: polls the whole window.
expect_none() {
  local deadline=$((SECONDS + $3)) since="${4:-$MARK}" hit
  while :; do
    hit="$(logs_since "$since" | grep -E -m1 -- "$2")"
    if [ -n "$hit" ]; then echo "    FAIL $1 (appeared: $2)"; show "$hit"; scenario_fail=1; return 1; fi
    ((SECONDS >= deadline)) && break
    sleep 0.5
  done
  echo "    ok   $1 (nothing for $3 s)"
}
tile_re() { printf 'outbound /tile/publish .*"title":"%s"' "$1"; }

# Waits until the plugin's publish bucket is mostly full again (at most one publish in the last minute), so
# a latency assertion is not stretched by pacing.
wait_bucket_refill() {
  local deadline=$((SECONDS + 75)) since n
  while ((SECONDS < deadline)); do
    since="$(awk -v n="$(now)" 'BEGIN { printf "%.3f", n - 60 }')"
    n="$(logs_since "$since" | grep -c 'outbound /tile/publish')"
    ((n <= 1)) && return 0
    sleep 2
  done
  echo "    note: publish bucket did not refill within 75 s"
}

# reset, then approval and grid mode, then link up and the handshake, and wait for the lease to be live. The
# approval comes first on purpose: approving a plugin that already holds a lease rebinds it through the retry path (see S9).
base_setup() {
  fg reset || return 1
  MARK="$(now)"
  fg approve "$MEDIA" && fg grid on && fg link up && fg handshake || return 1
  wait_for 'tile lease granted plugin=media' 20 || { echo "    setup: no lease granted"; return 1; }
  wait_for 'plugin registered package=com.anezium.rokidbus.plugin.media' 20 || { echo "    setup: plugin never registered"; return 1; }
}
setup_or_fail() { base_setup || { echo "    FAIL setup"; scenario_fail=1; return 1; }; }

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

cleanup() {
  adbp_ shell dumpsys battery reset >/dev/null 2>&1
  "$FG" reset >/dev/null 2>&1
}
trap cleanup EXIT

adbp_ logcat -c
echo "tile power tour on $PHONE_SERIAL, $(date +%T)"

# ---- S1 -----------------------------------------------------------------------------------------------------
if begin S1 "baseline: link, handshake, grid, approve, media play publishes a tile"; then
  if setup_or_fail; then
    t0="$(now)"; fg media play "S1 Song" "S1 Artist" 200000
    expect 'tile with "S1 Song" sent to the glasses' "$(tile_re 'S1 Song')" "$TILE_WAIT" "$t0"
  fi
  finish "baseline"
fi

# ---- S2 -----------------------------------------------------------------------------------------------------
if begin S2 "plugin process killed (kill -9): the lease comes back with no hub restart"; then
  if setup_or_fail; then
    fg media play "S2 Before" "S2 Artist" 200000
    expect 'precondition: tile with "S2 Before"' "$(tile_re 'S2 Before')" "$TILE_WAIT"
    pid1="$(adbp_ shell pidof "$MEDIA" | tr -d '\r')"
    t0="$(now)"; adbp_ shell "kill -9 $pid1"
    echo "      killed pid $pid1 at [$(clock "$t0")]"
    expect 'plugin registers again (Android rebinds the leased service)' 'plugin registered package=com.anezium.rokidbus.plugin.media' 30 "$t0"
    pid2="$(adbp_ shell pidof "$MEDIA" | tr -d '\r')"
    if [ -n "$pid2" ] && [ "$pid1" != "$pid2" ]; then echo "    ok   new plugin process $pid1 -> $pid2"; else echo "    FAIL plugin process not restarted ($pid1 -> '$pid2')"; scenario_fail=1; fi
    expect 'lease restored: the restarted plugin republishes the current track' "$(tile_re 'S2 Before')" "$TILE_WAIT" "$t0"
    t1="$(now)"; fg media play "S2 Song" "S2 Artist" 200000
    expect 'tile with "S2 Song" after the kill' "$(tile_re 'S2 Song')" "$TILE_WAIT" "$t1"
  fi
  finish "kill -9"
fi

# ---- S3 -----------------------------------------------------------------------------------------------------
if begin S3 "plugin force-stopped (nobody restarts it): REGISTRATION_TIMEOUT, then LEASE_RETRY recovers"; then
  if setup_or_fail; then
    fg media play "S3 Before" "S3 Artist" 200000
    expect 'precondition: tile with "S3 Before"' "$(tile_re 'S3 Before')" "$TILE_WAIT"
    t0="$(now)"; adbp_ shell am force-stop "$MEDIA"
    echo "      force-stopped at [$(clock "$t0")]"
    expect 'registration times out (15 s rebind window)' 'tile lease failed plugin=media reason=REGISTRATION_TIMEOUT retryInMs=5000' 30 "$t0"
    failed_at="$(fmt_epoch "$MATCH")"
    expect 'lease retried after the 5 s backoff' 'tile lease retry plugin=media' 15 "$t0"
    retry_at="$(fmt_epoch "$MATCH")"
    expect 'plugin registers again' 'plugin registered package=com.anezium.rokidbus.plugin.media' 15 "$retry_at"
    echo "      timeline: force-stop +0.0 s, REGISTRATION_TIMEOUT +$(secs_between "$t0" "$failed_at") s, LEASE_RETRY +$(secs_between "$t0" "$retry_at") s"
    t1="$(now)"; fg media play "S3 Song" "S3 Artist" 200000
    expect 'tile with "S3 Song" after the retry' "$(tile_re 'S3 Song')" "$TILE_WAIT" "$t1"
  fi
  finish "force-stop"
fi

# ---- S4 -----------------------------------------------------------------------------------------------------
if begin S4 "home hidden holds tiles; the visible edge releases the latest one promptly"; then
  if setup_or_fail; then
    wait_bucket_refill
    fg visibility hidden
    wait_for 'glasses home visibility screenOn=true homeVisible=false' 10 || { echo "    FAIL hidden report not processed"; scenario_fail=1; }
    t0="$(now)"; fg media play "S4 Hidden Song" "S4 Artist" 200000
    expect_none 'no tile with "S4 Hidden Song" while the home is hidden' "$(tile_re 'S4 Hidden Song')" 6 "$t0"
    fg visibility on
    if wait_for 'FAKE_GLASSES: visibility on injected' 10 "$t0"; then edge_at="$(fmt_epoch "$MATCH")"; else edge_at="$(now)"; fi
    if expect 'held tile released on the visible edge' "$(tile_re 'S4 Hidden Song')" "$TILE_WAIT" "$edge_at"; then
      lat="$(secs_between "$edge_at" "$(fmt_epoch "$MATCH")")"
      if awk -v l="$lat" 'BEGIN { exit !(l < 3.0) }'; then echo "    ok   released $lat s after the visible edge (< 3 s)"
      else echo "    FAIL released $lat s after the visible edge (>= 3 s)"; scenario_fail=1; fi
    fi
  fi
  finish "hidden hold"
fi

# ---- S5 -----------------------------------------------------------------------------------------------------
if begin S5 "legacy glasses: no visibility report means unknown, treated as visible"; then
  if setup_or_fail; then
    fg visibility hidden
    wait_for 'glasses home visibility screenOn=true homeVisible=false' 10 || { echo "    FAIL hidden report not processed"; scenario_fail=1; }
    t_link="$(now)"
    fg link down
    fg link up
    caps="$(mktemp)"
    # An older glasses hub announces its capabilities and never a /core/home/visibility report.
    cat >"$caps" <<'JSON'
[{"path":"/system/hub/capabilities","payload":{"version":1,"features":0,"setupComplete":true,"setupStage":"complete","coreReady":true,"maintenanceReady":true}}]
JSON
    fg script "$caps"; rm -f "$caps"
    expect 'lease granted again after the link came back' 'tile lease granted plugin=media' 20 "$t_link"
    t0="$(now)"; sleep 1
    fg media play "S5 Song" "S5 Artist" 200000
    expect 'tile with "S5 Song" sent at once (unknown = visible)' "$(tile_re 'S5 Song')" "$TILE_WAIT" "$t0"
    if logs_since "$t_link" | grep -q 'glasses home visibility'; then echo "    FAIL a visibility report was processed after the link came back"; scenario_fail=1
    else echo "    ok   no visibility report since the link came back"; fi
  fi
  finish "legacy"
fi

# ---- S6 -----------------------------------------------------------------------------------------------------
if begin S6 "screen off past the grace ends the lease; screen on grants it again and republishes"; then
  if setup_or_fail; then
    fg grace 15000
    wait_bucket_refill
    t0="$(now)"; fg visibility off
    expect 'display-off report processed' 'glasses home visibility screenOn=false homeVisible=false' 10 "$t0"
    off_at="$(fmt_epoch "$MATCH")"
    expect 'lease ends after the 15 s grace' 'tile lease ended plugin=media' 30 "$t0" &&
      echo "      lease ended $(secs_between "$off_at" "$(fmt_epoch "$MATCH")") s after the display-off report"
    t1="$(now)"; fg media play "S6 Song" "S6 Artist" 200000
    expect_none 'no tile while the display is off' 'outbound /tile/publish' 6 "$t1"
    t2="$(now)"; fg visibility on
    expect 'lease granted again' 'tile lease granted plugin=media' 20 "$t2"
    expect 'plugin registers again' 'plugin registered package=com.anezium.rokidbus.plugin.media' 20 "$t2"
    expect 'tile with "S6 Song" after the display is back' "$(tile_re 'S6 Song')" "$TILE_WAIT" "$t2"
  fi
  finish "screen off"
fi

# ---- S7 -----------------------------------------------------------------------------------------------------
if begin S7 "phone battery badge keys on the display: held while off, resent on screen on"; then
  if setup_or_fail; then
    adbp_ shell dumpsys battery reset >/dev/null
    fg visibility off
    wait_for 'glasses home visibility screenOn=false homeVisible=false' 10 || { echo "    FAIL display-off report not processed"; scenario_fail=1; }
    t0="$(now)"; adbp_ shell dumpsys battery set level 37 >/dev/null
    expect_none 'no /phone/battery while the display is off' 'outbound /phone/battery' 6 "$t0"
    t1="$(now)"; fg visibility on
    expect 'battery badge resent with level 37 on screen on' 'outbound /phone/battery \{"level":37' 15 "$t1"
    adbp_ shell dumpsys battery reset >/dev/null
  fi
  finish "battery"
fi

# ---- S8 -----------------------------------------------------------------------------------------------------
if begin S8 "refresh contract: a visible edge past the 5 minute minimum republishes an unchanged track"; then
  if [ "$slow" = 1 ]; then
    if setup_or_fail; then
      lease_at=$SECONDS
      fg media play "S8 Song" "S8 Artist" 200000
      if expect 'tile with "S8 Song"' "$(tile_re 'S8 Song')" "$TILE_WAIT"; then
        key="$(sed -E 's/.*"contentKey":"([^"]*)".*/\1/' <<<"$MATCH")"
        fg visibility hidden; sleep 1
        t1="$(now)"; fg visibility on
        expect_none 'an early visible edge (under 5 minutes) does not republish' "outbound /tile/publish .*\"contentKey\":\"$key\"" 8 "$t1"
        # No edge before the minimum has passed since the lease began: the hub's last refresh is never older
        # than the lease delivery that came after it, so the edge below is due by then.
        while ((SECONDS - lease_at < 310)); do sleep 5; done
        fg visibility hidden; sleep 1
        t0="$(now)"; fg visibility on
        expect 'same track (contentKey '"$key"') republished on the visible edge after 5 minutes' "outbound /tile/publish .*\"contentKey\":\"$key\"" 15 "$t0"
      fi
    fi
    finish "refresh"
  else
    echo "  SKIP S8 (needs --slow)"; RESULTS+=("SKIP S8 refresh")
  fi
fi

# ---- S9 -----------------------------------------------------------------------------------------------------
if begin S9 "a grant change on a leased plugin rebinds it and its tile publishes resume"; then
  if setup_or_fail; then
    fg media play "S9 Before" "S9 Artist" 200000
    expect 'precondition: tile with "S9 Before"' "$(tile_re 'S9 Before')" "$TILE_WAIT"
    t0="$(now)"; fg approve "$MEDIA"
    expect 'lease fails with AUTHORIZATION_CHANGED' 'tile lease failed plugin=media reason=AUTHORIZATION_CHANGED' 15 "$t0"
    expect 'lease retried' 'tile lease retry plugin=media' 20 "$t0"
    expect 'plugin registers again' 'plugin registered package=com.anezium.rokidbus.plugin.media' 20 "$t0"
    t1="$(now)"; fg media play "S9 Song" "S9 Artist" 200000
    expect 'tile with "S9 Song" after the rebind' "$(tile_re 'S9 Song')" "$TILE_WAIT" "$t1"
  fi
  finish "grant change"
fi

echo; echo "=== summary"
fails=0
for r in "${RESULTS[@]}"; do echo "  $r"; [[ "$r" == FAIL* ]] && fails=$((fails + 1)); done
if ((fails)); then echo "$fails scenario(s) FAILED"; exit 1; fi
echo "all scenarios passed"
