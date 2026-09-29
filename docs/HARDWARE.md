# Rokid glasses hardware and ROM facts (for the glasses-UI rewrite)

Purpose: one place for everything the glasses-side UI learned the hard way about the Rokid glasses,
the R08 ring and the Rokid ROM (YodaOS, Android 12L / API 32). A rewrite of `glasses-hub`'s UI
(launcher list/grid, plugin surfaces, notice band, pin, activity, status badge, pointer overlays and
the input routing between them) has to address each of these deliberately.

Compiled 2026-09-29 from `glasses-hub/src/main`, `bus-client/.../client/ui`, `README.md`, `BUSSPEC.md`,
`CHANGELOG.md`, `TESTPLAN.md`, `docs/`, `plans/`, `contracts/`, `plugins/nav/VALIDATION.md`, the
untracked `tools/emulator/` scripts and the (read-only) stash `stash@{0}`. Nothing here was run on a
device or emulator by the author of this file; every claim is traceable to code or a document.

## How to read this file

- **Mandatory fact: the glasses display is 480x352 px** (confirmed by the product owner, 2026-09-29).
  Section 1 lists every place where code, docs, tests and screenshots disagree with that number.
- Path shorthand: `G/` = `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/`,
  `BC/` = `bus-client/src/main/java/com/anezium/rokidbus/client/ui/`. Line numbers are for the working
  tree at compile time (`main`, `fa9c7715`). Evidence marked `stash@{0}` exists only in the stash
  (`pre-upstream-sync: grid launcher WIP patches`), never popped or dropped.
- Each fact: **Fact**, **Evidence**, **Current handling**, **Requirement** (phrased as a testable
  rule) and **Emulable?** (yes / partially / no, and how, on an Android emulator such as Cuttlefish).
- "(inferred)" = deduced from code or arithmetic, not stated by any source. "(unverified)" = a claim
  in a document that nothing in the repo measures.
- The Cuttlefish scripts in `tools/emulator/` (untracked, dated 2026-09-29) already exist:
  `env.sh` (adb at `/opt/cuttlefish/cf/bin/adb`, serial `0.0.0.0:6520`, `GLASSES_SIZE=480x352`,
  `GLASSES_DENSITY=240`), `setup-display.sh` (`wm size` / `wm density`), `install-and-arm.sh`
  (enables the accessibility service through `settings put secure`, `svc power stayon true`,
  `input keyevent KEYCODE_WAKEUP`), `ring.sh` (`input keyevent 85/87/88/4`), `capture.sh`.
  Emulation recipes below build on those.

Fact counts per section: see the table at the end of the file ("Summary of counts").

---

## 1. Display and canvas

### 1.0 Where sources disagree (read first)

| Topic | What each source says |
|---|---|
| Visible size | Owner (2026-09-29): **480x352**. `BC/RokidHudTokens.kt:92-93` `CANVAS_WIDTH=480`, `CANVAS_HEIGHT=352`. `docs/grid-hud-roadmap/00-overview.md:85` says 480x400 is the *physical* display and 480x352 only the "AIUI reference viewport". `docs/AIUI_RUNTIME.md:13`: page host observed "at 480x640 and 480x400". |
| Android window space | 480x640 px at density 1.5 on real RG glasses: `G/StatusBadgeGeometry.kt:7-8`, `glasses-hub/src/test/.../InkTemplateTortureTest.kt:31` (`w320dp-h427dp-hdpi`), `contracts/2026-08-16-lyrics-home-widget.contract.md:158` ("480x640 framebuffer"), `BUSSPEC.md:2264` ("nominal 480x640 display"), every activity `android:screenOrientation="portrait"` (`glasses-hub/src/main/AndroidManifest.xml`), all 32 untracked screenshots in the repo root (`afterback1.png` ... `wrapper_v3.png`) are 480x640. |
| Roborazzi baseline | `glasses-hub/src/test/.../GridLauncherScreenshotTest.kt:32-37`: `w480dp-h400dp-mdpi` (density 1.0, 480x400 px), commented as "the HUD's real physical canvas". |
| Density | 1.5 (240 dpi): `StatusBadgeGeometry.kt:8`, `InkTemplateTortureTest` hdpi, `stash@{0}` GridLauncherView comment (480 px is 320 dp), `tools/emulator/env.sh` (240). 2.0: `phone-hub/.../HudPositionPreviewView.kt:196,200` (`PANEL_DENSITY = 2f`, "480x640 px at 2x density"). 1.0: the Roborazzi test. |
| Token unit | Design doc: `safe-x` 16 **px**, `content-width` 448 **px** on the 480 canvas (`00-overview.md:85`). Code: the same numbers are used as **dp** (`BC/RokidHudTokens.kt:64-66,122-123`, `BC/HudFrameLayout.kt:24-26`), i.e. 24 px per side at 1.5 -> 432 px content width. |
| Tile unit | Working tree `G/GridLauncherView.kt:105` `TILE_UNIT_DP = 96`, 4 columns (`shared/.../tile/TileGridPacker.kt:13`): 4x96 + 3x8 = 408 dp, wider than the 320 dp screen. `stash@{0}` fixes it by fitting the unit to the width. |
| Screen timeout | See 2.1: "forced 5000 ms at every boot" vs "the wearer's own setting" vs "the ROM never turns the display off by itself". |
| Two greens | `BC/BusTheme.kt:30` `#71FF97` (launcher list, notices, most surfaces) vs `BC/RokidHudTokens.kt:23` `#40FF5E` (grid HUD only). |
| Durations | `BC/RokidHudTokens.kt:84-88` 120/200/**320**/6000/1200 ms (stash: structural **220**) vs `G/HudMotion.kt` 180/280/240 ms. |

### 1.1 Facts

**D1. The display hardware is 480x400; the OS exposes a 480x352 px screen (owner, 2026-09-29).**
- **Resolved**: the OS screen is the full 480x352 with no lit-rows offset, so the visible viewport is that screen at origin 0,0 (`HudGeometry`). The 480x640 window evidence in D2 and Q1 came from an earlier firmware/configuration and is **superseded**; it is kept below as history.
- **Fact**: Owner-confirmed on 2026-09-29. The design canvas in code already equals it.
- **Evidence**: task statement; `BC/RokidHudTokens.kt:90-93`.
- **Current handling**: only the grid-HUD token object knows it; the list launcher, notice band, pin, activity and surface hosts size from `displayMetrics` (D2) with fractions/margins tuned on device.
- **Requirement**: the new UI defines one `DisplayGeometry` (visible width 480 px, height 352 px) and every layout budget (safe area, grid, band, pin/activity corners, launcher) derives from it. A render test at exactly 480x352 must show no element crossing any edge and the bottom row fully inside.
- **Emulable?**: yes. `wm size 480x352; wm density 240` (`tools/emulator/setup-display.sh`).

**D2. (Superseded by D1: earlier firmware/configuration.) Android exposes a 480x640 px window space at density 1.5 (240 dpi) on the real glasses.**
- **Fact**: displayMetrics on the RG glasses read 480x640 px @1.5 (320x427 dp); the hub is portrait-only. How this relates to the 352 visible rows is not documented anywhere (open question Q1).
- **Evidence**: `G/StatusBadgeGeometry.kt:7-8` ("RG-glasses (480x640 @1.5) on 2026-07-28"); `InkTemplateTortureTest.kt:31`; `contracts/2026-08-16-lyrics-home-widget.contract.md:158`; `BUSSPEC.md:2264`; `G/InkCardPresentation.kt:10-22` (`HudBandGeometry.availableHeightPx(displayHeightPx, topPx)`), tested with 640 in `InkCardPresentationTest.kt:27-30`.
- **Current handling**: `NoticeOverlayRenderer` (max band height 0.65 / 0.92 of `resources.displayMetrics.heightPixels`, `:491-496,877-878`), pointer geometry (`RemotePointerOverlayRenderer.kt:66-73`), `InkCardClipHost` and Camera (480x640) all scale from `metrics.heightPixels`, i.e. 640, not 352.
- **Requirement**: no component may use `heightPixels` (or a literal 640/400) as "the screen height" until Q1 is answered. Provide the visible rect from one place and run every geometry test at 480x352, 480x400 and 480x640 (with `HudTopInset` applied) until the mapping is known; bands and cards must never be positioned by a fraction of 640.
- **Emulable?**: yes. `wm size 480x640`, `wm density 240`; only the optics question needs the device.

**D3. The ROM draws its own UI far below row 352 in that window space.**
- **Fact**: The ROM home status container spans y 306..400 (icons y 355..375, clock/weather left, wifi/battery right); the hint zone `no_plan_tips` is y 288..310; in-launcher app screens (teleprompter) relocate the container to y 466..560. (Measured 2026-07-28 and 2026-08-16.)
- **Evidence**: `G/StatusBadgeGeometry.kt:9-26`; `contracts/2026-08-16-lyrics-home-widget.contract.md:162-164`.
- **Current handling**: `StatusBadgeOverlayRenderer` reads the live node bounds every time (never a constant) and hides when it cannot find them (`:164-167`, `:73-76` of its doc).
- **Requirement**: the new UI never hard-codes ROM row positions; anything that must align with the ROM row reads the node bounds fresh, per layout, and hides (does not guess) when the row cannot be read. Test: with the fake ROM launcher (D6) moved between home and teleprompter layouts, the chip follows and disappears when the ids vanish.
- **Emulable?**: partially. A test-only app with package `com.rokid.os.sprite.launcher`, view ids `status_time_tv`, `status_weather_iv/tv`, `status_power_iv` at chosen bounds, set as HOME, reproduces the reads; it cannot reproduce ROM timing.

**D4. The wearer can shift the ROM screen position; Nexus must follow.**
- **Fact**: Hi Rokid lets the wearer raise/lower the virtual screen; the ROM row moves with it. Nexus derives a top inset in dp: `(rowCentreY - 364) / density`, where 364 px is the home-row centre with the ROM setting fully UP (calibrated 2026-08-06); a change under 2 dp is ignored; range 0..120 dp, default 0; a manual override exists.
- **Evidence**: `G/HudTopInset.kt:17-19,49-69`; `shared/.../PhoneHubCapabilitiesContract.kt:17-20,60-61`; `CHANGELOG.md` 1.2.4; `G/StatusBadgeOverlayRenderer.kt:168-174` (sampled only when the ROM weather node is visible, i.e. the home layout).
- **Current handling**: launcher list/grid, notice band, ink card, surfaces and status-badge subscribe (`HudTopInset.observe`) and re-pad; band top = 12 dp + inset (`InkCardPresentation.kt:14-16`).
- **Requirement**: one inset source, consumed by every root; changing it 0 -> 60 dp moves every top-anchored element by exactly 60 dp without recreating windows; samples taken from non-home ROM layouts must be ignored. Test with hysteresis (1 dp change ignored, 2 dp accepted).
- **Emulable?**: partially. The inset is pure app state (phone capabilities/prefs) and can be driven directly; the auto measurement needs the fake ROM launcher (D3).

**D5. Density: design and tests must use 240 dpi.**
- **Fact**: Real density is 1.5 (see 1.0). Sizes given in "px" in design docs (16, 12, 448) are applied as dp by the tokens, giving 24/18 px insets and a 432 px content width, not 16/12/448.
- **Evidence**: 1.0 table; `BC/RokidHudTokens.kt:122-123`; `BC/HudFrameLayout.kt:23-27`; `G/StatusBadgeGeometry.kt:54-56` (20 px icons = 13 dp glyph at this density).
- **Current handling**: mixed: dp for tokens, px literals in some ROM-alignment code, mdpi in one Roborazzi test.
- **Requirement**: decide the unit of every token (dp or px) in one place; snapshot tests run at `w320dp-h235dp-hdpi` (480x352 px @1.5) and assert pixel sizes (e.g. safe inset = 24/18 px if dp, 16/12 px if px). No test may use `mdpi` for glasses geometry.
- **Emulable?**: yes. `wm density 240`; Robolectric qualifier `w320dp-h235dp-hdpi`.

