# Emulating the glasses hub

Every glasses-hub UI change is validated on an Android device configured like the Rokid glasses.
The tooling lives in `tools/emulator/` and targets a Cuttlefish instance, but any adb device works.

## Canvas and density

- Display: **480 x 640 px**: the official screen of the glasses (owner, from the official spec,
  2026-09-29; `RokidHudTokens.CANVAS_WIDTH/HEIGHT`, `HudGeometry`). The 480x352 / 480x400 figures used
  briefly on 2026-09-29 (AIUI reference viewport, panel size) are superseded.
- Density: **240 dpi (hdpi, 1.5x)**, i.e. 320 x 234.67 dp. Evidence in the repo:
  - The old grid launcher's on-device overflow report: a 4-column row of 96 dp tiles (408 dp with gaps)
    was wider than the glasses display (320 dp) - 480 px / 320 dp = 1.5.
  - `InkWrappedTextLayoutTest` and `InkTemplateTortureTest` pin Robolectric to
    `w320dp-h427dp-hdpi` (480x640 px at hdpi).
  - Counter-evidence, not adopted: `GridLauncherScreenshotTest` uses `w480dp-h400dp-mdpi`
    (1 CSS px = 1 dp, matching the design mockup) and `HudPositionPreviewView.PANEL_DENSITY = 2f`.
    An mdpi canvas would make 96 dp tiles fit, contradicting the on-device overflow report.
  Density was never measured from a real unit; if it turns out different, set `GLASSES_DENSITY`.
  The design tokens are physical pixels (safe-x 16, content 448), so layouts do not depend on it.
- Portrait-locked activities (`MainActivity`, `SurfaceActivity`) rotate a 480x640 display to
  640x480 while they are in front. Overlays (launcher, surface overlay, notices) are windows and
  render at 480x640. `install-and-arm.sh` goes HOME and pins rotation so captures are 480x640.

## Scripts

All honor `ADB_BIN` (default `/opt/cuttlefish/cf/bin/adb`), `SERIAL` (default `0.0.0.0:6520`),
`GLASSES_SIZE`, `GLASSES_DENSITY`, `OUT_DIR` (default `/tmp/nexus-emu`).

```
tools/emulator/setup-display.sh            # wm size 480x640, wm density 240 ("reset" restores)
tools/emulator/install-and-arm.sh          # build, repack, install, enable a11y service, start hub
SKIP_BUILD=1 tools/emulator/install-and-arm.sh
tools/emulator/ring.sh fwd|back|tap|double|dismiss|launcher|overlay|state
tools/emulator/capture.sh <name>           # PNG in $OUT_DIR
tools/emulator/capture.sh <name> 8         # 8 s screenrecord MP4
tools/emulator/dense-capture.sh <label> <evdev-code> [seconds]   # ~150 ms raw frames around one key press
tools/emulator/analyze-frames.py <frame-dir> [--home-ref F] [--from S] [--to S] [--fail-on-home]
tools/emulator/frame-sheet.py <frame-dir> <from-s> <to-s> <out.png> [scale]
```

Ring mapping: `input keyevent` is injected above the accessibility key filter on the API 37 image, so
the hub never sees it. `ring.sh` therefore writes raw evdev events to the emulator keyboard
(`/dev/input/event4`, override with `KBD_DEV`): fwd = DPAD down, back = DPAD up, tap = ENTER,
dismiss = BACK. That is the touchpad/keyboard path of the hub; the real R08 media keycodes
(87/88/85) are only routed for an input device named `R08`, which the emulator cannot create.
`INPUT_MODE=inject` restores `input keyevent`. `INPUT_MODE=hud` instead broadcasts each key to the
debug-only `DebugHudInputReceiver` (`DEBUG_HUD_INPUT`, `android.permission.DUMP`-protected; extras
`key`, `device` = `R08` (default) | `TOUCHPAD` | `KEYBOARD_DPAD` | `OTHER`, `action` = `press`
(default) | `down` | `up`, `repeat`), which hands a `RawKeyEvent` tagged with that device class to
`HudInputSeam.sink`, i.e. to the live `HudInput`. That is the only way to run the real R08 pipeline
(keycodes 85/87/88, the 350 ms tap window) in emulation. The service connects the seam when it connects
and clears it when it is destroyed. `ring.sh double` sends a ring double tap: two taps need two
broadcasts inside one 350 ms window, which two `adb` invocations cannot meet, so both leave one
device shell. Tap timing in this mode is wall-clock between broadcasts (each event is
stamped with the uptime clock on delivery). `launcher` broadcasts `OPEN_LAUNCHER` (it toggles).
`overlay` runs the debug `probe=surface-overlay` demo card.

### Frame analysis: is a Nexus window always in front?

