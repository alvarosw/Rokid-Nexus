#!/usr/bin/env bash
# Install the phone hub debug APK and plugin debug APKs on the phone emulator (PHONE_SERIAL only), grant what
# the hub needs, allow Media Deck's notification listener and start the hub service.
#   install-phone.sh [--build] [plugin.apk ...]
# Default plugin: plugin-media debug. --build assembles the APKs first (serially; the hub WITHOUT
# -PskipCxrGlobal, the plugins with it). Needs start-phone.sh running; see docs/EMULATION.md.
set -euo pipefail
. "$(dirname "$0")/env.sh"

HUB_APK="$REPO_ROOT/phone-hub/build/outputs/apk/debug/phone-hub-debug.apk"
MEDIA_APK="$REPO_ROOT/plugins/media/build/outputs/apk/debug/plugin-media-debug.apk"
MEDIA_LISTENER="com.anezium.rokidbus.plugin.media/com.anezium.rokidbus.media.session.MediaDeckNotificationListenerService"

build=0
plugins=()
for arg in "$@"; do
  case "$arg" in
    --build) build=1 ;;
    *.apk) plugins+=("$arg") ;;
    *) echo "usage: $0 [--build] [plugin.apk ...]" >&2; exit 2 ;;
  esac
done
[ "${#plugins[@]}" -gt 0 ] || plugins=("$MEDIA_APK")

if [ "$build" = 1 ]; then
  cd "$REPO_ROOT"
  ./gradlew :phone-hub:assembleDebug
  ./gradlew :plugin-media:assembleDebug -PskipCxrGlobal=true
else
  echo "not building; run with --build, or: ./gradlew :phone-hub:assembleDebug && ./gradlew :plugin-media:assembleDebug -PskipCxrGlobal=true"
fi
for apk in "$HUB_APK" "${plugins[@]}"; do [ -f "$apk" ] || { echo "missing $apk" >&2; exit 1; }; done

# The vendor CXR .so files are ARM-only and the phone AVD is x86_64 without ARM translation: install the
# stubbed repack (X86_STUB=0 on a device that runs ARM). The hub then sees no CXR link, which is what the harness wants.
if [ "${X86_STUB:-1}" = "1" ]; then HUB_APK="$(STUB_MODULE=phone-hub "$(dirname "$0")/repack-x86-stub.sh")"; fi

adbp_ get-state >/dev/null || { echo "no device $PHONE_SERIAL; run tools/emulator/start-phone.sh" >&2; exit 1; }
adbp_ install -r -g "$HUB_APK"
for apk in "${plugins[@]}"; do adbp_ install -r -g "$apk"; done

# -g covers what the manifests declare; the hub refuses to run without BLUETOOTH_CONNECT on API 31+.
adbp_ shell pm grant "$PHONE_PKG" android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
adbp_ shell pm grant "$PHONE_PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adbp_ shell svc power stayon true
adbp_ shell input keyevent KEYCODE_WAKEUP
adbp_ shell wm dismiss-keyguard

if printf '%s\n' "${plugins[@]}" | grep -q 'media'; then
  # A listener allowed right after the install is not always bound; toggling it makes the system bind it.
  adbp_ shell cmd notification disallow_listener "$MEDIA_LISTENER" >/dev/null 2>&1 || true
  sleep 1
  adbp_ shell cmd notification allow_listener "$MEDIA_LISTENER"
  sleep 2
  adbp_ shell dumpsys notification | grep -q "Live notification listeners" && \
    adbp_ shell dumpsys notification | grep -A8 "Live notification listeners" | grep -q MediaDeckNotificationListenerService || \
    echo "warning: Media Deck's notification listener is not bound" >&2
fi

adbp_ shell am start-foreground-service -n "$PHONE_PKG/.BusHubService" >/dev/null
sleep 3
echo "hub service: $(adbp_ shell dumpsys activity services "$PHONE_PKG" | grep -c 'ServiceRecord.*BusHubService') running"