**D6. Platform identity: Android 12L / API 32, YodaOS, firmware `SKQ1.240613.001`.**
- **Fact**: Glasses hub targets API 32 (READ_EXTERNAL_STORAGE is still the media permission); ROM launcher package `com.rokid.os.sprite.launcher`; assist server `com.rokid.os.sprite.assistserver`; system config `com.rokid.sysconfig`; ROM `Settings` on affected YodaOS builds leaves the Developer-options list 4 px high (`636..640`).
- **Evidence**: `glasses-hub/src/main/AndroidManifest.xml:20-23`; `docs/AIUI_RUNTIME.md:3`; `G/StatusBadgeOverlayRenderer.kt:80`; `G/AccessibilityWindowRoots.kt:12`; `G/SelfArmAccessibilityScrollStrategy.kt:76-79`.
- **Current handling**: package names are string literals scattered across renderers and policies.
- **Requirement**: ROM package names, view ids and known quirks live in one `RomContract` object with tests; the UI compiles and runs against API 32 APIs only.
- **Emulable?**: partially. Use an API 32 image; ROM packages need stand-in apps.

**D7. Grid tile unit must be derived from available width.** (stash@{0})
- **Fact**: 480 px is about 320 dp; four 96 dp tiles plus gaps are 408 dp, so a fixed unit ran columns off the right edge. Available width after safe padding is 288 dp -> unit = (288 - 3x8) / 4 = 66 dp (99 px) (inferred arithmetic).
- **Evidence**: `stash@{0}` `G/GridLauncherView.kt` hunk "The tile unit is derived from the available width"; `G/GridLauncherView.kt:105` (working tree still `96`); `shared/.../tile/TileGridPacker.kt:13`.
- **Current handling**: working tree: fixed 96 dp (bug); stash: `min(TILE_UNIT_DP, fitted)` recomputed in `onSizeChanged`.
- **Requirement**: for every column count the sum of tile widths + gaps <= available width at 480x352 @240; no tile may be clipped by the right/bottom edge. Test with 1..4 columns and the largest tile spans.
- **Emulable?**: yes.

**D8. Ghost/panel geometry double-counts padding.** (stash@{0})
- **Fact**: `offsetDescendantRectToMyCoords` already returns hudRoot's padded coordinate space; using the result as a margin on a child of the same padded root adds the padding twice and pushes the expanded panel past the safe area.
- **Evidence**: `stash@{0}` `G/GridLauncherView.kt` (`marginSpace`, `safeAreaWidth/Height`).
- **Current handling**: working tree double-counts; stash strips it.
- **Requirement**: an open/close transition's start and end rects are computed in one coordinate space; test that the expanded panel's rect equals the safe area exactly (right/bottom edges not beyond `width - paddingRight`).
- **Emulable?**: yes.

**D9. The panel refreshes at 60 Hz.**
- **Fact**: Overlay windows of type accessibility-overlay reach 59.5-60 fps, frame interval 16.71 ms, in the real accessibility overlay over the ROM home (glasses hub 1.0.44).
- **Evidence**: `plans/013-hud-motion.md:86-97`.
- **Current handling**: motion vocabulary sized to that; Ink recurring redraw capped at 30 fps (`G/InkFrameGate.kt:29,51`).
- **Requirement**: animation code uses `Choreographer`/animators only (no fixed sleeps); jank test at 60 Hz budget for open/close of the grid.
- **Emulable?**: partially. Frame timing on Cuttlefish is software-rendered; use relative budgets only.

---

## 2. Power, sleep, screen timeout and wake