`dense-capture.sh` loops `screencap` on the device (about 150 ms per frame, faster than `adb exec-out`
can) around one evdev key press and pulls the frames to `$OUT_DIR/dense-<label>/`, each named by its
offset from the press in seconds. `analyze-frames.py` classifies every frame as `home` (the ROM
wallpaper shows, so no Nexus window covers the screen) or `nexus` (anything else: black ground,
launcher, surface, image, mid-morph). Only those two classes exist: Nexus draws one green hue on
pure black, so a green panel or a colorful photo is never mistaken for the launcher. The wallpaper
is recognised by its navy ground (a slightly blue black over most of the screen; Nexus never draws
it); `--home-ref <frame>` with a raw frame taken on the home screen matches against that frame
instead and is the safer choice for flows that show photos. `--from/--to` bound the window that the
summary covers and `--fail-on-home` makes the exit status 1 when any frame in it is `home`, so a
tour can assert "0 frames without a Nexus window" for the stretch between the press and the end of a
morph. A Nexus notice floating over the wallpaper still counts as `home`: the check is for flows where
the host window is supposed to cover the screen. `frame-sheet.py` builds a contact sheet of a time
range (identical neighbours collapsed) for the report.

```
tools/emulator/dense-capture.sh list-open 28 4          # ENTER (evdev 28) with the launcher up
tools/emulator/analyze-frames.py /tmp/nexus-emu/dense-list-open --from 0 --fail-on-home
tools/emulator/frame-sheet.py /tmp/nexus-emu/dense-list-open -0.2 1.5 /tmp/nexus-emu/list-open.png
```

The hubs link the vendor CXR library: build without `-PskipCxrGlobal=true` (the script does).

## Slowing motion down and reduced motion

The host's animations honor `animator_duration_scale`: `0` is reduced motion (every transition lands on
its end state with no intermediate frame) and any other value multiplies the durations. A raw
`screencap` loop sees about one frame per 150 ms, so read a 220 ms morph at scale 6:

```
adb shell settings put global animator_duration_scale 6   # slow motion
adb shell settings put global animator_duration_scale 0   # reduced motion
adb shell settings put global animator_duration_scale 1   # normal; restore it when done
```

## The x86_64 CXR stub

The vendor `libcxr-bridge-jni.so` ships only for arm64/armv7 and the Cuttlefish image has
`ro.dalvik.vm.native.bridge=0` (no ARM translation), so the stock APK crashes on the first hub
start with `UnsatisfiedLinkError` in `CXRServiceBridge.<clinit>`. `repack-x86-stub.sh` compiles
`cxr-stub/libcxr-bridge-jni-stub.c` (no-op natives; `sendMessage` returns -1), adds it to a copy of the
debug APK and re-signs it with the debug keystore. Needs `gcc`, `python3`, and Android build-tools.
The product APK is untouched. Set `X86_STUB=0` on a device that can run ARM.

## What emulation validates

- Layout, sizing, typography, colors and clipping at 480x640 @ 240 dpi.
- Overlay windows via the real accessibility service: launcher, surface overlay, focus and dismiss.
- Key routing of the ring keycodes through the accessibility key filter, BACK handling.
- Crashes and logcat regressions in hub startup, overlays and controllers.

## What it cannot validate

- The real R08 ring: actual event timing, long-press/repeat behavior, touchpad gestures.
- Rokid ROM behavior: status bar/mode row (`HudTopInset`), the screen-position setting, standby and
  wake policy, the ROM's own launcher, the API 32 (Android 12L) framework (the emulator is API 37).
- CXR/SPP links: with the stub the hub sees no phone, so no plugin entries, tiles, surfaces from
  plugins, camera, media sync or self-arm flows.
- The optical see-through display: black is transparent on glasses, brightness/legibility outdoors,
  viewing distance.
- Performance on the glasses SoC (the host GPU renders this).

## Fake phone

Without a phone the launcher shows "Waiting for phone". `DebugFakePhoneReceiver` (debug source set
only, `android.permission.DUMP`-protected like `DebugInkBroadcastReceiver`) plays envelopes into
`GlassesHub.onRemoteEnvelope`, the entry point real SPP/CXR traffic uses, so launcher lists, surfaces
and the launcher mode arrive exactly as from a phone. Nothing about this ships in release builds.

```
tools/emulator/fake-phone.sh plugins8              # 8 plugins; each open answers with its surface
tools/emulator/fake-phone.sh plugins8-slow         # answers after 2.5 s; "tasker" never answers
tools/emulator/fake-phone.sh empty                 # empty launcher list
tools/emulator/fake-phone.sh hud-grid | hud-list   # launcher mode via /glasses/hud-mode/config
tools/emulator/fake-phone.sh --hud grid            # same, built by HudModeContract
tools/emulator/fake-phone.sh --envelope /launcher/list '{"plugins":[]}'
tools/emulator/fake-phone.sh my-envelope.json      # any envelope file, array, or scenario
tools/emulator/fake-phone.sh --reset               # forget the /launcher/open rules
```

