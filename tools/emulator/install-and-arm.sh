#!/usr/bin/env bash
# Build (unless SKIP_BUILD=1), install the glasses hub debug APK, enable its accessibility
# service, start the hub and stay awake. Uses the hub's debug-only PROBE receiver.
set -euo pipefail
. "$(dirname "$0")/env.sh"

APK="$REPO_ROOT/glasses-hub/build/outputs/apk/debug/glasses-hub-debug.apk"
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  # The hub links the vendor CXR library: do NOT pass -PskipCxrGlobal=true here.
  (cd "$REPO_ROOT" && ./gradlew :glasses-hub:assembleDebug)
fi
[ -f "$APK" ] || { echo "missing $APK" >&2; exit 1; }
# The vendor CXR .so files are ARM-only; x86_64 emulators need the stubbed repack (X86_STUB=0 to skip).
if [ "${X86_STUB:-1}" = "1" ]; then APK="$("$(dirname "$0")/repack-x86-stub.sh")"; fi

adb_ uninstall "$PKG" >/dev/null 2>&1 || true   # signature may differ from an earlier install
adb_ install -r -g "$APK"
SVC="$PKG/$PKG.RokidBusAccessibilityService"
# Secure settings need WRITE_SECURE_SETTINGS; the shell user has it.
current="$(adb_ shell settings get secure enabled_accessibility_services | tr -d '\r')"
case "$current" in
  *"$SVC"*) ;;
  null|"") adb_ shell settings put secure enabled_accessibility_services "$SVC" ;;
  *) adb_ shell settings put secure enabled_accessibility_services "$current:$SVC" ;;
esac
adb_ shell settings put secure accessibility_enabled 1
adb_ shell svc power stayon true
adb_ shell locksettings set-disabled true >/dev/null 2>&1 || true
adb_ shell input keyevent KEYCODE_WAKEUP
adb_ shell wm dismiss-keyguard
adb_ shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 2
adb_ shell am broadcast -a "$PKG.PROBE" -n "$PKG/.ProbeBroadcastReceiver" --es probe hub
# MainActivity is portrait-locked and rotates the 480x640 display to 640x480 while it is in
# front; go home and pin rotation so overlays are captured at the real canvas.
adb_ shell input keyevent HOME
adb_ shell settings put system accelerometer_rotation 0
adb_ shell settings put system user_rotation 0
echo "armed; accessibility: $(adb_ shell settings get secure enabled_accessibility_services | tr -d '\r')"
