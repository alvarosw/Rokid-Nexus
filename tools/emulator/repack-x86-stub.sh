#!/usr/bin/env bash
# Produce $OUT_DIR/glasses-hub-debug-x86_64.apk: the debug APK plus a no-op x86_64
# libcxr-bridge-jni.so, re-signed with the local debug keystore. The vendor CXR library ships
# only arm64/armv7 .so files and the Cuttlefish image has no ARM translation, so without this
# the hub dies with UnsatisfiedLinkError in CXRServiceBridge.<clinit>. Emulation only.
set -euo pipefail
. "$(dirname "$0")/env.sh"

BT="${BUILD_TOOLS:-$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"/build-tools/* | sort -V | tail -1)}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
SRC="$REPO_ROOT/glasses-hub/build/outputs/apk/debug/glasses-hub-debug.apk"
OUT="$OUT_DIR/glasses-hub-debug-x86_64.apk"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$OUT_DIR" "$work/lib/x86_64"

JAVA_HOME_DIR="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
gcc -shared -fPIC -O1 -I"$JAVA_HOME_DIR/include" -I"$JAVA_HOME_DIR/include/linux" \
  -o "$work/lib/x86_64/libcxr-bridge-jni.so" "$(dirname "$0")/cxr-stub/libcxr-bridge-jni-stub.c"

python3 - "$SRC" "$work/unsigned.apk" "$work/lib/x86_64/libcxr-bridge-jni.so" <<'PY'
import sys, zipfile
src, dst, so = sys.argv[1:]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if not item.filename.startswith("META-INF/"):
            zout.writestr(item, zin.read(item.filename))
    zout.write(so, "lib/x86_64/libcxr-bridge-jni.so", compress_type=zipfile.ZIP_STORED)
PY
"$BT/zipalign" -f -p 4 "$work/unsigned.apk" "$work/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$work/aligned.apk"
echo "$OUT"