A scenario name resolves to `tools/emulator/scenarios/<name>.json`; several can be given and play in
order. `fake-phone-build.py` flattens a scenario (`"@file:x"` inlines text, e.g. the compiled Ink
document; `"binaryFile"` becomes `binaryBase64` and fills `"@sha256"`) and the script pushes it to
`/data/local/tmp/nexus-fake-phone/`.

Script format (one JSON document):

```json
{
  "envelopes": [ {"path": "/launcher/list", "payload": {"plugins": []}, "delayMs": 0} ],
  "onOpen": {
    "lyrics": {"delayMs": 350, "envelopes": [ {"path": "/surface/show", "payload": {...}} ]},
    "tasker": {"never": true, "envelopes": []}
  }
}
```

An envelope may also stand alone or be an array. `delayMs` is relative to the previous step (or to
the open, for `onOpen`). `/surface/*` envelopes without a `seq` get a fresh monotonic one on every
delivery, as the phone hub would assign, so a rule can answer the same plugin repeatedly. Explicit
`seq`, `id` and `binaryBase64` (SPP binary frame body, needed by `image` surfaces) are honored.

### How `/launcher/open` is answered

The glasses send `/launcher/open` to the phone, and with no link `sendRemote` returns `NO_LINK`, which
makes the launcher stay open. The only change outside the debug source set is a null-by-default
`GlassesHub.outboundInterceptor` checked at the top of `sendRemote`. The receiver installs it the first
time a script carries `onOpen`; it consumes only `/launcher/open` (reported as sent), then replays that
plugin's rule after its delay. A plugin without a rule, or with `"never": true`, is consumed and never
answered, which exercises the open-timeout path. Every other outbound envelope still meets the real
(absent) link (`NO_LINK`), e.g. `Ink event send failed type=ready code=NO_LINK` in the log.

### Fixtures

`plugins8.json` uses built-in icon keys (`music`, `disc`, `map`, `bus`, `send`, `terminal`, `lens`,
`bolt`, all in `NexusPluginIcons`) and one surface kind per plugin: `lyrics` timed-lines, `media`
media, `nav` plain card, `transit` and `tasker` list cards (sub/tone/selected/badge), `relay` reader,
`agents` Ink (`scenarios/ink/agents.ink`, compiled with the opt-in harness in `ink-engine/README.md`
into `agents.document.json`), `lens` image (`assets/lens.png`, 320x200, under the 64 KiB cap).

### Unit test

`glasses-hub/src/testDebug/.../FakePhoneTest.kt` covers parsing, seq stamping, delays, binary bodies
and the open rules (`:glasses-hub:testDebugUnitTest`). It lives in `testDebug` because it needs the
debug-only classes.

### Known limits

- The receiver reads files under `/data/local/tmp`, like the Ink harness.
- `/surface/input` and other outbound traffic go nowhere; add rules only for `/launcher/open`.
- Camera, media sync, self-arm and tiles are not scripted; tile envelopes can still be sent as plain
  envelopes.

### Ambient layers

`/notice/*`, `/pin/*` and `/activity/*` envelopes without a `seq` get a fresh one, like `/surface/*`.
The `ambient-*` scenarios put one layer up or take it down:

```
tools/emulator/fake-phone.sh ambient-notice ambient-pin ambient-activity   # 45 s notice, top-right pin, top-left chip
tools/emulator/fake-phone.sh ambient-pointer                                # cursor; hides itself after 8 s
tools/emulator/fake-phone.sh ambient-notice-hide ambient-pin-hide ambient-activity-end ambient-pointer-hide
```

More states for reviewing the design system on the ambient layers: `ambient-notice-question` (three actions;
answering it on the emulator fails to send and shows the warn `Status`), `ambient-notice-info`,
`ambient-notice-long` (two pages), `ambient-notice-compose` (an inline-reply band over its editable card;
`ambient-notice-compose-hide` clears both), `ambient-pin-medium` (bottom-left; `-hide`),
`ambient-activity-extras` (route badge, measure and track; `-end`), `ambient-activity-two` (a second
resident next to `ambient-activity`), and on `ambient-activity`'s activity `ambient-activity-flare` and
`ambient-activity-urgent` (the critical flare: 2 px outline blinking three times, then steady).

The pointer stream id needs 16+ characters and a rising `sequence`; change `streamId` to replay it.
`adb shell dumpsys window windows` lists the overlays top to bottom (all named after the package); tell
them apart by frame and `fl=` flags: the host has no `NOT_FOCUSABLE`, the pointer has
`LAYOUT_NO_LIMITS`, the notice `KEEP_SCREEN_ON`, the pin is a small frame.
