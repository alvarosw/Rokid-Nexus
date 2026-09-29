# Emulating the glasses hub

Every glasses-hub UI change is validated on an Android device configured like the Rokid glasses.
The tooling lives in `tools/emulator/` and targets a Cuttlefish instance, but any adb device works.

## Canvas and density

- Display: **480 x 352 px**. This is the AIUI reference viewport (`RokidHudTokens.CANVAS_WIDTH/HEIGHT`,
  `docs/grid-hud-roadmap/00-overview.md`). Older notes mention 480x400 or 480x640; those are not
  the canvas to design for.
- Density: **240 dpi (hdpi, 1.5x)**, i.e. 320 x 234.67 dp. Evidence in the repo:
  - `GridLauncherView` history (`git show stash@{0}`): "a 4-column row of TILE_UNIT_DP tiles is wider
    than this display on the glasses (408dp vs. 320dp)" - 480 px / 320 dp = 1.5.
  - `InkWrappedTextLayoutTest` and `InkTemplateTortureTest` pin Robolectric to
    `w320dp-h427dp-hdpi` (480x640 px at hdpi).
  - Counter-evidence, not adopted: `GridLauncherScreenshotTest` uses `w480dp-h400dp-mdpi`
    (1 CSS px = 1 dp, matching the design mockup) and `HudPositionPreviewView.PANEL_DENSITY = 2f`.
    An mdpi canvas would make 96 dp tiles fit, contradicting the on-device overflow report.
  Density was never measured from a real unit; if it turns out different, set `GLASSES_DENSITY`.
- Portrait-locked activities (`MainActivity`, `SurfaceActivity`) rotate a 480x352 display to
  352x480 while they are in front. Overlays (launcher, surface overlay, notices) are windows and
  render at 480x352. `install-and-arm.sh` goes HOME and pins rotation so captures are 480x352.
  Whether the real panel is a 480x352 landscape window or a 480-wide portrait panel is unverified.

## Scripts

All honor `ADB_BIN` (default `/opt/cuttlefish/cf/bin/adb`), `SERIAL` (default `0.0.0.0:6520`),
`GLASSES_SIZE`, `GLASSES_DENSITY`, `OUT_DIR` (default `/tmp/nexus-emu`).

```
tools/emulator/setup-display.sh            # wm size 480x352, wm density 240 ("reset" restores)
tools/emulator/install-and-arm.sh          # build, repack, install, enable a11y service, start hub
SKIP_BUILD=1 tools/emulator/install-and-arm.sh
tools/emulator/ring.sh fwd|back|tap|dismiss|launcher|overlay|state
tools/emulator/capture.sh <name>           # PNG in $OUT_DIR
tools/emulator/capture.sh <name> 8         # 8 s screenrecord MP4
```

Ring mapping: `input keyevent` is injected above the accessibility key filter on the API 37 image, so
the hub never sees it. `ring.sh` therefore writes raw evdev events to the emulator keyboard
(`/dev/input/event4`, override with `KBD_DEV`): fwd = DPAD down, back = DPAD up, tap = ENTER,
dismiss = BACK. That is the touchpad/keyboard path of the hub; the real R08 media keycodes
(87/88/85) are only routed for an input device named `R08`, which the emulator cannot create.
`INPUT_MODE=inject` restores `input keyevent`. `launcher` broadcasts `OPEN_LAUNCHER` (it toggles).
`overlay` runs the debug `probe=surface-overlay` demo card.

The hubs link the vendor CXR library: build without `-PskipCxrGlobal=true` (the script does).

## The x86_64 CXR stub

The vendor `libcxr-bridge-jni.so` ships only for arm64/armv7 and the Cuttlefish image has
`ro.dalvik.vm.native.bridge=0` (no ARM translation), so the stock APK crashes on the first hub
start with `UnsatisfiedLinkError` in `CXRServiceBridge.<clinit>`. `repack-x86-stub.sh` compiles
`cxr-stub/libcxr-bridge-jni-stub.c` (no-op natives; `sendMessage` returns -1), adds it to a copy of the
debug APK and re-signs it with the debug keystore. Needs `gcc`, `python3`, and Android build-tools.
The product APK is untouched. Set `X86_STUB=0` on a device that can run ARM.

## What emulation validates

- Layout, sizing, typography, colors and clipping at 480x352 @ 240 dpi.
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
- Camera, media sync, self-arm and tiles are not scripted; tile/notice/activity envelopes can still be
  sent as plain envelopes.