**P1. The ROM sleeps the display about 5 s after the last input.**
- **Fact**: Logs show `Brightness reason changing to: 'manual [ dim ]'` then `'screen_off'` and `SurfaceFlinger Setting power mode 0` while an app window is up; the firmware forces `screen_off_timeout=5000` and re-asserts it at every boot. Conflicting statements exist (see Evidence).
- **Evidence**: `G/NoticeOverlayRenderer.kt:30-38` ("vendor-set `screen_off_timeout`"); `G/SurfaceController.kt:706-709` ("these glasses' 5 s screen timeout"); `contracts/2026-08-10-assistant-episode-hold.contract.md:145-146`, `...ink-hold-handover.contract.md:285-287`, `...assistant-wake-hold.contract.md:168-178` (log); `contracts/2026-08-16-lyrics-home-widget.contract.md:160`. Conflicts: `plans/016-display-wake.md:70-73` (the timeout is the wearer's own setting: 5/10/20 s, always on/off); `CHANGELOG.md` 1.2.6 ("the glasses' system never turns the display off by itself").
- **Current handling**: assistant episode holds a wake lock (P4); notices ask a 3 s wake (P3); everything else relies on windows flags (P2) or on the wearer's input.
- **Requirement**: the new UI assumes any presentation that lives longer than 5 s with no input may go dark; each presentation declares one of {never holds, holds via lock, may go dark}. With `screen_off_timeout=5000`, a launcher/surface that declares "holds" is still interactive after 15 s idle (checked via wakefulness).
- **Emulable?**: yes for the timeout: `settings put system screen_off_timeout 5000`, `svc power stayon false`, `dumpsys power | grep -E "mWakefulness|Wake Locks"`. The ROM re-asserting it at boot: no.

**P2. `FLAG_KEEP_SCREEN_ON` does not stop this panel; only a wake lock does.**
- **Fact**: Measured on this firmware: with the window flag on the notice and surface windows the panel still switched off; a `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` lock (`decision=wake` followed by `power mode 2`) does control it.
- **Evidence**: `contracts/2026-08-10-assistant-wake-hold.contract.md:160-185`; `...assistant-episode-hold.contract.md:142-145`; `...ink-hold-handover.contract.md:216-219`; `G/NoticeOverlayRenderer.kt:33-38`.
- **Current handling**: the flag is still set on the launcher overlay (`G/LauncherOverlayRenderer.kt:95-96`), plugin-surface overlay (`G/SurfaceOverlayRenderer.kt:44-45`), `SurfaceActivity.kt:20`, `CameraActivity.kt:78` and the notice window (`NoticeOverlayRenderer.kt:39-42`). Only the assistant episode adds a lock. Whether the launcher/camera survive >5 s of idle is not recorded (Q3).
- **Requirement**: never rely on the flag for correctness. Every "keeps the display awake" behavior goes through one hold API and is asserted by the presence of a named wake lock (`rokidbus:*`) in `dumpsys power`, not by a window flag.
- **Emulable?**: partially. AOSP honors the flag, which hides this bug; build a test variant that strips the flag and assert on `dumpsys power` wake locks.

**P3. Global wake budget for one-shot wakes.**
- **Fact**: at most one wake per 5 s across all plugins and kinds; lock is 3 s; nothing is spent when the screen is already interactive; a new notice may wake inside a hot budget up to 2 times per unattended episode (reset after 60 s or after any user key DOWN/nav); a plugin can never raise, reset or read it.
- **Evidence**: `G/DisplayWakePolicy.kt:67-70,74-131,135-143`; `plans/016-display-wake.md:56-83`; `CHANGELOG.md` 1.2.8.
- **Current handling**: `DisplayWakePolicy.requestWake` on every surface show (`G/SurfaceController.kt:468,535,583`), notice show (`NoticeController.kt:1160,1211`), activity significant update (`ActivityController.kt:626`), remote navigation/pointer (`RemoteNavigationController.kt:94-98`, `RemotePointerController.kt:117`).
- **Requirement**: one wake decision function, no other code path acquires a tier wake lock; property tests over (kind, requested, interactive, budget, newNotice). A wake refused as ALREADY_INTERACTIVE must not consume budget.
- **Emulable?**: yes. `input keyevent 223` (sleep) then send a surface/notice; `dumpsys power` shows the lock; timing checks via `adb logcat -s ROKIDBUS`.

**P4. Assistant episode hold (renewable wake lock) spans band -> card -> follow-up.**
- **Fact**: One owner acquires once at the first engaged notice and releases once at the end; band updates and hub redraws never extend it, only a follow-up question or a shown answer re-arms the 90 s ceiling; release on wearer dismiss, session close, non-assistant surface, other plugin's engaged notice, link loss, service destroyed, renderer error, ceiling. Handing the hold between owners at the band->card morph died on device (panel off 18 ms into the morph).
- **Evidence**: `G/AssistantDisplayEpisode.kt:9-58` (owners/reasons), `:373` (`DISPLAY_HOLD_CEILING_MS = 90_000`), `:406` (lock); `contracts/2026-08-10-ink-hold-handover.contract.md:195-218`; `contracts/2026-08-10-assistant-episode-hold.contract.md:57-82`.
- **Current handling**: implemented and logged as `ROKIDBUS hold seq=... decision=acquire|renew|release`.
- **Requirement**: the hold owner is not a view; it survives any window swap. Test: band -> Ink card -> follow-up band -> card without a release between; release exactly once per end reason; a plugin opened from the launcher never holds.
- **Emulable?**: yes for state and lock presence (`dumpsys power`); the panel actually dying: no.

**P5. Display standby watchdog puts the display to sleep itself.**
- **Fact**: because a lit-but-idle display costs about a quarter of the battery per hour, the hub locks the screen (`GLOBAL_ACTION_LOCK_SCREEN`) after 3 minutes of continuous idleness on battery when the top window is the ROM home (weather node present) or Nexus main, and nothing is presenting; 16 blockers; evaluated every 45 s; every gate is re-read at the irreversible edge; unknown state blocks; STATUS_FULL counts as charging only while plugged.
- **Evidence**: `G/DisplayStandbyPolicy.kt:74,108-131`; `G/DisplayStandbyWatchdog.kt:111-133,141-160,191-201,215`; `CHANGELOG.md` 1.2.6; `G/DisplayStandbyWatchdog.kt:154-155` (STT has no glasses-side edge).
- **Current handling**: reads `SurfaceController.activeSurface()`, `NoticeController.activeNotice()`, `ActivityController.isPresenting()`, camera tracker, `LauncherOverlayRenderer.isShown()`, `MainActivity.isInteractiveFlowActive()`, TTS, setup and media-sync state.
- **Requirement**: every new UI state exposes an `isPresenting()` to this watchdog; a unit test lists each presentation and asserts it blocks standby. Unknown -> blocked.
- **Emulable?**: partially. `GLOBAL_ACTION_LOCK_SCREEN` and battery states (`dumpsys battery set ac 0`, `set status 3`) work; the ROM-home detection needs the fake ROM launcher (D3).

**P6. A notice that woke a dark display puts it back to sleep, with settle handling.**
- **Fact**: after USER/TIMEOUT close, if the notice episode owns the wake and nothing else is presenting (launcher, surface, assistant episode, activity, camera), the hub locks the screen; a new notice arriving while the lock is settling (screen still interactive for up to 2 s) is retried every 75 ms until SCREEN_OFF is seen; a message landing while a previous band was still fading (~250 ms) was once lost; SCREEN_OFF from any cause clears the episode.
- **Evidence**: `G/NoticeSleepPolicy.kt:27-54`; `G/NoticeController.kt:1160-1225,1251-1289,1291-1300,1320-1321`; `CHANGELOG.md` 1.2.8.
- **Current handling**: as described.
- **Requirement**: two notices 2 s apart over a locking display both become visible (second one relights); a stale episode never sleeps a display the wearer woke.
- **Emulable?**: yes. `input keyevent 223/224`, `dumpsys power`, logcat `notice display sleep`.

**P7. The manual pairing dialog needs the display held.**
- **Fact**: the display going dark dismisses Android's pairing dialog and cancels the pairing; the flow holds a 5-minute `SCREEN_BRIGHT` lock released on close.
- **Evidence**: `G/GlassesHub.kt:996-1002,1316-1327,1640`; `plans/016-display-wake.md:118-123`.
- **Current handling**: own lock, deliberately outside the wake policy.
- **Requirement**: the rewrite must leave this lock untouched; setup screens are outside the "no plugin may hold" rule.
- **Emulable?**: yes for lock presence.

**P8. A key press that wakes a dark display is eaten.**
- **Fact**: keys injected while the display sleeps are consumed as wake events; when the glasses are not worn the display sleeps and focus can land on an invisible `MockWindow`, so `adb input` never reaches the activity.
- **Evidence**: `TESTPLAN.md:439-441,474-478`.
- **Current handling**: none in UI; tests and remote navigation call a wake first (`RemoteNavigationController.kt:94-98`).
- **Requirement**: the first input after a SCREEN_ON is not interpreted as a gesture by any tap/triple-tap state machine (reset detectors on SCREEN_ON/OFF). Test: SCREEN_OFF -> SCREEN_ON clears tap counters and pending ring taps.
- **Emulable?**: partially. Sending `input keyevent 224` then an immediate key approximates; physical eat behavior is ROM-specific (Q13).

**P9. Screen on/off broadcasts are the truth source, and several components keep their own copy.**
- **Fact**: Ink gating listens to `ACTION_SCREEN_OFF/ON`, the notice controller to SCREEN_OFF, the watchdog arms only on SCREEN_ON. On SCREEN_ON the Ink layout metrics are invalidated because bounds measured while powering on are wrong.
- **Evidence**: `G/SurfaceController.kt:62-74,756-771`; `G/NoticeController.kt:578-583,1291-1300`; `G/DisplayStandbyWatchdog.kt:28-35`; `G/InkPresentationGate.kt:39-43`.
- **Current handling**: three separate receivers.
- **Requirement**: one `DisplayState` observable (interactive, transitioning) that every UI piece reads; test that a SCREEN_ON during Ink layout re-measures before first frame.
- **Emulable?**: yes. `input keyevent 223/224` produce the broadcasts.

**P10. Hi Rokid's own notifications light the screen: the ROM allows apps to wake it.**
- **Fact**: checked on hardware 2026-07-28; the earlier rule "never wake" was self-imposed. Keeping the display on for a plugin stays forbidden.
- **Evidence**: `plans/016-display-wake.md:24-37,170-175`; `plans/012-activities.md:387-395`.
- **Current handling**: wake only through P3; plugins cannot hold.
- **Requirement**: no new UI path may call `PowerManager` directly except the hold/wake owners.
- **Emulable?**: yes (grep + wake-lock assertion).

---

## 3. Ring (R08) input

**R1. The ring is recognized by input device name.**
- **Fact**: `event.device?.name?.uppercase()` contains `"R08"` selects the ring path; everything else (touchpad, keyboards, injected keys) takes the generic path.
- **Evidence**: `G/RokidBusAccessibilityService.kt:153-157`.
- **Current handling**: hard-coded in `onKeyEvent`.
- **Requirement**: one `InputSource` classifier (ring / touchpad / keyboard / unknown) with unit tests for null device, unknown names and "R08" variants; routing tables are per source.
- **Emulable?**: partially. Plain `adb shell input keyevent` has no R08 device name, so `tools/emulator/ring.sh` (85/87/88) likely reaches the generic path, where 85/87/88 are unclaimed (inferred; not run). To exercise the ring path create a uinput device named `R08...` on a rooted image (`sendevent`/uinput helper) or add a debug seam.

**R2. Ring keycodes.**
- **Fact**: tap = 85 (`KEYCODE_MEDIA_PLAY_PAUSE`), forward swipe/scroll = 87 (`MEDIA_NEXT`), backward = 88 (`MEDIA_PREVIOUS`). The ring's triple tap that opens the launcher is done by the separate R08 Access Bridge app, not by Nexus (`OPEN_LAUNCHER` broadcast).
- **Evidence**: `G/RingSurfaceInputPolicy.kt:54-56`; `G/LauncherOverlayRenderer.kt:41-43`; `tools/emulator/ring.sh:7-10`; `CHANGELOG.md` 1.0.41 (R08 section); `G/OpenLauncherReceiver.kt:7-18`.
- **Current handling**: literals duplicated in three files.
- **Requirement**: one constant set; contract test that 85/87/88 map to tap/forward/backward in every consumer; OPEN_LAUNCHER toggles (shows if hidden, hides if shown).
- **Emulable?**: yes for the broadcast (`am broadcast -a ...action.OPEN_LAUNCHER`); keys see R1.

**R3. The ring sends DOWN/UP pairs; the UP must be consumed whenever the DOWN was.**
- **Fact**: the UP frequently arrives after the owner (notice, launcher entry) is gone; a stray ENTER UP that reaches the ROM launcher starts phone music playback. Notices additionally track consumed presses by (deviceId, keyCode, downTime).
- **Evidence**: `G/RokidBusAccessibilityService.kt:28-32,220-222,226-231,262`; `plans/011-notice-surface.md:131-137`; `BUSSPEC.md:1289-1291`; `G/NoticeKeyDispatcher.kt:36-58`.
- **Current handling**: `consumedDownKeys` set + per-notice `consumedPresses` map.
- **Requirement**: a single input pipeline records "DOWN consumed by X" and consumes the matching UP even if X is dead (test: DOWN consumed, owner removed, UP delivered -> swallowed and never reaches the fake ROM launcher).
- **Emulable?**: partially. Needs separate DOWN and UP with a delay (`sendevent`), plus a fake HOME app counting `onKeyUp`.

**R4. Ring repeat and non-DOWN events are swallowed; only repeatCount 0 acts.**
- **Fact**: when a Nexus owner is active every ring key is consumed; only DOWN with repeatCount 0 changes state; others return true silently.
- **Evidence**: `G/RokidBusAccessibilityService.kt:239-263`.
- **Current handling**: as stated.
- **Requirement**: holding a ring key never repeats an action; test with repeatCount 1..5.
- **Emulable?**: partially (`input keyevent --longpress`, or sendevent).

**R5. Ring tap windows: single = 350 ms of silence, double = dismiss, 3+ ignored.**
- **Fact**: taps within 350 ms of the previous accumulate; the resolution fires 351 ms after the last tap: 1 -> confirm (ENTER), 2 -> BACK/dismiss, >=3 -> ignore. So a single tap costs at least 350 ms latency.
- **Evidence**: `G/RingTapPolicy.kt:38` and `:17-30`; `G/RingSurfaceInputPolicy.kt:36-38`; timers `G/LauncherOverlayRenderer.kt:164-166`, `G/SurfaceController.kt:304-307`, `G/NoticeController.kt:806-809`.
- **Current handling**: four consumers each own a `RingTapPolicy` and a timer.
- **Requirement**: one shared tap resolver; tests at 340 ms (DOUBLE), 360 ms (two SINGLE), three taps (IGNORE); no consumer resolves a tap earlier than the window.
- **Emulable?**: partially: timing tests need injected event times (JVM unit tests: yes; instrumented: `sendevent` with `sleep`).

**R6. Ring focus is handed to the R08 Access Bridge by broadcast.**
- **Fact**: the hub broadcasts `com.anezium.r08accessbridge.action.NEXUS_RING_FOCUS` (`focused`, `ts`) to package `com.anezium.r08accessbridge` whenever any of {launcher shown, surface active, surface handoff pending, notice owns ring} changes; the bridge stands down while focused and drives native UI otherwise. A launcher->surface handoff keeps focus for up to 10 s until the surface shows.
- **Evidence**: `G/RingFocusCoordinator.kt:66-67,75-77,140-149`; `CHANGELOG.md` 1.0.41; `G/NoticeController.kt:731-736`.
- **Current handling**: edge-triggered union; `beginSurfaceHandoff` + 10 s expiry.
- **Requirement**: the union is computed from the single set of UI owners; no `focused=false` broadcast between launcher hide and surface arrival; with no owner left a `false` is sent within one frame. Test with a fake receiver.
- **Emulable?**: yes (register a receiver for the action in a test app; check the extras).

**R7. Ring key precedence equals visual z-order.**
- **Fact**: notice band (if it claims the key) > notice owns ring (swallow everything) > launcher > surface > idle activity. The band is asked before the launcher because it is drawn above it.
- **Evidence**: `G/RokidBusAccessibilityService.kt:240-260`; `G/ActivityController.kt:501-505` (`claimsInput` false when surface, notice or launcher is up).
- **Current handling**: hand-written `when`.
- **Requirement**: derive routing order from the same list that defines window z-order (W2); test each pair (notice over launcher, launcher over surface, activity idle layer).
- **Emulable?**: partially (needs R1's ring device).

**R8. Ring input to plugin surfaces is translated into touchpad keys.**
- **Fact**: 87/88 -> DPAD_RIGHT/LEFT DOWN+UP pair; tap -> ENTER pair after the window; double tap -> BACK forwarded and handled locally. Reader surfaces take ring scroll directly (deduped by the 150 ms swipe dedupe).
- **Evidence**: `G/RingSurfaceInputPolicy.kt:24-38,63-69`; `G/SurfaceController.kt:288-309,915-943`.
- **Current handling**: as stated; vertical DPAD never produced by the ring.
- **Requirement**: plugin-facing input for ring and touchpad is identical per intent (a conformance test replays both sources and compares the forwarded `/surface/input` sequence).
- **Emulable?**: yes at the unit level; end to end needs R1.

**R9. A delayed ring tap must be bound to the state at DOWN.**
- **Fact**: 350 ms after the tap the selection may have moved. Notices and activities capture the interaction identity at DOWN and drop the resolution if it changed; the review left open whether a swipe during the window should cancel the tap.
- **Evidence**: `G/NoticeController.kt:826-836`; `G/ActivityPresentationPolicy.kt:84-104`; `docs/reviews/hud-interaction-review-follow-up.md` ("Ring tap followed by a swipe during the 350 ms delay").
- **Current handling**: notices answer the action captured at DOWN.
- **Requirement**: an explicit, tested policy (cancel vs freeze); never answer with a different target than the one shown at DOWN.
- **Emulable?**: partially.

**R10. Clock bases differ between tap timers (inferred).**
- **Fact**: `KeyEvent.eventTime` is on the uptime clock; launcher/surface resolve with `SystemClock.uptimeMillis()`, notice and activity resolve with `SystemClock.elapsedRealtime()` (which keeps counting through deep sleep).
- **Evidence**: `G/LauncherOverlayRenderer.kt:207`, `G/SurfaceController.kt:912` vs `G/NoticeController.kt:832`, `G/ActivityController.kt:787`.
- **Current handling**: no conversion.
- **Requirement**: one monotonic clock for input timing; test tap resolution after a simulated clock offset.
- **Emulable?**: yes at unit level.

---

## 4. Touchpad (temple)

**T1. Every touch first emits `KEYCODE_NOTIFICATION` (83); the firmware classifies 300-500 ms later.**
- **Fact**: Classification: single tap -> ENTER (66), double tap -> BACK (4), swipe -> a pair of DPAD keys. Accepting the raw 83 contact made the start of a swipe answer a notice.
- **Evidence**: `BUSSPEC.md:1242-1250`; `QUESTIONS.md:148-156`; `G/RokidBusAccessibilityService.kt:314-320` ("a classification allowed to take 500 ms"); `G/NoticeKeyDispatcher.kt:95-96`; `G/TouchpadGestureDetectors.kt:69`.
- **Current handling**: notices confirm only on ENTER/DPAD_CENTER; surfaces get the raw 83 forwarded as a tap after the triple-tap window (`:321-329`); activities via `handlePendingTempleTap`.
- **Requirement**: every irreversible action waits for the classified key; only reversible/optimistic UI may use the raw contact. Test: 83 DOWN then a DPAD pair -> no confirm.
- **Emulable?**: partially. `input keyevent 83`, then `input keyevent 66` after 300-500 ms; DOWN/UP spacing needs `sendevent`.

**T2. Triple tap = 3 contacts in 600 ms; 800 ms suppression of the trailing classifications.**
- **Fact**: three 83 DOWNs (repeat 0) within a sliding 600 ms window trigger the launcher; the ENTER/BACK the firmware emits afterwards are consumed for 800 ms; any other key DOWN clears the streak (swipes also start with 83); an unfinished streak is flushed 601 ms after the last contact as 1-2 taps (max 2).
- **Evidence**: `G/TouchpadGestureDetectors.kt:28-50,53-59,61-62,70-71`; `G/RokidBusAccessibilityService.kt:189-209,311-333`.
- **Current handling**: as stated.
- **Requirement**: replay test with real timestamps: 3 contacts at 0/250/500 ms trigger; at 0/250/700 do not; three fast swipes never trigger; BACK/ENTER within 800 ms after a trigger are swallowed and at 801 ms pass.
- **Emulable?**: partially; unit-level yes, device-level via injected timing.

**T3. Swipes arrive as duplicated DPAD pairs 20-80 ms apart.**
- **Fact**: measured 2026-07-08; a 50 ms dedupe let doubles through; 150 ms is safe because deliberate repeated swipes are >= ~200 ms. RIGHT/DOWN = forward, LEFT/UP = backward. The matching UP of a suppressed DOWN must be suppressed too. Camera also sees aliases 183/184 with a 400 ms debounce.
- **Evidence**: `G/TouchpadGestureDetectors.kt:75-121`; `G/SurfaceController.kt:899-909`; `G/CameraInputRouter.kt:10-21`; `BUSSPEC.md:1263-1265`.
- **Current handling**: one `DpadPairDedupe` per consumer (launcher, surface, notice, camera has its own debounce).
- **Requirement**: exactly one step per physical swipe for every consumer (test with pairs at 20/50/80 ms; a second swipe 200 ms later counts).
- **Emulable?**: partially: send two `sendevent` DPAD pairs with configurable gaps.

**T4. Two temple taps 188 ms apart answered a notice twice.**
- **Fact**: measured on hardware; a messaging plugin sent two replies.
- **Evidence**: `G/NoticeController.kt:38-42,238-243,381-389`; `BUSSPEC.md:1277-1281`; `CHANGELOG.md` 1.0.48.
- **Current handling**: single state transition marks the answer spent and returns it in one call; the phone also holds the flag.
- **Requirement**: two confirms 188 ms apart fire exactly one `/notice/action` or `/notice/input`.
- **Emulable?**: yes (inject two ENTER keys 188 ms apart).

**T5. A typing hand near the touchpad looks like a tap burst.**
- **Fact**: with an editable card active the triple-tap detector is bypassed; confirm/arrow keys belong to the text field, only BACK stays claimed.
- **Evidence**: `G/RokidBusAccessibilityService.kt:159-168,184-188`; `G/SurfaceController.kt:252-269`.
- **Current handling**: `hasFocusedEditableSurface()` gate.
- **Requirement**: while a field is focused, no gesture detector may open the launcher (test: 3 contacts in 500 ms while an editable card is active).
- **Emulable?**: yes.

**T6. `KEYCODE_PROG_BLUE` (186) always passes to the system.**
- **Fact**: purpose undocumented in code (inferred: a dedicated hardware button; possibly the AI/assist button since the phone surfaces "AI-assist button presses").
- **Evidence**: `G/RokidBusAccessibilityService.kt:158,1183`; `G/LauncherOverlayRenderer.kt:40,174`; `CHANGELOG.md:1210`.
- **Current handling**: two hard-coded early returns.
- **Requirement**: keep the pass-through in the classifier; test that 186 is never consumed or logged as a gesture.
- **Emulable?**: yes (`input keyevent 186`).

**T7. Backdrop notices swallow the touchpad but only on DOWN.**
- **Fact**: a backdrop notice hides native UI, so unclaimed ENTER/CENTER/DPAD DOWNs are consumed; ordinary notices let everything through.
- **Evidence**: `G/NoticeTouchpadInputPolicy.kt:7-24`; `G/NoticeKeyDispatcher.kt:61-78`.
- **Current handling**: as stated.
- **Requirement**: a backdrop notice leaks no key to the ROM; a plain notice leaks all unclaimed keys.
- **Emulable?**: yes.

---

## 5. BACK and the ROM launcher

**B1. An unclaimed BACK reaching the ROM launcher puts the display to sleep.** (stash@{0})
- **Fact**: the ROM's own launcher `onKeyUp(BACK)` handler sleeps the display when nothing of ours claims it (confirmed on device via logcat). A redundant dismiss shortly after Nexus already closed something is a real wearer pattern and lands on that shortcut. A 500 ms guard was too short; 4 s covers a wearer taking a beat. The real R08 dismiss path goes through `handleRingKeyEvent`, not the generic chain.
- **Evidence**: `stash@{0}` `G/RokidBusAccessibilityService.kt` (`guardAgainstRomSleepOnBack`, `lastConsumedBackAtMs`, `BACK_SLEEP_GUARD_MS = 4_000L`, and the early return in `handleRingKeyEvent`); working tree has none of it (`G/RokidBusAccessibilityService.kt:237`).
- **Current handling**: working tree: unclaimed BACK falls through to the ROM (the bug); stash: swallow an unclaimed BACK within 4 s after a consumed one.
- **Requirement**: a BACK/BACK-equivalent that no Nexus owner claims within 4 s of a consumed dismiss is swallowed and never reaches the ROM; after 4 s it passes. Applies to both ring and touchpad sources. Test at 3.9 s and 4.1 s with a fake ROM launcher that counts `onKeyUp(BACK)`.
- **Emulable?**: partially. A stand-in HOME app that calls `input keyevent 223` (or `PowerManager.goToSleep` via shell) on `onKeyUp(BACK)` reproduces the effect, not the real ROM.

**B2. Ring double-tap on an idle activity returns BACK to the system on purpose.**
- **Fact**: activities do not dismiss on BACK, so the double tap is passed to the system with `GLOBAL_ACTION_BACK`; on a ROM home that is exactly the B1 shortcut (inferred).
- **Evidence**: `G/ActivityController.kt:799-801`; `G/RokidBusAccessibilityService.kt:85-87`.
- **Current handling**: passes through (no guard in the working tree).
- **Requirement**: decide explicitly whether an idle-layer double tap may reach the ROM; if it may, it must not put the display to sleep unintentionally (guard or documented behavior).
- **Emulable?**: partially (as B1).

**B3. Native assistant scenes are dismissed by BACK bursts, only while armed and only when they are on top.**
- **Fact**: the phone's CXR `sendExit` closes the native scene in ~150 ms (measured 2026-08-02); the fallback BACK burst runs at 0/120/280/600/1000/1800 ms within a 3 s armed window, debounced 120 ms, only when the active window package is `com.rokid.os.sprite.assistserver` or `com.rokid.overlayrec`. The ROM launcher is deliberately excluded (BACK on the ROM home is "pure noise"), and once our overlay is active the burst holds fire.
- **Evidence**: `G/RokidBusAccessibilityService.kt:335-385,1189-1201`.
- **Current handling**: as stated.
- **Requirement**: BACK from Nexus never targets the ROM launcher; a test with the fake assistserver window closes it, and with our overlay active sends no BACK.
- **Emulable?**: partially: fake windows with those packages.

**B4. A plain `finish()` of the surface activity reveals a leftover Nexus launcher task.**
- **Fact**: surface activities use `finishAndRemoveTask`; `MainActivity` left paused in its task is what Android resumes when another task vanishes (single-app ROM, no other idle screen). Only surfaces that really rendered through the ACTIVITY path may finish it: finishing on overlay-surface end killed a task that was never created.
- **Evidence**: `G/SurfaceActivity.kt:32-37`; `G/SurfaceController.kt:56-60,624-637,703-710`; `G/MainActivity.kt:633-656`; `CHANGELOG.md` 1.4.6.
- **Current handling**: `activeDisplayedViaActivity` flag + `MainActivity.finishIfStale()` (skipped mid-onboarding).
- **Requirement**: closing any plugin surface returns to what was underneath (ROM home or the native app), never to the Nexus launcher; instrumentation test: open via activity path, close, top activity != MainActivity.
- **Emulable?**: yes (`dumpsys activity activities`).

**B5. Another app can relaunch itself and starve activity-based surfaces.**
- **Fact**: Rokid Relay's glasses activity keeps re-launching itself to the foreground on this firmware; hence the overlay path is the default.
- **Evidence**: `G/SurfaceController.kt:81-92`.
- **Current handling**: overlay by default, activity as fallback and for editable cards.
- **Requirement**: a foreground app bouncing in front of Nexus must not hide an overlay surface; test with a stand-in app relaunching itself every second.
- **Emulable?**: yes.

**B6. Nexus consumes BACK on its surfaces; the native app below never sees it.**
- **Fact**: BACK on a surface hides it locally and is forwarded to the plugin; a plugin that handles BACK gets a 1.5 s failsafe (if it does not answer with an update/hide, the hub closes locally); a notice BACK is always the first dismissal and never forwarded; an editable surface still claims BACK.
- **Evidence**: `G/SurfaceController.kt:28,252-286,965-1012`; `G/NoticeKeyDispatcher.kt:61-63`; `QUESTIONS.md:158-163`; `BUSSPEC.md:1266-1273`.
- **Current handling**: as stated.
- **Requirement**: BACK never leaks to the layer beneath while Nexus owns the top; failsafe fires at 1.5 s +- 100 ms.
- **Emulable?**: yes.

**B7. The ROM launcher hosts more than the home screen.**
- **Fact**: teleprompter/subtitle screens run in the same package; only the home layout has weather nodes. "Launcher on top" therefore does not mean idle.
- **Evidence**: `G/DisplayStandbyWatchdog.kt:194-196`; `G/StatusBadgeReserve.kt:6-24`.
- **Current handling**: signature = weather visible.
- **Requirement**: "idle home" detection uses the signature, never the package alone.
- **Emulable?**: partially (fake launcher with two layouts).

**B8. The firmware blocks manifest broadcasts to third-party apps; reinstalling disables the accessibility service.**
- **Fact**: `MY_PACKAGE_REPLACED` and similar do not reach the app, so re-arm hangs off every process entry (hub start, the launcher's boot auto-open); installing a new APK disables the service (Android behavior).
- **Evidence**: `G/GlassesHub.kt:215-219`; `TESTPLAN.md:470-472`.
- **Current handling**: see W9.
- **Requirement**: the UI must tolerate the service being absent at hub start: `MainActivity` falls back to its own list; when the service reconnects every overlay state is rebuilt (W10).
- **Emulable?**: yes.

**B9. The ROM's double-tap exit banner is a small window.**
- **Fact**: a window smaller than 50% of the screen area is not the launcher and cannot hide it.
- **Evidence**: `G/StatusBadgeOverlayRenderer.kt:91-95`.
- **Current handling**: coverage threshold in the top-window read.
- **Requirement**: top-window selection ignores windows <50% of the screen area.
- **Emulable?**: yes.

**B10. The phone can drive the native ROM cursor.**
- **Fact**: with CXR-L up, pointer control goes over the ROM `Tools` custom command (`enterTouch`, relative `moving` in the nominal 480x640 display, `click`, `long_press`, `exitTouch`); assistserver draws the cursor and injects touches; the fallback is Nexus's own cursor overlay and `dispatchGesture` (tap 40 ms, long press 550 ms, idle hide 8 s).
- **Evidence**: `BUSSPEC.md:2250-2290`; `G/RemotePointerController.kt:141,147,178`; `G/RemotePointerOverlayRenderer.kt:109-118`; `CHANGELOG.md:355-364`.
- **Current handling**: two paths; the fallback overlay is always the last window.
- **Requirement**: the pointer overlay stays above every HUD window (W2) and never takes focus or touches.
- **Emulable?**: yes for the fallback (`dispatchGesture`); the ROM path: no.

---

## 6. Accessibility overlay windows

**W1. The service asks for raw keys, interactive windows and all events.**
- **Fact**: `flagRequestFilterKeyEvents`, `flagRetrieveInteractiveWindows`, `flagIncludeNotImportantViews`, `flagReportViewIds`, `canPerformGestures`, `canRetrieveWindowContent`, `typeAllMask`, `notificationTimeout=50`; key filtering means Nexus sees every key before the ROM and decides whether to consume it.
- **Evidence**: `glasses-hub/src/main/res/xml/rokidbus_accessibility_service.xml`; `G/RokidBusAccessibilityService.kt:62-71`.
- **Current handling**: declared in XML and again at connect.
- **Requirement**: the rewrite keeps these capabilities; a manifest test asserts the flags.
- **Emulable?**: yes.

**W2. All HUD windows are `TYPE_ACCESSIBILITY_OVERLAY`; z-order is add order; a central function re-adds the ambient layers.**
- **Fact**: after any full-screen window is created, pin, activity, notice and pointer windows are removed and re-added in that order (ambient first, most interruptive last). The status-badge window is not part of it. A launcher opened over a notice must leave the notice visible for its whole life.
- **Evidence**: `G/HudOverlayStack.kt:3-33`; `G/LauncherOverlayRenderer.kt:91-100`; `G/SurfaceOverlayRenderer.kt:40-57`; `G/RemotePointerOverlayRenderer.kt:98-107`; `plans/012-activities.md:50-58` (verified on hardware 2026-07-28); `contracts/2026-08-16-lyrics-home-widget.contract.md:68-70` (planned ambient registration, not in code).
- **Current handling**: `HudOverlayStack.reassert()` called by the launcher and surface renderers only.
- **Requirement**: one `HudWindowStack` owns creation and order; test the order after each window is created/destroyed in every permutation (launcher, surface, notice, activity, pin, pointer). Screenshot test: notice over launcher.
- **Emulable?**: yes. `dumpsys window windows` (z-order) and `screencap`.

**W3. Never animate window layout params; animate child bounds in a fixed full-screen window.**
- **Fact**: `updateViewLayout` is an IPC to `system_server` and races the view's frame production. The stated reason is generic Android capability, not a measurement on this hardware.
- **Evidence**: `G/NoticeOverlayRenderer.kt:88-92`; `plans/013-hud-motion.md:74-84`; `contracts/2026-08-10-ink-card-overlay-morph.contract.md:293-297`.
- **Current handling**: notice/ink/activity islands are children of a MATCH_PARENT window; the status badge alone moves its window (x/y) and only when the row moves.
- **Requirement**: no per-frame `updateViewLayout`; lint/grep test for `updateViewLayout` outside the badge.
- **Emulable?**: yes.

**W4. Focus: HUD chrome is never focusable; the launcher and plugin-surface overlays are.**
- **Fact**: notice, pin, activity, pointer and badge windows use `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`, because a focusable overlay shows in `getWindows()` and defeats other services' topmost checks; the launcher and surface overlays have no such flag and call `requestFocus()`, while keys are decided in the service filter first. An editable field needs a focusable window to reach the IME, so it forces the ACTIVITY path. A `SurfaceHudView` comment says "overlays never hold focus" (conflict).
- **Evidence**: `G/StatusBadgeOverlayRenderer.kt:219-224`; `G/PinOverlayRenderer.kt:75-78`; `G/ActivityOverlayRenderer.kt:260-264`; `G/NoticeOverlayRenderer.kt:39-42`; `G/LauncherOverlayRenderer.kt:91-99`; `G/SurfaceOverlayRenderer.kt:40-47,60-62`; `G/InkCardPresentation.kt:66-79`; `G/SurfaceHudView.kt:1221`.
- **Current handling**: as stated.
- **Requirement**: a table of window -> {focusable, touchable} is the single source, tested against the actual `LayoutParams.flags`; no ambient element may become focusable.
- **Emulable?**: yes (`dumpsys window`).

**W5. Overlays are invisible to each other (accessibility tree and `getWindows()`).**
- **Fact**: Nexus badge and R08 Access Bridge's ring chip coexist by side (bridge slot fallback `[276..375]` measured 2026-07-28; Nexus chip anchors left), not by detection.
- **Evidence**: `G/StatusBadgeOverlayRenderer.kt:36-51`; `G/StatusBadgeReserve.kt:34-39`.
- **Current handling**: layout by convention.
- **Requirement**: any new element in the ROM status row keeps to the left cluster or documents the collision.
- **Emulable?**: no (needs the bridge); layout arithmetic is unit-testable.

**W6. Tiny and system windows confuse "what is active".**
- **Fact**: a root of at most 2x2 px (or empty) is treated as unreadable; a focused `TYPE_SYSTEM` window that is tiny makes `rootInActiveWindow` unreliable; selection then falls back to application windows ordered by active, focused, then lowest index; `com.rokid.sysconfig` and our own package are never remembered as the last app.
- **Evidence**: `G/AccessibilityWindowRoots.kt:12,19-50,91-105,107-137,175-177,193-214`.
- **Current handling**: `AccessibilityWindowRoots` used by setup automation and standby.
- **Requirement**: one API for "top/active window"; unit tests with fake windows (tiny focused system window present -> fallback picks the readable application window).
- **Emulable?**: partially: a test app can add a 1x1 `TYPE_SYSTEM`-like window only with privileges; unit tests suffice.

**W7. Reading the ROM launcher: highest layer covering >=50% of the screen, settle 150 ms, fail closed.**
- **Fact**: the top window is the highest-layer window covering at least half the screen area; the row centre comes from `status_power_iv` (the container is 12 px off); the ROM replaces status nodes while updating so reads wait 150 ms after events; if ids are renamed the chip disappears; the reserve for the left cluster only grows within a layout signature.
- **Evidence**: `G/StatusBadgeOverlayRenderer.kt:53-77,95-98,282-330`; `G/StatusBadgeReserve.kt:25-32`.
- **Current handling**: event-driven only (no polling).
- **Requirement**: readers are event-driven with a 150 ms settle; a missing id hides the element.
- **Emulable?**: partially (fake launcher).

**W8. The ROM (or a tool) can disable the accessibility service; Nexus re-arms it.**
- **Fact**: re-arm listens to `AccessibilityManager` state, a content observer on the raw `enabled_accessibility_services` setting (the manager's cached list lags the write), a rising edge of `adb_wifi_enabled` and Wi-Fi network availability; retry delay 1.5 s; a shell watchdog (180 s healthy / 30 s recovery interval) survives app death; a foreign accessibility service in front of Nexus's key handling has caused "input broken" reports.
- **Evidence**: `G/AccessibilityRearmWatcher.kt:16-24,68-123,225-231`; `glasses-hub/src/main/assets/rokid-nexus-a11y-watchdog.sh:14-28`; `CHANGELOG.md` 1.4.6 ("Check accessibility services"); `docs/SELF_ARM_ONBOARDING.md`.
- **Current handling**: as stated.
- **Requirement**: UI code assumes the service can disconnect at any time (W9); no UI state lives only in the service.
- **Emulable?**: yes. `settings put secure enabled_accessibility_services ...`, `accessibility_enabled 0/1`, `am force-stop`.

**W9. The ROM churns the service (destroy/recreate); every renderer must rebuild.**
- **Fact**: during manual pairing the ROM destroys and recreates the service; connect re-shows an active overlay surface and re-registers every renderer; destroy tears everything down and clears ring/notice/key state.
- **Evidence**: `G/RokidBusAccessibilityService.kt:62-137,277-309`; `G/SurfaceOverlayRenderer.kt:15-26`; `G/RokidBusAccessibilityService.kt:1020-1031`.
- **Current handling**: `onServiceConnected` chains ten renderer/controller registrations.
- **Requirement**: windows are disposable; state (active surface, notice, activity, launcher, ring focus) lives in controllers. Test: disconnect and reconnect mid-surface, mid-notice, mid-launcher; UI returns and `consumedDownKeys` is empty.
- **Emulable?**: yes (toggle the setting).

---

## 7. Activity vs overlay display paths, tasks, keyboard and IME

**X1. Two display paths: OVERLAY (default) and ACTIVITY; Ink always overlay; editable cards always activity.**
- **Fact**: the overlay can fail to add (falls back to the activity path); an editable field needs real window focus to reach the system IME or a keyboard bonded to the glasses.
- **Evidence**: `G/SurfaceController.kt:81-98,609-655`; `G/InkCardPresentation.kt:66-79`.
- **Current handling**: `surfaceDisplayPath` + fallback in `displaySurface`.
- **Requirement**: path selection is one pure function with tests: ink -> overlay, editable -> activity, overlay add failure -> activity.
- **Emulable?**: yes.

**X2. `SurfaceActivity` is a translucent, separate-task, screen-on activity.**
- **Fact**: `taskAffinity=com.anezium.rokidbus.glasses.surface`, `singleTask`, `excludeFromRecents`, portrait, translucent theme (`windowIsTranslucent`, transparent background) so a typed field leaves the app behind visible; `setShowWhenLocked`, `setTurnScreenOn`, `FLAG_KEEP_SCREEN_ON`; status/navigation bars black.
- **Evidence**: `glasses-hub/src/main/AndroidManifest.xml:77-87`; `glasses-hub/src/main/res/values/styles.xml`; `G/SurfaceActivity.kt:14-25`; `CHANGELOG.md` 1.5.0.
- **Current handling**: as stated.
- **Requirement**: the activity path never paints an opaque window; with a bright wallpaper behind, the area outside the card stays visible (screenshot pixel test).
- **Emulable?**: yes.

**X3. The launcher must stay up until the opened plugin's surface actually arrives.** (stash@{0})
- **Fact**: hiding on tap, before the plugin answered over the bus, showed the raw ROM home for a beat. The launcher stays exactly as drawn, ring focus is retained, and `SurfaceController` hides it when the matching surface arrives; fallback 10 s. Entries that do not open a surface (native apps) hide immediately. The stash also removed the tile open/close animation for now.
- **Evidence**: `stash@{0}` `G/LauncherOverlayRenderer.kt` (`SURFACE_ARRIVAL_TIMEOUT_MS = 10_000L`, `completeOpen`), `G/SurfaceController.kt` hunks ("this is where the launcher gets hidden"), `G/GridLauncherView.kt` (`enabled = false`); `G/RingFocusCoordinator.kt:77` (same 10 s).
- **Current handling**: working tree hides on tap and animates the tile; stash is the fix.
- **Requirement**: no frame between "launcher gone" and "surface visible" shows anything but one of them (frame-sampled capture test); a plugin that never answers leaves the launcher for at most 10 s then hides.
- **Emulable?**: yes (`screenrecord` / repeated `screencap`).

**X4. Only a real handoff steps the launcher aside; updates never do.**
- **Fact**: Lyrics and Media push updates to the same surfaceId continuously; stepping aside on each made the launcher vanish under a plugin already showing. A surface arriving by another route (a plugin gesture, a phone-triggered show) also has to move the launcher when it takes the ACTIVITY path.
- **Evidence**: `G/SurfaceController.kt:476-479,624-637`.
- **Current handling**: `isHandoff = active?.surfaceId != surface.surfaceId`.
- **Requirement**: repeated shows/updates with the same surfaceId never toggle the launcher; a new surfaceId does.
- **Emulable?**: yes.

**X5. `MainActivity` and the launcher must be the same launcher.** (stash@{0})
- **Fact**: the app icon and the triple tap must open the same launcher (grid mode included); the activity's own list is a fallback when the service is not connected; `renderScreen()` runs from both `onCreate` and `onResume`, and `finish()` does not cancel a pending `onResume`, so a second hand-off must be guarded.
- **Evidence**: `stash@{0}` `G/MainActivity.kt` hunk; `G/MainActivity.kt:633-656`.
- **Current handling**: working tree lacks the guard.
- **Requirement**: opening from the icon while the service is connected shows the overlay launcher exactly once and finishes the activity; opening without the service shows the list.
- **Emulable?**: yes.

**X6. The IME draws nothing; a keeper reclaims the keyboard from Rokid's.**
- **Fact**: `NexusRemoteInputMethodService` returns a 1x1 transparent view, never shows an input view and zero insets: the phone types through the `InputConnection`. Hi Rokid's companion selects Rokid's own keyboard without asking, so Nexus reclaims (package prefix `com.rokid.`) at hub start and on `DEFAULT_INPUT_METHOD` changes, at most 3 times per 10 minutes; a keyboard the owner installed is left alone.
- **Evidence**: `glasses-hub/src/main/AndroidManifest.xml:111-124`; `G/NexusRemoteInputMethodService.kt:9-30`; `G/GlassesKeyboardKeeper.kt:12-29,90-113`; `G/RemoteInputController.kt:10-16`.
- **Current handling**: as stated; needs `WRITE_SECURE_SETTINGS`.
- **Requirement**: no soft keyboard is ever visible on the display (screenshot test with a focused field); reclaim budget never exceeds 3/10 min.
- **Emulable?**: yes. `ime list -a`, `ime set`, `settings put secure default_input_method`.

**X7. A physical Enter on a bonded keyboard bypasses `onEditorAction`; the field must be see-through, not GONE.**
- **Fact**: hardware Enter dispatches as a raw key event; a GONE field loses focus and the IME, so an inline field is drawn at alpha 0 while its owner's band shows the text; typing text is mirrored to the band.
- **Evidence**: `G/SurfaceHudView.kt:125-140,484-490,500-515,312-318`.
- **Current handling**: `setOnKeyListener` and `applyInline`.
- **Requirement**: Enter from both a keyboard and the IME submits once; hiding a field never drops its focus.
- **Emulable?**: yes (`input text`, `input keyevent 66`).

**X8. The glasses never enter touch mode.**
- **Fact**: a focused full-screen view got Android's translucent white default focus highlight, a grey veil over the see-through areas.
- **Evidence**: `G/SurfaceHudView.kt:222-226`; `CHANGELOG.md` 1.5.0 ("No more grey veil").
- **Current handling**: `defaultFocusHighlightEnabled = false` on that view only.
- **Requirement**: every focusable full-screen view disables the default highlight; screenshot test with bright background shows no veil.
- **Emulable?**: partially: Cuttlefish may or may not be in touch mode; assert the flag.

**X9. Overlay surfaces attach children after layout, not during it.**
- **Fact**: children added mid-layout sit unmeasured and the board draws empty on the first render of a fresh surface window (seen on device).
- **Evidence**: `G/SurfaceHudView.kt:918-940`.
- **Current handling**: a `post` before attach.
- **Requirement**: first render of a fresh surface window is complete in the first drawn frame (frame-capture test).
- **Emulable?**: yes.

---

## 8. Ink presentation timing

**I1. An initial Ink show is released to the plugin only after its first HUD frame has drawn.**
- **Fact**: the gate refuses zero-size bounds, a mismatching surface/seq and a display power transition; a 500 ms timeout force-releases so a missing signal never leaves a blank card.
- **Evidence**: `G/InkPresentationGate.kt:34-46,48-52`; `G/SurfaceController.kt:383-412,429-440`; `G/SurfaceHudView.kt:663,802`; `contracts/2026-08-10-ink-card-overlay-morph.contract.md:240-243`.
- **Current handling**: as stated; `ready` event sent on release.
- **Requirement**: `ready` is never sent before a real frame or after 500 ms without one; test: draw with size 0, during SCREEN_OFF, and never.
- **Emulable?**: yes.

**I2. Ink layout is only trustworthy after two settled passes and after the display is fully on.**
- **Fact**: `750rpx` equals the measured container width, so a bad measurement silently shrinks every dimension (the clipped "Ensoleille/e" screenshot). Bounds measured while the display powers on were wrong; layout params changed inside `onLayout` are ignored by ViewRoot and corrupt Flexbox's same-frame second pass, so geometry is re-applied via `post` and a fresh clean layout is required; SCREEN_ON invalidates metrics.
- **Evidence**: `G/InkLayoutSettlePolicy.kt:13-56`; `G/InkHudView.kt:225-250`; `G/SurfaceController.kt:66-72`; `contracts/2026-08-09-ink-notice-path-bugs.contract.md:64-75`; `contracts/2026-08-09-assistant-display-hold.contract.md:73-79`.
- **Current handling**: settle policy + `isLayoutSettledForDraw()` in `dispatchDraw` (`SurfaceHudView.kt:322`).
- **Requirement**: a card presented right after a display power transition renders identically to a steady-state render (compare pixels).
- **Emulable?**: yes. `input keyevent 223/224` around `show`, `screencap` diff.

**I3. Never pre-hide, pre-collapse, scale or re-measure the Ink view; reveal only through clip bounds.**
- **Fact**: three earlier attempts failed: animating layout size (clipped text), pre-hiding waiting for a signal that sometimes never came (card never appeared), closing the notice before the first frame (black gap). The current design lays the card out at final size, clips a wrapper from band height to card height and fades alpha, and commits instantly at the 500 ms deadline.
- **Evidence**: `contracts/2026-08-10-ink-card-overlay-morph.contract.md:184-262`; `G/InkCardPresentation.kt:91-140` (`InkCardClipHost`); `G/SurfaceHudView.kt:663-700`.
- **Current handling**: as stated.
- **Requirement**: the Ink view is never `GONE`/`INVISIBLE`/alpha 0 in a state whose exit depends on a signal that can fail; the notice window is not removed before the first frame (test with a stubbed first-frame signal that never comes: card visible <= 500 ms).
- **Emulable?**: yes.

**I4. The Ink card keeps the notice band's footprint.**
- **Fact**: width 0.92 of the display width, centered, top = 12 dp + `HudTopInset`, height = content capped to the space below the top; the host paints no background, padding or chrome so the rest of the panel stays transparent.
- **Evidence**: `G/InkCardPresentation.kt:10-22,50-64`; `G/NoticeOverlayRenderer.kt:296-312`.
- **Current handling**: shared `HudBandGeometry`.
- **Requirement**: the band and the card share one geometry function; pixel test that band and card top/left/right coincide.
- **Emulable?**: yes.

**I5. Notice -> Ink handoff must not black out.**
- **Fact**: measured black gaps of several seconds when the notice window was torn down before the Ink arrived or painted; the assistant-side 2 s fallback blanked the band before a 12 s answer.
- **Evidence**: `contracts/2026-08-09-ink-morph-blackgap-clip.contract.md:25-49,79-95`; `contracts/2026-08-09-assistant-display-hold.contract.md:25-45`.
- **Current handling**: notice stays until first frame; cross-fade.
- **Requirement**: frame-sampled capture across the handoff shows no all-black frame.
- **Emulable?**: partially (timing differs; use slow-plugin stub).

**I6. Recurring Ink redraw is capped at 30 fps and ticks are coarse.**
- **Fact**: one `Choreographer` callback gates all recurring redraws to 30 fps; surface tick 100 ms (media 500 ms, status row 30 s).
- **Evidence**: `G/InkFrameGate.kt:5,29,51`; `G/SurfaceHudView.kt:1326-1328`; `plans/020-ink-surface.md:84`.
- **Current handling**: as stated.
- **Requirement**: no per-view timers in the new UI; animation clients register with one gate.
- **Emulable?**: yes.

**I7. The wake-word path shows the Ink surface cold; the launcher path warms it.**
- **Fact**: the clip/black bugs did not reproduce when the plugin was opened from the launcher (surface warmed) but did on the notice-triggered path.
- **Evidence**: `contracts/2026-08-09-ink-notice-path-bugs.contract.md:25-35,116-122`.
- **Current handling**: settle policy and gates cover both.
- **Requirement**: run every Ink presentation test on both paths (cold arrival over an active notice, and launcher open).
- **Emulable?**: yes.

---

## 9. Notices

**N1. The band is a child of a full-screen non-focusable, non-touchable window; the window keeps the screen-on flag.**
- **Fact**: window MATCH_PARENT overlay, band width 0.92 of the display width, top-centered, top margin 12 dp + inset; max height 0.65 (compact, 8 body lines) or 0.92 (pageable, up to 14); the flag is kept even though it does not stop the panel.
- **Evidence**: `G/NoticeOverlayRenderer.kt:39-42,88-102,270-360,874-885`; `G/NoticeController.kt:136-137`.
- **Current handling**: as stated.
- **Requirement**: geometry budgets expressed on the visible 480x352 (D1), not 640; test the 8/14-line caps at 352.
- **Emulable?**: yes.

**N2. BACK always dismisses a notice first; a double ring tap is the same.**
- **Fact**: never forwarded to the plugin, no `handlesBack`; runs ahead of the surface.
- **Evidence**: `G/NoticeKeyDispatcher.kt:61-63`; `G/NoticeController.kt:692-708,836-841`; `BUSSPEC.md:1266-1273`.
- **Current handling**: first in every chain.
- **Requirement**: BACK with notice over launcher/surface/activity dismisses only the notice (test each stack).
- **Emulable?**: yes.

**N3. Confirm and direction claims are conditional; unclaimed keys fall through.**
- **Fact**: confirm only while the band expects input; directions only when >=2 actions or pageable; a plain notice claims none; an answered band claims nothing; a backdrop band claims all unclaimed touchpad keys.
- **Evidence**: `G/NoticeController.kt:61-76,711-737,780-790`; `BUSSPEC.md:1240-1275`.
- **Current handling**: `claimsInput/claimsDirection/claimsAllInput`.
- **Requirement**: truth-table test per band state x key.
- **Emulable?**: yes.

**N4. The exit fade must not remove a newly arrived notice's window.**
- **Fact**: retargeting immediately cancels the exit's teardown; waiting for layout leaves one main-loop turn in which the old fade reaches zero and removes the live notice's window. A message landing during the ~250 ms exit was once never seen.
- **Evidence**: `G/NoticeOverlayRenderer.kt:241-258`; `CHANGELOG.md` 1.2.8.
- **Current handling**: as stated.
- **Requirement**: notice A closing and notice B showing within the exit window leaves B visible (stress test).
- **Emulable?**: yes.

**N5. A backdrop notice uses an opaque black scrim.**
- **Fact**: additive optics emit nothing for black but it occludes the windows underneath; it rides the band's fade and, being NOT_TOUCHABLE, blocks nothing except light.
- **Evidence**: `G/NoticeOverlayRenderer.kt:274-286`; `BUSSPEC.md:1226-1228`.
- **Current handling**: scrim view alpha follows `fade`.
- **Requirement**: with a bright wallpaper, a backdrop notice hides it fully; a normal notice does not.
- **Emulable?**: yes (screenshot pixel check).

**N6. Notice clocks.**
- **Fact**: default TTL 8 s (2..45 s), derived TTL 4 s min + 45 ms/char, absolute lifetime 90 s (plan 011 says 60 s), engaged inactivity 30 s, reading gets its own clock after the first page turn.
- **Evidence**: `shared/.../NoticeSurfaceContract.kt:200-214`; `G/NoticeController.kt:343-347,466-486,544`; `plans/011-notice-surface.md:63`.
- **Current handling**: contract + state machine.
- **Requirement**: notice timing constants in one file with the wire contract as their only source; tests advance a fake clock.
- **Emulable?**: yes.

---

## 10. Camera

**C1. The camera UI runs in a separate `:camera` process.**
- **Fact**: `CameraActivity` is portrait, `excludeFromRecents`, immersive sticky, screen-on flag; the main process learns of it from `/camera/session/state` envelopes and from a broadcast with a binder token (death recipients release a crashed camera process's overlay edge).
- **Evidence**: `glasses-hub/src/main/AndroidManifest.xml:89-96`; `G/CameraActivity.kt:75-80,818-828`; `G/CameraOverlayVisibilityBridge.kt:9-40,42-73`; `G/CameraSessionTracker.kt:3-16,52-68`.
- **Current handling**: as stated.
- **Requirement**: the UI treats "camera active" as remote state that can vanish without a close message; on camera process death the launcher/activities/notices recover (test with `am force-stop` of the `:camera` process).
- **Emulable?**: yes (`am kill`, `pidof`).

**C2. The camera hides activities and blocks notice-sleep and standby.**
- **Fact**: `CAMERA_OVERLAY` presentation context maps to HIDDEN; `cameraOverlayActive` blocks the notice sleep; `cameraSessionActive` blocks the standby watchdog.
- **Evidence**: `G/ActivityPresentationPolicy.kt:57-64`; `G/NoticeSleepPolicy.kt:51-52`; `G/DisplayStandbyPolicy.kt:120`.
- **Current handling**: as stated.
- **Requirement**: while the camera is visible no HUD element other than notices draws; leaving it restores state without replaying a flare.
- **Emulable?**: partially (fake broadcast).

**C3. Camera input: ENTER/CENTER freeze, swipes zoom, aliases 183/184, 400 ms debounce.**
- **Fact**: RIGHT/DOWN/183 zoom in, LEFT/UP/184 zoom out, ENTER/CENTER toggle freeze; unhandled keys pass; handled UPs are consumed; repeats consumed.
- **Evidence**: `G/CameraInputRouter.kt:9-42`; `G/CameraActivity.kt:110-122`.
- **Current handling**: own router (not the shared dedupe).
- **Requirement**: same one-step-per-swipe rule as T3; test 183/184 aliases.
- **Emulable?**: yes.

**C4. Stream geometry and preview must share one crop.**
- **Fact**: preferred portrait 720x1280 with hardware rotation, else landscape 1280x720 with the remaining rotation; a separate 4:3 preview gets a wider Camera2 crop and misaligns overlay boxes; the TextureView consumes local raster orientation, the encoded consumer the remaining rotation; viewfinder tests use 480x640.
- **Evidence**: `G/CameraOrientation.kt:42-76`; `G/CameraActivity.kt:132-140,700-708`; `G/CameraPreviewGeometry.kt:37-52`; `glasses-hub/src/test/.../CameraPreviewGeometryTest.kt:63-64`.
- **Current handling**: `selectStreamPlan`.
- **Requirement**: overlay boxes map through one viewport function for live and frozen frames.
- **Emulable?**: partially (virtual camera on Cuttlefish; sensor orientation differs).

**C5. Camera link needs the glasses' Wi-Fi, which the firmware boots off.**
- **Fact**: cold open enables Wi-Fi automatically and connects in 5.5 s (warm radio) to 9-14 s (cold chip) over P2P or reverse LOHS; the first camera frame is therefore not instant.
- **Evidence**: `TESTPLAN.md:576-590`; `CHANGELOG.md:642-647`.
- **Current handling**: empty/permission/"no camera plugin" screens.
- **Requirement**: the camera screen shows a truthful waiting state for up to ~15 s; test with delayed link.
- **Emulable?**: partially (`svc wifi disable`).

**C6. Microphone and worn state.**
- **Fact**: the glasses microphone DSP beamforms to the wearer's mouth and gates otherwise, so audio is near-silent when unworn; captured speech peaks well below full scale (about 5x gain). `GLASSES_WORN` is a link-state bit.
- **Evidence**: `docs/PLUGIN_SDK.md:1403-1414`.
- **Current handling**: SDK guidance only.
- **Requirement**: UI that depends on voice shows an "unworn" state, not silence.
- **Emulable?**: no (needs the DSP); the state bit can be faked.

---

## 11. Rendering constraints

**S1. The optic is additive and green: black is transparent and every other hue is mush.**
- **Fact**: unlit pixels show the real world; "any non-green hue lands as green mush"; the physical panel is GREEN MONOCHROME so an image appears as green luminance (no tone mapping, no dithering expected).
- **Evidence**: `contracts/2026-08-16-lyrics-home-widget.contract.md:158-159`; `TESTPLAN.md:196-201`; `G/PinOverlayRenderer.kt:162-163`; `BC/RokidHudTokens.kt:40-42`.
- **Current handling**: pure-black fills, borders and text; Ink colors clamped to tiers (`G/InkStyleMapping.kt:139-160`).
- **Requirement**: an automated screenshot lint on any render: every non-black pixel is green-dominant, no large filled area (>N% of the canvas) of any intensity, background alpha = transparent black. `BusTheme.danger` (red, `BC/BusTheme.kt:31`) must not be used on the glasses.
- **Emulable?**: partially. Cuttlefish shows true colors; the lint (and compositing over a bright wallpaper to imitate transparency) can run on `screencap` output. The optics themselves: no.

**S2. Never a translucent grey fill.**
- **Fact**: "A nicer translucent grey is a visible grey rectangle on-glasses." Selection/emphasis use borders and text intensity.
- **Evidence**: `G/NoticeOverlayRenderer.kt:437-441`; `G/HudActionRowView.kt:81-86`; `plans/012-activities.md:127-130`.
- **Current handling**: transparent fill + stroke.
- **Requirement**: lint: no panel background alpha strictly between 0 and 100% except the six token tints, and none over 12% for selection fills (design rule `green-06`/`green-12`).
- **Emulable?**: yes for the lint; visibility on optics: no.

**S3. Large bright fills bloom (design-system statement, not measured in this repo).**
- **Fact**: `green-100` "never large fills (bloom)"; only one focus element at a time.
- **Evidence**: `docs/grid-hud-roadmap/00-overview.md:53` (unverified on device).
- **Current handling**: tokens; `SurfaceHudView` draws a solid phosphor route chip (`:1231-1245`), the brightest fill on the row.
- **Requirement**: at most one `green-100` element on screen except deliberate chips; tested by area.
- **Emulable?**: no (bloom); area lint yes.

**S4. Intensity levels 24/12/6 % are not validated on the optics.**
- **Fact**: the token table defines six alpha steps; no source records that 12 % and 6 % surfaces are visible on the waveguide (Q10).
- **Evidence**: `BC/RokidHudTokens.kt:23-38`.
- **Current handling**: grid HUD only.
- **Requirement**: do not encode meaning (selection, state) only in the 12 % or 6 % steps; pair with border/icon/text.
- **Emulable?**: no.

**S5. Layers dither grey grain on the waveguide.**
- **Fact**: a `ScrollView` in the onboarding launcher was replaced by manual `translationY` because its layers dithered grey grain; bitmaps decode RGB_565 with `inDither=false`, media paints use `isDither=false`.
- **Evidence**: `G/MainActivity.kt:186-190,505-509`; `G/ImageHudView.kt:60-66`; `G/MediaHudView.kt:133-148`; conflicting current use of `ScrollView`: `G/LauncherOverlayRenderer.kt:298-308`, `G/GridLauncherView.kt:37-44`.
- **Current handling**: mixed (Q11).
- **Requirement**: decide on device whether ScrollView grain also affects the overlay launchers; until then the new list/grid scroll by translating children, with a test that no `ScrollView` exists in glasses UI code.
- **Emulable?**: no (grain); the structural lint yes.

**S6. Text metrics tuned on device.**
- **Fact**: chips defer to the ROM row: 20 px icons (13 dp), labels ~11 sp regular; bold 12 sp out-weighed the row and an 18 px glyph under-weighed into illegibility (both tried on device); monospace family everywhere; the Ink card "Ensoleille" wrap shows how narrow the text width is.
- **Evidence**: `G/StatusBadgeGeometry.kt:54-58,61-72`; `glasses-hub/src/main/res/values/styles.xml`.
- **Current handling**: constants in geometry object.
- **Requirement**: minimum text size and stroke rules stated once; tests at 240 dpi.
- **Emulable?**: partially (metrics yes, legibility no).

**S7. Motion: state changes only, no idle loops, reduced motion honored at animation start.**
- **Fact**: motion durations 180/240/280 ms (HudMotion) and 120/200/220-320 ms tokens; frames reach the app at 60 fps but whether a 280 ms morph "reads or smears through the waveguide" was settled by the owner watching the loop, not by a camera; "remove animations" = `animator_duration_scale == 0`, read fresh each time; `HudMotion.enabled` is a global kill switch wired to nothing.
- **Evidence**: `G/HudMotion.kt:6-46`; `plans/013-hud-motion.md:60-116`; `G/ReducedMotion.kt:6-16`; `BC/RokidHudTokens.kt:82-88`; `stash@{0}` (structural 220 ms, fast-out-slow-in path (0.4,0,0.2,1)).
- **Current handling**: two vocabularies.
- **Requirement**: one motion vocabulary; every animation checks reduced motion and the kill switch at start; with `animator_duration_scale=0` the end state is reached with no intermediate frame.
- **Emulable?**: yes. `settings put global animator_duration_scale 0`.

**S8. A lit display costs power even when it looks black.**
- **Fact**: an awake display costs about a quarter of the battery per hour "even when the optic looks black"; moving elements have "a thermal bill".
- **Evidence**: `CHANGELOG.md` 1.2.6; `G/HudMotion.kt:20-24`.
- **Current handling**: standby watchdog (P5), idle loops banned.
- **Requirement**: no continuous animation without an engaged interaction; test: 60 s idle launcher schedules no frames (Choreographer callback count = 0).
- **Emulable?**: partially (`dumpsys gfxinfo`).

**S9. Image surfaces: <=512x512, <=64 KiB, 150 ms minimum interval, RGB_565.**
- **Fact**: hub validates declared vs actual dimensions/MIME/hash before decode; decodes on 2 threads.
- **Evidence**: `G/ImageHudView.kt:55-70`; `TESTPLAN.md:203-215`; `shared/.../ImageSurfaceContract.kt:37`.
- **Current handling**: as stated.
- **Requirement**: keep the limits; test 65,537 bytes rejected.
- **Emulable?**: yes.

---

## 12. Timeouts and magic numbers

One row per constant; "Source" is the definition site. The rewrite should keep them in one `HudTimings` object and cite this table.

| Value | Meaning | Source |
|---|---|---|
| 5 s | ROM `screen_off_timeout` (see P1 for conflicts) | `G/NoticeOverlayRenderer.kt:31`, `G/SurfaceController.kt:708` |
| 3 s | one-shot wake lock | `G/DisplayWakePolicy.kt:68` |
| 5 s | global wake budget window | `G/DisplayWakePolicy.kt:67` |
| 2 / 60 s | unattended notice wake episodes / reset | `G/DisplayWakePolicy.kt:69-70` |
| 75 ms / 2 s | notice wake retry / lock-settle timeout | `G/NoticeController.kt:1320-1321` |
| 90 s | assistant episode hold ceiling | `G/AssistantDisplayEpisode.kt:373` |
| 3 min / 45 s | standby idle window / evaluation cadence | `G/DisplayStandbyPolicy.kt:74`, `G/DisplayStandbyWatchdog.kt:215` |
| 5 min | manual-pairing screen lock | `G/GlassesHub.kt:1640` |
| 600 ms / 800 ms | triple-tap window / suppression of trailing classifications | `G/TouchpadGestureDetectors.kt:70-71` |
| 150 ms | swipe pair dedupe (hardware pairs 20-80 ms apart) | `G/TouchpadGestureDetectors.kt:120` |
| 350 ms (+1) | ring tap window | `G/RingTapPolicy.kt:38` |
| 300-500 ms | firmware touch classification latency | `BUSSPEC.md:1246-1249` |
| 188 ms | measured double-tap that answered twice | `G/NoticeController.kt:40` |
| 400 ms | camera direction/activation debounce | `G/CameraInputRouter.kt:10-11` |
| 4 s | unclaimed-BACK guard (stash@{0}) | `stash@{0}` service hunk |
| 1.5 s | plugin-handled BACK failsafe | `G/SurfaceController.kt:28` |
| 10 s | launcher->surface handoff timeout | `G/RingFocusCoordinator.kt:77`, stash `LauncherOverlayRenderer` |
| 3 s / 120 ms / 0..1800 ms | native-assistant dismiss arm / debounce / burst | `G/RokidBusAccessibilityService.kt:1195-1197` |
| 500 ms | Ink first-frame deadline | `G/SurfaceHudView.kt:663,802` |
| 30 fps | Ink recurring redraw cap | `G/InkFrameGate.kt:51` |
| 100 / 500 ms / 30 s | surface tick / media tick / status-row tick | `G/SurfaceHudView.kt:1326-1328` |
| 150 ms | ROM status-row read settle | `G/StatusBadgeOverlayRenderer.kt:98` |
| 2 dp | HUD inset auto hysteresis | `G/HudTopInset.kt:19` |
| 364 px | ROM home-row centre with position fully UP | `G/HudTopInset.kt:18` |
| 50 % | full-screen coverage threshold | `G/StatusBadgeOverlayRenderer.kt:95` |
| 8 s / 2-45 s / 90 s | notice default / range / lifetime | `shared/.../NoticeSurfaceContract.kt:200-214` |
| 30 s | engaged notice inactivity | `G/NoticeController.kt:544` |
| 10 s | activity panel idle collapse | `G/ActivityController.kt:395` |
| 10 s / 60 s | activity flare interval / urgent interval | `G/ActivityController.kt:396`, `G/ActivityPresentationPolicy.kt:36` |
| 40 ms / 550 ms / 8 s | pointer tap / long press / idle hide | `G/RemotePointerController.kt:141,147,178` |
| 34 ms | phone pointer coalescing (29.4 Hz) | `BUSSPEC.md:2262-2263` |
| 1.5 s / 180 s / 30 s | a11y re-arm retry / watchdog healthy / recovery | `G/AccessibilityRearmWatcher.kt:18`, `.../rokid-nexus-a11y-watchdog.sh:14-15` |
| 3 per 10 min | keyboard reclaim budget | `G/GlassesKeyboardKeeper.kt:21-22` |
| 0.92 / 0.65 / 0.92 | band width / compact height / grown height fractions | `G/InkCardPresentation.kt:12`, `G/NoticeOverlayRenderer.kt:877-878` |
| 12 dp | edge margin (band top, pin, activity) | `G/InkCardPresentation.kt:13`, `G/PinOverlayRenderer.kt:281`, `G/ActivityOverlayRenderer.kt:592` |
| 96 dp x4 | tile unit x columns (see D7) | `G/GridLauncherView.kt:105`, `shared/.../TileGridPacker.kt:13` |

**M1. Timings must be simulable with a fake clock.**
- **Fact**: nearly every rule above is a timing rule; none should need wall-clock sleeps.
- **Evidence**: policies already take `nowMs`/`eventTimeMs` (`G/DisplayWakePolicy.kt:74-81`, `G/DisplayStandbyPolicy.kt:76-82`, `G/RingTapPolicy.kt:38-52`).
- **Current handling**: pure policy classes, but controllers use three different system clocks (R10).
- **Requirement**: an injected monotonic clock for all UI timing; the full table above is exercised in JVM tests.
- **Emulable?**: yes (JVM).

---

## 13. Emulation cheat-sheet (Cuttlefish / adb)

Assumes the harness in `tools/emulator/` (`adb_` = `/opt/cuttlefish/cf/bin/adb -s 0.0.0.0:6520`). None of this was run when writing.

| Need | Command |
|---|---|
| Glasses canvas | `wm size 480x352; wm density 240` (or `480x640` to compare) |
| Enable the service | `settings put secure enabled_accessibility_services <pkg>/<pkg>.RokidBusAccessibilityService; settings put secure accessibility_enabled 1` |
| 5 s timeout, no stay-on | `svc power stayon false; settings put system screen_off_timeout 5000` |
| Sleep / wake | `input keyevent 223` / `input keyevent 224` (or `KEYCODE_WAKEUP`) |
| State | `dumpsys power \| grep -E "mWakefulness\|Wake Locks"`, `dumpsys window windows`, `dumpsys accessibility` |
| Touchpad keys | `input keyevent 83` (contact), `66` (ENTER), `4` (BACK), `19/20/21/22` (DPAD); DOWN/UP spacing needs `sendevent` |
| Ring keys | `input keyevent 85/87/88`; only reaches the ring path if the input device name contains `R08` (uinput on a rooted image) |
| Reduced motion | `settings put global animator_duration_scale 0` |
| Battery gates | `dumpsys battery set ac 0` / `dumpsys battery set status 3` |
| IME | `ime list -a`, `ime set <id>`, `settings put secure default_input_method <id>` |
| Fake ROM | stand-in HOME app with package `com.rokid.os.sprite.launcher`, ids `status_time_tv`, `status_weather_iv`, `status_weather_tv`, `status_power_iv`, a weather-less layout, and `onKeyUp(BACK)` that sleeps the display |
| Optics | none: run a pixel lint on `screencap -p` (green-dominant, no large fills) and composite over a bright wallpaper |

Cannot be emulated: real 5 s timeout re-assertion at boot, the panel ignoring `FLAG_KEEP_SCREEN_ON`, additive optics / bloom / grain, ROM launcher internals, the R08 firmware (real DOWN/UP and duplicate timing), the touchpad classifier, Rokid's native assistant, CXR, camera sensors and DSP microphone.

---

## 14. Open questions (need an on-device check)

1. **Q1 (display-size part resolved by D1: hardware 480x400, OS screen 480x352; the 640-row window was an earlier configuration) Which 352 rows are lit?** The window space is 480x640; the ROM home status row sits at y 353..375 and teleprompter chrome at 466..560, both below 352. Where is the 480x352 visible region within 640, does it move with the ROM screen-position setting, and is 480x400 (docs) ever true? Needed before any vertical fraction is reused.
2. **Q2 Density (size part resolved, see D1):** confirm 1.5 (240 dpi); `HudPositionPreviewView` claims 2.0.
3. **Q3 Do launcher, camera and plugin overlays survive >5 s idle?** They rely on `FLAG_KEEP_SCREEN_ON`, which is known not to stop the panel for the notice/surface windows. The grid launcher's screen-off investigation (stash) suggests a problem; measure idle-launcher wakefulness for 15 s.
4. **Q4 What is the real screen timeout policy?** Forced 5000 at boot, wearer-adjustable, or "never turns off by itself" (1.2.6)? Does the value differ after Hi Rokid changes it?
5. **Q5 Which BACK sources hit the ROM `onKeyUp(BACK)` sleep?** Ring double-tap through the bridge, touchpad double-tap classification, the idle-activity `GLOBAL_ACTION_BACK` (B2), or all? Is 4 s enough on every path?
6. **Q6** Meaning of `KEYCODE_PROG_BLUE` (186) and aliases 183/184: which physical inputs?
7. **Q7 R08 timing:** DOWN-to-UP spacing, repeat behavior and any duplicate events per tap/swipe (the 20-80 ms pair is documented only for the touchpad).
8. **Q8 Clock bases (R10):** confirm event times are uptime and whether `elapsedRealtime` timers misfire after deep sleep.
9. **Q9 Triple-tap on the ring** relies on the R08 Access Bridge; behavior when the bridge is absent or its version lacks "Nexus launcher".
10. **Q10 Legibility of 12 %/6 % tints and bloom threshold** for `green-100` fills on the optics; minimum readable text size at 480x352.
11. **Q11 ScrollView grain:** does it also affect the overlay launchers (list and grid), or only the activity it was removed from?
12. **Q12 Touchpad classifier latency distribution** (300-500 ms) and the double-tap-to-BACK delay.
13. **Q13 Is the first physical key after wake really eaten** (only shown for `adb input`)?
14. **Q14 Camera window:** does `FLAG_KEEP_SCREEN_ON` keep the viewfinder alive beyond 5 s idle, or does the standby/ROM timer kill it?
15. **Q15 R08 device name:** all variants of the input device name (is `"R08"` always present, also over Bluetooth reconnects)?
16. **Q16** Does `GLOBAL_ACTION_LOCK_SCREEN` differ from the power key for wake/dim behavior and for `ACTION_SCREEN_OFF` timing (used by the standby watchdog and notice sleep)?

---

## Summary of counts

| Section | Facts |
|---|---|
| 1 Display and canvas (D1-D9, plus the 1.0 disagreement table) | 9 |
| 2 Power, sleep, screen timeout, wake (P1-P10) | 10 |
| 3 Ring R08 input (R1-R10) | 10 |
| 4 Touchpad (T1-T7) | 7 |
| 5 BACK and ROM launcher (B1-B10) | 10 |
| 6 Accessibility overlay windows (W1-W9) | 9 |
| 7 Activity vs overlay, tasks, keyboard/IME (X1-X9) | 9 |
| 8 Ink presentation timing (I1-I7) | 7 |
| 9 Notices (N1-N6) | 6 |
| 10 Camera and sensors (C1-C6) | 6 |
| 11 Rendering constraints (S1-S9) | 9 |
| 12 Timeouts and magic numbers (table of 40 rows, plus M1) | 1 + table |
| **Total** | **93 facts + 40 table rows** |
