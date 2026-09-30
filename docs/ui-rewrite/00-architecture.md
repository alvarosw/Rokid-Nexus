# Glasses UI rewrite: architecture and delivery plan

Status: approved direction (product owner, 2026-09-29). Replaces the incremental grid-HUD
approach in `docs/grid-hud-roadmap/` for everything on the glasses side; the phone-side layout
editor, `TileSize`, `TileGridPacker`, `TileLayoutStore`, the tile data pipeline and all wire
contracts stay.

Inputs this plan is built on:

- `docs/HARDWARE.md` — every hardware/ROM fact (IDs such as D1, P1, R3, B1 are cited below).
- `docs/ui-rewrite/01-current-behavior.md` — current behavior, bugs (F-n) and the 186-item parity
  checklist (§7). The checklist is the acceptance suite of this rewrite.
- `docs/EMULATION.md` — Cuttlefish at the glasses canvas, fake phone, capture scripts.

## 1. Goals and non-goals

Goals: one predictable UI where exactly one owner decides what is on screen and who receives each
key; no moment in which a Nexus flow is in progress but no Nexus window owns input; motion that is
real (one view hierarchy) and always interruptible; one design system (`RokidHudTokens`) for list
and grid; everything validated in emulation before it counts as done.

Non-goals: changing any bus path, payload, capability or the public SDK (`BUSSPEC.md`,
`docs/PLUGIN_SDK.md`); changing the Ink compiler; the camera pipeline; SelfArm/onboarding flows.
The trusted `/core/native-apps/*`, `/core/remote-input/*`, `/core/navigation/*`, `/core/pointer/*`
routes stay hub-only.

## 2. Architecture

```
 raw KeyEvents ──► HudInput ──► HudIntent ─┐
 bus envelopes ──► controllers ─► HudEvent ─┼─► HudStateMachine (pure) ──► HudState + [HudEffect]
 timers (injected clock) ───────────────────┘                                 │
                                                                              ▼
                                              HudHost (1 persistent window) ◄─ effects runner
                                                ├─ HomeLayer  (ListHome | GridHome)
                                                └─ AppLayer   (existing surface views, re-hosted)
                                              AmbientStack (notice, pin, activity, badge, pointer)
```

All new code lives in `glasses-hub/.../glasses/hud/`. Old classes are deleted when the delivery
that replaces them lands; nothing runs two implementations side by side behind a flag.

### 2.1 `HudStateMachine` — the single owner

Pure Kotlin, no Android types, driven by an injected clock/scheduler; 100% JVM-testable.

State (one value, not a union of booleans):

- `Hidden` — no Nexus foreground. Keys pass to the system.
- `Home(mode, selectedId)` — launcher visible. Selection is by plugin id, not index (F-30).
- `Opening(pluginId, openToken, deadline)` — the home stays on screen and owns input until the
  plugin's surface arrives or the deadline passes (fixes F-1/F-3, HARDWARE X3).
- `App(surfaceId, origin)` — a surface is open in the `AppLayer`; `origin` says whether dismiss
  returns to `Home` or to `Hidden`.
- `External(kind, origin)` — content outside our window (ACTIVITY display path, Camera, native
  app). Our window is detached or non-focusable; returning is explicit.

Rules:

- Every transition emits effects (`SendLauncherOpen`, `ShowHost`, `HideHost`, `PublishRingFocus`,
  `Animate…`, `ForwardKeyToSurface`, …). Views never call controllers and controllers never call
  views; both talk only to the machine.
- A surface show is matched to an open by `openToken` + plugin id; unsolicited shows never claim a
  launcher return. Pending opens expire (F-3).
- Ring focus (`NEXUS_RING_FOCUS`) is derived: focused ⇔ state ≠ `Hidden` or a notice owns the ring.
  `RingFocusCoordinator`'s handoff timer and `LauncherReturnCoordinator` are deleted.
- BACK/dismiss is always answered by the state it lands in. `Hidden` is the only state that lets
  BACK reach the ROM, and there Nexus never consumes a key (product decision 2026-09-30, HARDWARE
  B1): what happens inside the HUD is handled by the app, anything outside it belongs to the ROM.
  The first BACK closes the HUD; the second, with nothing of ours on screen, goes to the ROM
  untouched, even if the ROM then sleeps the display.

### 2.2 `HudInput` — one normalizer for every key source

Converts ring (R08), touchpad and DPAD/keyboard events into `HudIntent`s (`Next`, `Prev`,
`Select`, `Dismiss`, `OpenLauncher`, `Raw(key)` for surfaces that need raw keys). It owns, once:
DOWN/UP pairing and orphan-UP consumption (R3), tap/double/triple-tap windows with a single clock
(R10), touchpad first-contact KEYCODE 83 handling (T1/T2/T4), device classification (R1).

Debug seam: a debug-source-set receiver injects raw events *tagged as a given device class*
(e.g. R08), so the real ring pipeline runs in emulation — `adb input keyevent` alone never reaches
it (HARDWARE §13).

### 2.3 `HudHost` — one window for home and app

A single `TYPE_ACCESSIBILITY_OVERLAY` window. It is attached on `Hidden → *` and detached only on
`* → Hidden` or `* → External`; it is never removed between `Home`, `Opening` and `App`. Layers are
switched by visibility inside one hierarchy. Constraints from HARDWARE §6: never animate
`updateViewLayout`; chrome windows stay non-focusable; `FLAG_KEEP_SCREEN_ON` is not trusted for
staying awake (P1/P2) — wake is requested through the existing `DisplayWakePolicy`.

Geometry comes from one `HudGeometry` object (visible viewport, safe area, content width) derived
from `RokidHudTokens`; nothing else reads `displayMetrics` for layout. The visible-viewport origin
inside the Android window is a single value; the official screen is 480×640 and the viewport is all of it (HARDWARE D1).

### 2.4 `HomeLayer` — list and grid, one design system

Both modes render the same model `(entries, selectedId, tileData)` and use only `RokidHudTokens`
(`BusTheme` is retired from the glasses launcher). Views are keyed by plugin id and updated in
place; a selection move changes focus state only, never rebuilds the tree (F-8). Live-data tiles
show selection/focus like fallback tiles (F-10). No `ScrollView` grain (S5): scrolling is our own
offset. List follows `ListItem` (32 px rows). The design rule of 3–4 items at a time was written for
the 352 px reference canvas; on the 480×640 screen the owner wants the whole screen used, so the
list and grid show as many whole rows as fit (see §6, U3b/U4).

### 2.5 `AppLayer` — existing content, new host

`SurfaceHudView`, `InkHudView`, `ImageHudView`, `MediaHudView`, `ReaderSurfaceView` are re-hosted
unchanged at first (their contracts and the Ink first-frame gate, I1–I3, are preserved). Restyling
their content to the design system is a later, separate delivery.

### 2.6 Motion

State changes are immediate; views animate toward the current state. One `HudMotion` driver,
durations from `RokidHudTokens` (structural 220 ms), reduced-motion → instant end state. Any new
event cancels an in-flight animation and snaps it; no callback of an animation ever triggers a
state transition or a bus send (F-12: tween sending `/launcher/open` after BACK).

Open morph: the tile's panel grows to the safe area inside `HudHost`, shows a `Loader` while
`Opening`, and the real surface view appears inside it on `App`. Close is the reverse onto a live
tile. `External` transitions are instant (no morph across windows).

### 2.7 `AmbientStack`

Notice, pin, activity, status badge and pointer keep their own windows (they must float over
foreign apps too) but z-order is declared once and re-asserted by one owner whenever any window
is added (F-17: pin below notice).

## 3. Decisions taken for the "decision needed" parity items

Recorded here so they can be reviewed; each becomes a test.

| Item (01 §7) | Decision |
|---|---|
| 10 pin vs notice | Pin always below notice (documented intent). |
| 34 selection identity | By plugin id; survives list updates. |
| 56 live tile focus | Live tiles show selection and focus exactly like fallback tiles. |
| 62 open/close motion | Real morph inside `HudHost`, 220 ms, siblings dimmed, instant under reduced motion. |
| 69 pending open expiry | Token-matched, expires at the `Opening` deadline; unsolicited shows never claim a return. |
| 87 phone link loss | Keep HEAD behavior (surface stays) for now; flagged for product review. |
| 119 input with launcher over surface | The launcher gets exclusive input; nothing leaks to the surface below. |

## 4. Deliveries

Each delivery is one agent task on branch `ui-rewrite`, one commit (or a few), and is done only
when: its JVM tests pass, the relevant parity items are ticked, the four hub/shared suites pass,
and it was exercised on the Cuttlefish with the fake phone, with captures attached.

| # | Delivery | Replaces / deletes |
|---|---|---|
| U1 | `HudInput` + debug raw-event injection seam | input parts of `RokidBusAccessibilityService`, `RingTapPolicy`, `DpadPairDedupe` usage |
| U2 | `HudStateMachine` + full [JVM] test suite for launcher/app/handoff items | — (not wired yet) |
| U3 | `HudHost` + `HomeLayer` list (new design) + `AppLayer` re-hosting; wire U1+U2; `MainActivity`/`OpenLauncherReceiver`/triple-tap all go through the machine | `LauncherOverlayRenderer`, `SurfaceOverlayRenderer`, `LauncherReturnCoordinator`, ring handoff in `RingFocusCoordinator`, `MainActivity` list |
| U4 | Grid home (keyed tiles, live + fallback) | `GridLauncherView`, `TileGridContainer` |
| U5 | Motion (morph, loader, focus) | `TileExpansionAnimator`, ghost/blur code |
| U6 | `AmbientStack` z-order owner | `HudOverlayStack` |
| U7 | Restyle surface content to `RokidHudTokens` | `BusTheme` use on glasses |

U1 and U2 are independent. U3 needs both. U4–U6 need U3. U7 last.

## 5. Open questions blocking specific work

- HARDWARE Q1/Q2 display size: resolved (official screen 480×640, no offset; the 480×352 / 480×400
  figures of 2026-09-29 are superseded); density
  (1.5) is still unconfirmed.
- HARDWARE B1/Q5: decided (2026-09-30). No guard: keys in `Hidden` belong to the ROM and Nexus never
  swallows them.
- Emulator is API 37; the device is API 32. An API 32 Cuttlefish is feasible (docs/EMULATION.md)
  and will be set up before U3 is declared done if API-specific window behavior shows up.

## 6. Delivery notes

### U3a (structural wiring, no redesign)

Implemented in `glasses-hub/.../glasses/hud/`:

- `HudRunner` drives `HudStateMachine` one event at a time, queues events raised by effects, owns
  the single deadline through a `HudTimer`. It is pure
  Kotlin and JVM-tested with a fake clock, timer and sink.
- `HudController` (main thread) is the effects runner and the only place that decides what is on
  screen: entry points (`onRawKey`, `openLauncher`, `toggleLauncherFromBroadcast`,
  `onSurfacePresented`/`onSurfaceHidden`, `onNativeAppLaunched`, camera edge, service
  connect/destroy) feed it, and its effects drive `HudHost`, the bus, `SurfaceController`, the
  ring-focus broadcast and the ambient controllers. It also adapts `HudInput`'s `InputTarget`s: the
  machine gets `HUD` intents, `NoticeController` and `ActivityController` get theirs directly.
- `HudHost` is the persistent window with `HomeLayer` (existing list or grid content view plus one
  status line for "Opening..." and the failure) and `AppLayer` (`SurfaceHudView`). The app layer stays
  visible under a launcher opened over a surface, as that surface's own window used to.
- `HudGeometry` holds the visible viewport (default 0,0,480x640, the whole screen) as one value.

Deviations from §2, with the reason:

- A show or update of the surface a forwarded BACK went to now disarms the machine's failsafe (the
  old code cancelled it on any update of that surface id); U2 only disarmed it on hide, which would
  have closed a `handlesBack` plugin that answered BACK by navigating inside itself.
- `NativeApp` external state has no end signal: it is left by an open-launcher, a surface or the
  camera, and until then keys pass as in Hidden.
- `ExternalEnded(CAMERA)` comes from the `:camera` overlay-visibility edge, the only main-process
  signal that the camera activity left the display.
- With the accessibility service not connected an overlay-path surface still falls back to its
  activity as before, but it is not moved back onto the overlay when the service returns.

### U3b + U4 (home layer: list and grid, design-system pixels)

Implemented in `glasses-hub/.../glasses/hud/`: `HomeLayer` holds one `HomeViewModel(entries,
selectedId, mode, status, tileData)` and draws it with `ListHome` or `GridHome`, both built on
`HomeScreenView` (header, body, status slot, position indicator). `LauncherMenuView`,
`GridLauncherView` and `TileGridContainer` are deleted.

- Views are keyed by plugin id and diffed in place. A selection move calls `setFocused` on the old
  and the new item only; an entry change adds, removes and re-positions by id; a tile-data change
  swaps or rebinds that one tile. Nothing calls `removeAllViews` and there is no `ScrollView`
  (`GridColorLiteralLintTest` enforces both). `TileCache.observe` notifies an attached `HomeLayer`
  of every write, which fixes F-10.
- Units: `RokidHudTokens` and `HudFrameLayout` are physical pixels now (safe-x 16, safe-y 12,
  content 448, text via `applyTextSize`/`COMPLEX_UNIT_PX`). The only dp left is the synced HUD top
  inset, converted once in `HudTopInset.toPx`. Blast radius: the two files above, `FallbackTileView`,
  `LiveTileView` and the home layer; `phone-hub` does not reference them.
- Vertical budget on 480x640: header 16, `space-1` gaps, a 24 px `Status` slot, so the body may take
  `640 - 24 - 16 - 8 - 24 = 568` px. The list shows every whole 32 px row that fits (14, i.e. 552 px),
  the grid every whole 106 px tile row (5, 562 px). The body is only as tall as its content, so the
  failure strip sits directly under the last row or tile row. A HUD top inset takes rows away
  instead of pushing the body off the screen. This deliberately exceeds `ListItem`'s "3-4 items";
  the owner asked for the whole screen.
- List rows: 32 px, 20 px icon, `body` label; focused = `surface-selected` fill + 2 px `focus`
  border + `focus` text; at rest a hairline `line` border. The list scrolls by its own offset with
  one row of context around the selection. Grid tiles: 106 px unit, `space-2` gaps, sizes from
  `TileLayoutStore`, whole-row scrolling (a TALL/LARGE tile always shows whole), selection order =
  packer order. Fallback tiles stack icon over name (an icon-beside-name header leaves ~70 px and
  cut "NAVIGATION"). Live tiles take the same focus chrome (dashed for `WARN`); a stale snapshot dims
  the content only.
- `Opening` = `Loader` (scan, 1200 ms, one static frame under reduced motion) on the selected row
  ("OPENING" label) or tile (bottom edge), only while the open is in flight. A failed open is
  `Status warn` (alert icon, dashed `line-control` border) until the next input or 4 s. The empty
  state is `Status off`.
- For U5: the morph starts at `HomeLayer.itemBounds(pluginId)` (row or tile rect in home-layer
  coordinates, scroll offset included) and ends at the safe area (16, 12 + inset, 448 wide) of the
  app layer. The row and tile views are stable per id, so the morph can animate the real view or a
  ghost of its rect without rebinding anything. `HudLoaderView` is the loader to reuse inside the
  morphing panel. `TileExpansionAnimator`/`DownscaleBlur` are untouched and unused by the home layer.

### U5 (motion, and two live-tile fixes)

Implemented in `glasses-hub/.../glasses/hud/`: `HudMotionDriver`, `HudMorph` (with `MorphPlan` and
`MorphPanelView`), focus and scroll motion in `HomeScreenView`/`ListHome`/`GridHome`, and the item
views. `TileExpansionAnimator` and `DownscaleBlur` are deleted; `shared/.../motion/TileRectTween`
stays and is the morph's rect interpolation.

- One driver. `HudMotionDriver` owns the frame source (`Choreographer`, or a hand-stepped clock in
  tests), the one easing (fast-out-slow-in 0.4/0/0.2/1) and the reduced-motion check, which is read
  when an animation starts. `animator_duration_scale == 0` lands every value on its target inside
  the call; any other scale multiplies the durations, which is how the emulator runs read a 220 ms
  morph frame by frame. Durations are `RokidHudTokens` only: structural (morph), default (focus
  ring, scroll offset), feedback (surface cross-fade), scan (`Loader`). `DURATION_STRUCTURAL_MS` is
  now 220, as this document has always said; the 320 in the source tokens was never used by a
  shipped animation. `HudMotion`/`HudMotionValue` stay for the notice, Ink and chart animations
  until U6/U7.
- What triggers what. `HudHost.sync(screen)` compares the previous and the new machine screen and
  `HudMorphPlan.of` (pure, JVM-tested) says what to animate: Home to Opening = **open** (panel grows
  from `HomeLayer.itemBounds(id)` to the app safe area, `Loader` inside); Opening to App with
  origin HOME = **reveal** (the surface appears clipped to the panel and cross-fades in, 120 ms);
  Opening to Home (deadline, failure, Dismiss) and App(origin HOME) to Home = **collapse** onto the
  item; everything else, including every Hidden and External transition and a launcher opened over
  a surface, is **instant**. A selection move also animates the scroll offset and the focus ring at
  `duration-default`; every other change (show, update, tile data) lands at once.
- Interruptibility. Every visual is a function of two values, `progress` (panel at the item = 0,
  at the safe area = 1) and `reveal`. A new plan retargets from the current values, at the speed a
  full tween would have; `Instant`, and a selection move during a close, snap them and lay the
  layers out statically. The driver's callbacks only move views: no animation callback emits a
  `HudEvent`, sends on the bus or reads the machine. Bounds are read after `settleMotion()` ends
  the scroll and focus animations, so a morph never starts from a moving item.
- The panel is `Panel` style (1 px `line`, `radius-panel`, transparent). It starts as the item it
  replaces (fill, 2 px `focus` border, and the item's content as a bitmap) and cross-fades to the
  panel style over the first third of the morph, so no frame has an item frame and a panel frame at
  once. The home is not drawn inside the panel's rect (`clipOutRect`) and is dimmed with a plain
  alpha of 0.48: `text-secondary`'s step, which puts 72 % text at 34.6 %, the design's 0.35. A
  `ground` backdrop sits under both layers while the morph runs (an emulator artifact: `ground` is
  unlit on the optic, but a dimmed layer over the emulator's opaque black would show the wallpaper).
- Live tiles name their plugin: the same `TileHeaderView` as fallback tiles (16 px icon over the
  `label` uppercase name, top-left) on every size, above the live value.
- One critical per screen, one 2 px frame. Focus owns the only 2 px `focus` frame. A live tile
  never draws a 2 px critical frame: `CRITICAL` is the alert icon at 100 % on a solid 1 px
  `text-primary` border and the icon alone blinks 3 times at `duration-default` (then steady, and
  not at all under reduced motion); `WARN` is the alert icon at 72 % on the dashed border. The
  icon carries "critical" because border thickness is the focus vocabulary. When several tiles are
  critical only one is (`GridHome.assignCriticalRoles`): the focused one if it is critical, else
  the first in packer order; the others read as `WARN`. A focused critical tile shows the focus
  chrome and keeps its 100 % icon.

Deviations, with the reason: the settled critical tile is not the Status contract's 2 px border (it
would put a second 2 px frame on screen beside the focus ring); `WARN` live tiles gained the
contract's alert icon (needed so a demoted critical still reads as an alert); the surface content
itself is not restyled and its layer has no frame after a reveal (U7).

For U6 (ambient z-order): the host window is still the single window; nothing in U5 touches the
ambient windows. For U7 (surface content): a reveal clips and fades the whole `AppLayer`, so any
surface content that draws outside the 16/12+inset/448 safe area is clipped while it appears, and a
see-through card (Ink) pops from the `ground` backdrop to the real world when the morph ends on the
emulator only.

### U6 (AmbientStack, and the late show of a cancelled open)

Implemented in `glasses-hub/.../glasses/hud/AmbientStack.kt`. `HudOverlayStack` is deleted and the
five `ensureOnTop()` functions with it.

- **Declared order, bottom to top:** `HOST` (launcher and surfaces), `BADGE`, `PIN`, `ACTIVITY`,
  `NOTICE`, `POINTER`. This is 01 §1.3's intended stack (`pin < activity < notice < pointer`) with the
  host underneath, and it is the architecture §3 decision "pin always below notice". The badge sits
  just above the host: it only exists while no Nexus window is up, so its place matters only if a
  host appears later, and there it must not end up on top. `AmbientLayer.focusable` is true for
  `HOST` alone (HARDWARE W4); every ambient window keeps `NOT_FOCUSABLE | NOT_TOUCHABLE`, which the
  emulator confirms with `dumpsys window` while keys still reach the host.
- **Every add goes through the stack.** `HudHost.attach`, and the pin, activity, notice, pointer and
  badge renderers, call `AmbientStack.main.added(window)` right after their `addView` and
  `removed(layer)` when the window goes. F-17 is closed: a pin created under a live notice, a notice
  created under the pointer, and a host attached under any of them all end in the declared order.
- **How reordering works.** Android has no z-index for accessibility overlays and no call that moves
  one, so the only tool is removing and adding a window again; the stack minimises it. It mirrors the
  window manager's order. A new window is on top, so only the windows that must be above it (and
  are now below) are re-added, lowest first; adding in the declared order re-adds nothing, and a
  notice appearing over a pin touches neither. A window that is animating (`AmbientWindow.isAnimating`:
  the notice's slide or fade, an activity island's spring) is not re-added mid-animation: it is
  re-added when its animation goes idle (`HudMotionValue.onIdle`, `HudIslandView.onIdle` call
  `animationEnded()`), with a 2 s backstop for a missed signal. The windows that belong above it
  wait behind it, so each is re-added once. The cost is that a notice sliding in under a host or pin
  added at that moment is covered for the rest of its slide (about 280 ms; emulator capture in
  slow motion), which was judged better than cutting the slide. `updateViewLayout` is not used for
  ordering.
- **Camera (F-16).** The intent is that pin, notice and activity hide over the camera: BUSSPEC says
  so for the pin, plan 011 says the pin hook "hides the notice too", `CameraOverlayView` calls both
  setters, and `noticeOwnsRingInput` is false while the camera is up (item 139). HARDWARE C2's
  "no HUD element other than notices" contradicts the code and was not followed. The flag was only set
  inside the `:camera` process, where those singletons have no window. `CameraOverlayView` now
  reports only the bridge edge and `AmbientStack.setCameraOverlayActive` fans it out in the main
  process to `PinController`, `NoticeController` and `ActivityController`. The pointer stays (remote
  input has to remain visible); the badge only exists over the ROM launcher.
- **Late show of a cancelled open.** `HudState.cancelledOpen` records the plugin and the open's own
  deadline when a Dismiss cancels `Opening`. A `SurfaceShown` matching that plugin (`matchesOpen`)
  before the deadline emits `CloseApp(surfaceId, OPEN_CANCELLED)`, changes no screen and attaches no
  window, so nothing is drawn or flashed. `SurfaceController.closeFromHud` handles `OPEN_CANCELLED`
  exactly like a wearer dismissal (BACK is forwarded to the plugin), so the wire has no new value.
  After the deadline the show is unsolicited as before. Selecting the plugin again clears the
  record, and a show of the surface already beneath the launcher is an update, not a late answer.
  Only a Dismiss cancels this way; a launcher toggled away from `Opening` keeps the old rule.

Deviations and limits: the badge cannot be exercised on the emulator (it needs the ROM launcher's view
ids), so its place is covered by the JVM permutation test only; the camera path was driven with the
debug `CameraFixtureActivity`, not a real camera session.

For U7: nothing in surface content depends on window order. The activity and notice renderers still
own their visuals and state machines; only their window handling moved.

### U7a (plugin-surface content in the design system)

Implemented in `glasses-hub/.../glasses/`: `SurfaceHudView`, `MediaHudView`, `MediaProgressView`,
`ImageHudView`, `ReaderSurfaceView`, the new `SurfaceStyle.kt` (`SurfaceType` text styles,
`SurfaceChrome` outlines) and the Ink palette in `InkHudView`. None of them uses `BusTheme` colors
any more (`BusTheme.dp` remains only in the Ink card geometry shared with the notice band, U7b).
`RokidHudTokens` gained `heading` and `display` (16/22 and 22/28, semibold).

- **Host.** Full-bleed surfaces are laid out on the app safe area: 16 px sides, `12 + HUD top inset`
  (converted once with `HudTopInset.toPx`) at the top, 12 px at the bottom. Inside it: the status
  row (`mono`, `text-secondary`) and one `Panel` (1 px `line`, `radius-panel`) that holds title
  (`heading`), subtitle (`body-small`), the content and the footer. The morph's panel ends on the same
  rect, so the frame it fades out of is the one the surface fades in. A see-through surface removes
  the panel border along with the rest of its chrome. The Ink card stays a direct child of the host
  with no chrome, as before. `surfaceHostChrome`'s legacy dp padding is kept because
  `InkCardPresentationTest` pins it; the view no longer reads it for full-bleed surfaces.
- **Plain card and lyrics.** The body takes the largest of `display`, `heading`, `body` that fits (a
  short message is the screen's one `display`; long text shrinks instead of losing its tail); the
  current lyric line is `display` at `focus`, neighbours `body` at `text-secondary`.
- **Rows.** Board rows: route as a `line-control` outlined `data` chip, destination `body`, wait times
  `data` then `mono`. List rows are `ListItem`s: 32 px minimum, `body` title, `body-small` sub line,
  `data` value at the end, the selected row in the focus chrome (`surface-selected`, 2 px `focus`).
  `ALERT` shows the alert icon and a heavier title, `DIM` is `text-secondary`. The row window
  (`surfaceListViewport`) is untouched; its overflow markers are chevron icons and a `mono` count.
- **Media.** Title is the screen's `display`; artist `body`; album `body-small`; the bar is `Loader`
  `progress` (`text-primary` on `line`); elapsed and duration are `data`, the state a `label`. The
  artwork takes whatever height the text leaves (max 224 px) and is dropped below 64 px.
- **Reader.** `ScrollView` is gone: the reader scrolls by `View.scrollTo` with a `duration-default`
  tween through `HudMotionDriver`, with a 2 px position track, so S5 holds here too. Headers are
  `label` (speaker in `text-primary`, `focus` when emphasised, uppercase), prose `body`, aside
  `body-small`. `resolveReaderScrollTarget` and the page step (45 % of the viewport) are unchanged.
- **Editable card.** The field is the focused control: `surface-selected` fill, 2 px `focus` border,
  `focus` caret, `heading`-sized text.
- **Image.** Same drawing, framed by the panel; decoded pixels are the only non-token pixels.
- **Ink.** The SDK leaves colors to the host (`InkColorPalette`, tiers by name). The tiers became
  intensities of the one hue: accent 100 %, text 72 %, muted and dim 48 %, `danger` = `critical`
  (100 %; the design has no red), black = ground. `dim` is 48 % and not the 24 % `line` step because
  that step is never text. Literal colors are still matched to a tier with the historical anchor
  colors (`InkTierReference`, carried in `InkColorPalette.matching`), so a red literal is still
  `danger`; with one-hue tiers nearest-color matching would send everything to the same tier.
  Typography (monospace, sp sizes) and every layout metric are unchanged, since rpx layout and
  wrapping depend on them; the first-frame gate and morph code are untouched.

Fixed baseline bugs: media title, artist and progress were pushed out of the screen (a fixed 168 dp
artwork ahead of them); list-row `badge` values were never drawn for list rows (only for `BODY`).

Deviations and limits: emulator captures are on an opaque black ground, as always; `RokidHudTokens`
`heading`/`display` use the semibold cut, not a true 600; the media artwork placeholder is a "note"
glyph in an outlined square (the icon set has no music icon); Ink text stays monospace; the
`ALERT` row is told apart by icon and weight only. The notice, pin, activity, action row and
waveform views (`HudActionRowView`, `HudWaveformView`, `ParagraphTranslationLayout` users) are U7b.
`SurfaceScreenshotTest` renders every kind at 480x640 and checks the single hue on the pixels.

### U7b (ambient layers in the design system, BusTheme retired)

Implemented in `glasses-hub/.../glasses/`: `AmbientStyle.kt` (new: the floating `Panel`, glyph tinting,
the ambient motion driver and its idle-reporting value), `NoticeOverlayRenderer` (`NoticeBandView`),
`NoticeComposeMirror` users, `PinOverlayRenderer` (`PinPanelView`), `ActivityOverlayRenderer`,
`ActivityExtrasViews`, `HudIsland`, `HudActionRowView`, `HudWaveformView`,
`StatusBadgeOverlayRenderer`, `RemotePointerOverlayRenderer`, plus `MainActivity`'s setup screen and
`CameraActivity`'s empty state. `BusTheme` and `NexusUi` are no longer referenced anywhere in
`glasses-hub` (main or test code; `GridColorLiteralLintTest` now fails on either name in any main source
and on any color literal outside `RokidHudTokens`). `BusTheme` stays in `bus-client` for `phone-hub` and
the plugins.

- **Panels.** A floating window needs an occluding fill, so the notice band, the pin and the activity
  island are `Panel`s with a `ground` (black) fill, 1 px `line`, `radius-panel` and `space-3` padding;
  `ground` is unlit on the optic and keeps the windows below out of the text in the compositor
  (`AmbientStyle.panel`). No grey, no tinted fill.
- **Notice band.** `heading` title, `body` message at `text-primary` (sans, 14/20, wrapped to the same
  line-based page capacities as before: 8 lines compact, 14 pageable), `body-small` footer and `mono`
  page counter at `text-secondary` (the counter is right-aligned even without a footer). The action row
  is a row of `Button`s (32 px, `line-control` outline, 16 px icon, `body` label); the selected chip is
  the row's one primary button in the focus chrome (`surface-selected`, 2 px `focus`). The inline compose
  field is the focused control (same chrome, `focus` caret, `text-secondary` placeholder). An answer that
  could not be sent ("Delivery not confirmed") is `Status warn`: alert icon, dashed `line-control`
  border.
- **Pin and activity chip.** `PinPanelView` is `body`/`body-small` (small) or `heading`/`body`
  (medium); a `bright` line is `text-primary`, others `text-secondary`; the chip's measure is `data`
  (mono). The glyph is a 24 px icon tinted with the text intensity (plugin glyph drawables keep their
  geometry; their legacy green is replaced by a color filter).
- **Activity island.** Outline `line` 1 px, `radius-panel`. The panel's primary is the screen's one
  `display` value fitted between 22 and 16 px (`fitActivityPrimary` now works in px; its floor is
  `heading`), the ETA a `data` readout, the secondary `body`, details `body-small`; progress is
  `Loader progress` (`MediaProgressView` plus a `mono` percentage) or `Loader scan` (`HudLoaderView`) when
  indeterminate. The route badge is a `line-control` `data` chip sized to its text; the track is
  `text-secondary` passed dots and segments, `text-primary` current, `line` hollow later stops.
- **Critical.** An urgent flare is `Status critical`: the alert icon at 100 %, a 2 px `focus` outline
  that blinks three times at `duration-default` (`CriticalBlink`, one shared step chain) 380 ms after the
  band has arrived and then stays; under reduced motion it is steady. It replaces the old spring wobble
  ("beat") of the outline. `BUSSPEC.md` and `docs/PLUGIN_SDK.md` still describe the outline as one that
  "beats once"; the wire and SDK are untouched and that sentence was not edited (owner call). Only one
  flare exists at a time, so there is one critical from this layer; a critical live tile on the home
  below is a second one the ambient windows cannot see.
- **Pointer.** A `focus` ring (2 px, radius 12 = half an `icon-lg`) with a `focus` center dot and a
  black halo; the white ring and the off-palette green are gone. Position math uses `HudGeometry`.
- **Waveform.** `text-primary` bars over a `line` rest level, 2 px bars on a 6 px pitch, 32 px tall.
  Nothing instantiates it today.
- **Status badge.** Colors only: the glyph and the `mono` numerals are `text-primary`. Its size and
  placement stay in dp/sp and read from the ROM row, because the chip must match the ROM's 20 px icons
  and ~11 sp labels (HARDWARE D3 and S6, `StatusBadgeGeometry`); the swap to token pixels would have
  changed the row it is tuned to disappear into.

Geometry. Pin, chip and panel sit at `safe-x` 16 / `safe-y` 12 (+ the synced top inset, converted once
with `HudTopInset.toPx`) from their corner; the panel is 78 % and the pin 45 % / 60 % of
`HudGeometry`'s 480 px viewport, and the band's height ceiling is a fraction of its 640 px. The notice
band and the activity flare keep the Ink card's width (441 px) and top (`12 dp + inset`), which stay in
dp in `HudBandGeometry`: the card's rpx layout is measured on that width and the band morphs into the
card, so moving one moves the other (`InkCardPresentationTest` still pins 441 and `dp(52)`; the test
now spells `dp` and the ground color without `BusTheme`, the assertions are the same values). No
ambient layer reads `displayMetrics` any more except the badge (D3).

Motion decision. The notice's slide and fade moved to `HudMotionDriver` (`AmbientMotionValue` wraps it
and calls the idle hook `AmbientStack` waits for after an end, and after a snap or cancel that stopped
an animation). Old 280 ms enter and 240 ms exit are `duration-structural` (220); the 180 ms Ink fade is
`duration-default` (200); one easing replaces the enter and exit curves. The animation scale now
applies to the notice too (verified at 3x and 6x, and 0 lands instantly). The bitmap release delay in
`NoticeController` follows the exit duration. Kept on the old vocabulary: `HudIslandView`'s damped
springs (physics, not a duration token; its cross-fade and form timings are unchanged), the flare's
`STANDARD_MS + HOLD_MS` dwell (3 780 ms, a hold rather than a tween), and `HudMotion` for Ink, chart and
`SurfaceHudView`, which are not ambient layers.

Deviations and limits. The plugin glyph vocabulary (`NexusGlyphs`) is the SDK's, not the design's fixed
icon set; the drawables are only recolored. `MainActivity`'s setup screen and the camera's empty state
were restyled with tokens, but `CameraOverlayView` (the camera HUD, with cyan, amber and red status
colors) is out of scope with the camera pipeline and is the one main source the lint exempts besides the
Ink tier anchors. The home's own 2 px focus ring stays visible under a notice or flare that draws
another, because the windows cannot coordinate. Two activities can still overlap when a wide panel and a
chip share the top row (unchanged from before). The status badge's new colors were only rendered on
Roborazzi: it needs the ROM launcher on the device and cannot be exercised on the emulator.

### Review fixes (independent review of U1-U7b)

Every item has a test that failed before the change (JVM or Robolectric) except where noted.

- **Host attach failure.** `HudEvent.HostAttachFailed` is dispatched when `HudHost.attach()` returns
  false for the launcher. A Home or Opening falls back to what it was opened over (foreign content) or
  to Hidden, which cancels the open deadline, detaches and publishes `ringFocus=false`; keys then pass
  to the system instead of vanishing into an invisible launcher. `showApp` keeps its own activity
  fallback. `HudControllerTest` runs it against a window manager that refuses `addView`.
- **`MainActivity` and the app icon.** Only a start of the activity (`onCreate`, `onNewIntent`: the app
  icon or a setup hand-back) sets `openLauncherRequested`; a plain resume or a store broadcast never
  does, so the launcher is not requested over an App screen. The flag stays set until the launcher
  really opened; with the service not connected the setup view shows "Nexus is not running" (a
  Settings shortcut, retried every 2 s) instead of finishing to a blank task. `LauncherHandoff.decide`
  holds the rule and is unit tested; the "not running" view itself could not be reached on the
  emulator (removing the service also moves the setup snapshot back to an onboarding stage).
- **Cancelled open (`OPEN_CANCELLED`).** BUSSPEC has no event for "the hub hid a surface that was never
  displayed": `/surface/input` with `KEYCODE_BACK` is the only close signal a non-Ink plugin ever gets,
  and it means the wearer pressed BACK. So the surface is hidden locally and **nothing is sent**
  (`CloseReason.forwardsBackToPlugin()` is true only for `WEARER_DISMISSED`). An Ink surface keeps the
  `/ink/event closed` (`CLOSE_USER`) it always gets, since that event exists for exactly this end of
  its session. No new wire value. A re-show inside the cancelled window is closed the same way and
  sends nothing that could start another show; after the open's deadline it is an unsolicited show
  as before. The plugin is left believing its surface is up until then: an accepted cost of having
  no wire signal for it. Emulator: `plugins8-slow`, dismiss during `Opening`, the late show logs
  "Surface closed unseen" and the fake-phone log has no `/surface/input`.
- **One focus frame under a notice.** `HudState.noticeOwnsRing` reaches `HomeLayer.setNoticeOwnsRing`
  from the settled sink; `HomeViewModel.focusedId` is null while it is true, so the selected row or
  tile draws rest chrome (line border, no fill, no focus text) and the critical-tile choice ignores
  it. The header counter still shows the selection. Captures: `list-07-notice-owns-ring`,
  `grid-09-notice-owns-ring` (Roborazzi) and the emulator pair 18/19.
- **Second `onServiceConnected`.** It releases the previous connection first (same path as a destroy:
  the machine gets `ServiceDestroyed`, the host window is removed, observers and timers stop); a late
  destroy of the old service is ignored.
- **Threading.** `toggleLauncherFromBroadcast` posts to the main looper from any other thread and
  answers `queued`; `dispatch` is private; `HudRunner.state` is `@Volatile`.
- **Camera over a launcher over a surface.** The camera entry now closes the visible surface with
  `SUPERSEDED` like every other external launch.
- **Entries refresh.** `LauncherEntriesChanged` carries an `appearance` map (name and icon key per
  id); a change with the same ids emits `RefreshHomeEntries` while the home is up.
- **Live tiles while hidden.** `HomeLayer` loads, observes and binds tile data only while it is
  `VISIBLE` and refreshes from the cache when it becomes visible, so a critical blink is not spent
  under an app or a detached host.
- **Owed UPs.** Each owed UP is stamped with its DOWN's event time and dropped after 5 s (a held key's
  repeats refresh it), so a lost UP cannot swallow the UP of a later press.
- **Reader keys.** A reader forwards only BACK, ENTER and DPAD_CENTER (`READER_FORWARDED_KEYS`); SPACE
  and `MEDIA_PLAY_PAUSE` pass to the system as before the rewrite.
- **Density of the band top: kept in dp.** `HudBandGeometry.topPx` is `(12 dp + inset) x density`, the
  rest of the layers use `12 px + inset x density`. `InkCardPresentationTest` pins the Ink card's top as
  `52 dp`, so unifying the unit would change what that test asserts, not restate it. It is recorded as
  HARDWARE Q17 until the density is confirmed on a device.
- **Debug injection.** `RawKeyEvent` carries `downTime` and `deviceId`; the receiver gives an injected
  DOWN/UP pair one down time and a virtual device id, so the notice's press identity matches
  (`HudKeyEventAdapter.toKeyEvent`). `HudInputSeam.sink` is only wired when `BuildConfig.DEBUG`.
  The fake phone now logs every outbound envelope other than `/launcher/open`.
- **Cleanup.** Removed `HudController.onSurfaceContentChanged`, `HomeLayer.clearStatus`,
  `HudWaveformView` (nothing instantiated it; U7b's note about it is history), `HudSpring.BEAT`, the
  `RingSurfaceInputPolicy` class and test (its keycode constants are `RingSurfaceKeys`), and the
  `LiveTileViewTest` case that asserted `true`.
- **Docs.** `docs/grid-hud-roadmap/00-05` carry a supersession banner (03 and 04 say what stays
  current); the stash references in EMULATION are replaced by the fact they cited; the `RokidHudTokens`
  KDoc no longer names the deleted grid view. By the owner's decision the urgent-flare sentences in
  BUSSPEC.md and PLUGIN_SDK.md now say "blinks three times, then stays" (wording only), which closes
  the note in U7b that they were left unedited.
- **Frame analyzer.** `tools/emulator/analyze-frames.py`, `frame-sheet.py` and `dense-capture.sh`
  (docs/EMULATION.md): a frame is either the ROM wallpaper (`home`) or Nexus content, so a green
  panel or a photo is no longer taken for the launcher, and `--fail-on-home` asserts "no frame without
  a Nexus window".

The B1 back guard is removed (`unclaimedBackGuardMs`, `SwallowBack` and the dismissal timestamp are gone): keys in `Hidden` belong to the ROM.


### Parity coverage

Every item of `01-current-behavior.md` §7, mapped to what covers it. **Tests** are JVM/Robolectric/Roborazzi test classes and cases in `glasses-hub` unless another module is named (`*` = a family of cases). **EMU** marks an item whose behavior needs the emulator (real windows, key injection); it was exercised by the delivery agents under §4 and their captures are described in the delivery notes above, but the raw frames were pruned and are not indexed per item, so an EMU cell is a claim to re-verify on the API 32 run, not fresh evidence. **DEVICE** marks items only real glasses can settle. **Changed** cites the decision or delivery that changed `HEAD` behavior, so the test asserts the new behavior. A `-` in Tests means no automated test exists (the item is EMU or DEVICE only).

| # | Tests | EMU | DEVICE | Intentionally changed / note |
|---|---|---|---|---|
| 1 | - | EMU |  | Window is now HudHost's one window (§2.3); params not asserted on the JVM |
| 2 | - | EMU |  | Overlay-path surfaces share HudHost's window (§2.3, U3) |
| 3 | AmbientScreenshotTest (pixels only) | EMU |  |  |
| 4 | AmbientScreenshotTest (pixels only) | EMU |  |  |
| 5 | - | EMU |  |  |
| 6 | - | EMU |  |  |
| 7 | AmbientStackTest: the_declared_order_..., every_add_order_of_the_six_windows_... | EMU |  | Order owned by AmbientStack (§2.7, U6) |
| 8 | AmbientStackTest: a_new_window_re_adds_only_the_windows_that_belong_above_it | EMU |  | The surface is now HudHost, layer HOST, below every ambient layer |
| 9 | AmbientStackTest: the_host_attaching_puts_every_visible_ambient_window_back_above_it_once | EMU |  |  |
| 10 | AmbientStackTest: F17_a_pin_created_while_a_notice_is_visible_... | EMU |  | Decision 10: pin always below notice |
| 11 | HudHostTest: the_window_is_added_once_...; HudStateMachineTest: item_22_... | EMU |  |  |
| 12 | HudHostTest: item12_detach_is_guarded_when_the_host_is_not_attached, the_window_is_added_once_...; HudRunnerTest: the_host_is_attached_once_... | EMU |  |  |
| 13 | HudStateMachineTest: item_13_14_15_...; item_77_...; HudControllerTest: a_second_connect_without_a_destroy_... | EMU |  |  |
| 14 | HudStateMachineTest: item_13_14_15_reconnect_restores_an_overlay_surface_but_not_the_launcher; SurfaceControllerHudTest: item15_* | EMU |  |  |
| 15 | HudStateMachineTest: item_13_14_15_..., item_15_F25_...; SurfaceControllerHudTest: item15_the_active_surface_survives_..., item15_a_surface_hidden_while_the_service_was_down_... |  |  |  |
| 16 | SurfaceHudSeeThroughTest (see-through card, not the focus veil) |  | DEVICE | HudHost sets defaultFocusHighlightEnabled=false; only a device shows the veil |
| 17 | ListHomeTest/GridHomeTest: whole-row fit inside the safe area; HudHostTest: the_viewport_is_the_one_configured_value |  | DEVICE | Canvas is 480x640, not 480x352 (HARDWARE Q1/Q2) |
| 18 | HudStateMachineTest: item_18_...; HudInputTest: T2_three_contacts_at_0_250_500_...; TouchpadGestureDetectorsTest: tripleTap_triggersOn...; HudControllerGlueTest: a_triple_tap_opens_the_launcher_in_one_persistent_window, the_third_contact_..._is_consumed | EMU |  |  |
| 19 | TouchpadGestureDetectorsTest: tripleTap_dropsContactsOutsideWindow, tripleTap_normalClassificationsClearPendingContacts; HudInputTest: T2_window_edges_600_triggers_601_does_not, T2_three_fast_swipes_never_trigger |  |  |  |
| 20 | TouchpadGestureDetectorsTest: tripleTap_suppressesTrailingBackAndEnterClassifications; HudInputTest: T2_classifications_are_swallowed_for_800ms_and_pass_at_801 |  |  |  |
| 21 | TouchpadGestureDetectorsTest: tripleTap_expiryReturnsSingleAndDoubleTapCounts, tripleTap_expiryReturnsNothingAfterTriggerOrSwipeClear |  |  |  |
| 22 | HudStateMachineTest: item_22_...; HudInputTest: T2_trigger_works_while_the_launcher_is_already_shown | EMU |  |  |
| 23 | HudStateMachineTest: item_23_...; HudInputTest: editable_card_passes_everything_but_back_and_never_triggers | EMU |  |  |
| 24 | HudStateMachineTest: item_24_...; HudControllerTest: the_broadcast_toggle_from_another_thread_...; HudControllerGlueTest: the_broadcast_toggle_and_the_app_icon_... | EMU |  |  |
| 25 | HudStateMachineTest: item_25_...; HudControllerGlueTest: dismissing_the_home_hides_the_window_and_frees_the_ring, back_dismisses_the_notice_first_... | EMU |  |  |
| 26 | HudInputTest: item124_taps_resolve_..., item126_ring_double_tap_...; RingTapPolicyTest | EMU |  | Ring double tap is a Dismiss intent; on R08 it also reaches the launcher (§2.2) |
| 27 | RingTapPolicyTest (4 cases); HudInputTest: R5_taps_340ms_..., R5_exactly_350ms_... |  |  |  |
| 28 | HudStateMachineTest: item_28_...; HudInputTest: launcher_maps_and_consumes_every_key_including_unmapped_ones | EMU |  |  |
| 29 | HudInputTest: T3_swipe_pairs_at_20_50_80ms_count_once_...; HudControllerGlueTest: a_swipe_moves_the_selection_and_select_sends_...; TouchpadGestureDetectorsTest: dpadPairDedupe_* | EMU |  |  |
| 30 | TouchpadGestureDetectorsTest: dpadPairDedupe_countsPairedForwardCodesAsOneSwipe, dpadPairDedupe_countsPairedBackwardCodesAsOneSwipe |  |  |  |
| 31 | HudInputTest: item132_..., launcher_maps_...; ring 87/88 wrap: HudStateMachineTest: item_32_... | EMU |  | Decision 132: a ring on a non-R08 device is a launcher no-op |
| 32 | HudStateMachineTest: item_32_selection_wraps_both_ways_and_empty_list_does_not_move |  |  |  |
| 33 | HudStateMachineTest: item_33_selection_persists_across_close_and_open; HudControllerGlueTest (selection survives a reconnect) | EMU |  |  |
| 34 | HudStateMachineTest: item_34_* (4 cases) |  |  | Decision 34: selection by plugin id |
| 35 | HudStateMachineTest: item_35_select_sends_launcher_open_...; HudControllerGlueTest: a_swipe_moves_the_selection_and_select_sends_the_launcher_open_through_the_hub | EMU |  |  |
| 36 | GlassesHubLauncherTest: item36_a_blank_id_..., item36_the_camera_entry_without_a_hub_context_..., item36_a_send_error_...; HudControllerGlueTest: a_send_that_fails_returns_to_the_home_with_a_status, item36_a_camera_that_cannot_start_keeps_the_launcher_and_says_so; HudStateMachineTest: item_37_open_failure_... |  |  | Failure now becomes an OpenFailed status on the home (§2.1) |
| 37 | HudStateMachineTest: item_35_..., item_37_*; HudControllerGlueTest: the_surface_that_answers_the_open_shows_in_the_app_layer_and_dismiss_returns_home | EMU |  | Handoff is the machine's Opening state (§2.1) |
| 38 | HudStateMachineTest: item_38_*; GlassesHubLauncherTest: item36_38_the_camera_entry_starts_the_camera_activity_...; HudControllerGlueTest: item36_38_the_camera_entry_starts_... | EMU |  |  |
| 39 | - |  | DEVICE | Gap measured on the device only |
| 40 | ListHomeTest: the_header_counts_position_and_the_empty_state_is_a_status |  |  | Empty state text is now the Status component (§2.4) |
| 41 | ListHomeTest: rows_are_32_px_..., the_focused_row_is_the_one_focus_element |  |  | Rows are the design-system ListItem, 32 px (§2.4), not 18 sp with a 2 dp outline |
| 42 | HudModeStoreTest; HudModeContractTest |  |  |  |
| 43 | HudModeContractTest |  |  |  |
| 44 | GlassesHubLauncherTest: item44_a_valid_hud_mode_config_is_stored_..., item44_an_invalid_hud_mode_payload_leaves_the_stored_mode_unchanged |  |  |  |
| 45 | HudStateMachineTest: item_45_mode_change_applies_on_the_next_open_only; HomeLayerTest: the_rendering_follows_the_mode_and_is_kept_while_the_mode_stays | EMU |  |  |
| 46 | TileControllerTest: while_inactive_publish_is_consumed_but_never_cached; GlassesHubLauncherTest: item44_a_valid_hud_mode_config_... (starts/stops the subsystem); HomeLayerTest: tile_data_is_ignored_in_list_mode |  |  |  |
| 47 | TileLayoutStoreTest: a_custom_order_is_applied_...; TileLayoutIntegrationTest; GlassesHubLauncherTest: item49_...; HudControllerGlueTest: item36_38_... (camera first) |  |  |  |
| 48 | GlassesHubLauncherTest: item48_launcher_list_skips_blank_ids_defaults_the_name_and_drops_a_blank_icon_key, item48_a_list_without_plugins_is_an_empty_launcher |  |  |  |
| 49 | GlassesHubLauncherTest: item49_a_tile_layout_push_re_notifies_launcher_observers_in_the_new_order, item49_an_invalid_tile_layout_notifies_nobody |  |  |  |
| 50 | AmbientStackTest (host never above an ambient layer); HudControllerGlueTest: a_visible_notice_takes_enter_before_the_launcher_does | EMU |  | Notice keeps ring priority: HudInput asks the notice first (§2.2) |
| 51 | TileGridPackerTest (8 cases) |  |  |  |
| 52 | GridHomeTest: default_eight_plugins_fill_two_rows_..., wide_tall_and_large_tiles_use_the_layout_store_sizes |  |  | GridLauncherView replaced by GridHome (§2.4) |
| 53 | GridHomeTest: a_selection_move_changes_focus_on_two_tiles_..., live_and_fallback_tiles_show_selection_and_focus_alike |  |  |  |
| 54 | GridHomeTest: live_and_fallback_tiles_...; HomeLayerTest: a_tile_data_write_refreshes_an_open_grid_in_place; LiveTileIdentityTest |  |  |  |
| 55 | GridHomeTest / LiveTileIdentityTest (fallback tile name and focus chrome); GridColorLiteralLintTest |  |  | Fill/stroke now come from HomeChrome tokens |
| 56 | GridHomeTest: live_and_fallback_tiles_show_selection_and_focus_alike |  |  | Decision 56: live tiles show selection and focus |
| 57 | LiveTileViewBindingTest: item57_* (7 cases); LiveTileViewTest |  |  |  |
| 58 | LiveTileViewTest: every_tone_renders_...; LiveTileIdentityTest; CriticalBlinkTest |  |  | Tone vocabulary redone by U5/U7b: WARN dashed alert icon, CRITICAL one blinking tile |
| 59 | TileCacheTest: staleness_boundary_... |  |  |  |
| 60 | TileRateLimiterTest (4 cases); TileControllerTest: publishing_above_the_rate_ceiling_... |  |  |  |
| 61 | GridHomeTest: four_columns_fit_the_448_px_content_width_exactly; HomeScreenshotTest (grid-*) | EMU |  | Decision: 4 columns in 448 px on 480x640; F-8 fixed |
| 62 | HudHostMotionTest; HudMorphPlanTest; HudMotionDriverTest | EMU |  | Decision 62: real 220 ms morph inside HudHost, replaces the 320 ms ghost tween |
| 63 | HudMotionDriverTest: reduced_motion_is_read_when_an_animation_starts_...; HudHostMotionTest: reduced_motion_lands_every_transition_... |  |  | TileExpansionAnimator retired; HudMotionDriver is the reduced-motion owner |
| 64 | HudMotionDriverTest: snap_and_cancel_stop_the_animation_without_its_end_hook, a_retarget_...drops_the_old_end_hook |  |  | No tween callback reaches the machine (§2.6) |
| 65 | HudStateMachineTest: F9_back_during_opening_cancels_it_...; HudHostMotionTest: playing_out_every_animation_produces_no_effect_and_no_state_change | EMU |  | F-9 fixed: BACK during Opening cancels and sends nothing |
| 66 | HudStateMachineTest: item_66_* (3 cases) |  |  | LauncherReturnCoordinator folded into the machine |
| 67 | HudStateMachineTest: item_67_the_claim_is_consumed_by_the_first_hide |  |  |  |
| 68 | HudStateMachineTest: item_66_..., item_69_* |  |  | Pending is token-matched state, not a coordinator field |
| 69 | HudStateMachineTest: item_69_F3_* (4 cases); HudReviewFixesTest |  |  | Decision 69: token-matched, expires at the Opening deadline |
| 70 | HudStateMachineTest: item_70_* (2 cases); HudControllerGlueTest: the_surface_that_answers_the_open_shows_in_the_app_layer_and_dismiss_returns_home | EMU |  |  |
| 71 | HudStateMachineTest: item_71_an_unsolicited_surface_hides_back_to_hidden | EMU |  |  |
| 72 | HudStateMachineTest: item_72_metadata_updates_never_complete_a_handoff |  |  |  |
| 73 | HudStateMachineTest: item_73_app_icon_uses_the_same_launcher_and_return; HudControllerTest / LauncherHandoffTest | EMU |  | [decide] resolved: the app icon returns to the launcher like every other entry |
| 74 | HudStateMachineTest: item_74_ring_focus_is_the_union_and_publishes_only_on_edges; HudRunnerTest: ring_focus_is_published_only_on_edges_... |  |  | RingFocusCoordinator folded into HudState.ringFocus() |
| 75 | HudStateMachineTest: item_75_76_focus_is_held_through_the_handoff_... |  |  |  |
| 76 | HudStateMachineTest: item_75_76_... |  |  |  |
| 77 | HudStateMachineTest: item_77_service_destroy_releases_everything_and_focus |  |  |  |
| 78 | RingFocusPublisherTest: item78_* (2 cases); HudControllerGlueTest: dismissing_the_home_hides_the_window_and_frees_the_ring (edge order) | EMU |  |  |
| 79 | - |  | DEVICE |  |
| 80 | HudStateMachineTest: item_80_activity_path_handoff_steps_the_host_aside | EMU |  |  |
| 81 | SurfaceControllerHudTest: item81_an_ink_surface_whose_overlay_is_unavailable_closes_with_renderer_error, item81_an_ink_surface_shown_with_no_service_closes_with_renderer_error | EMU |  |  |
| 82 | HudStateMachineTest: item_23_..., editable cases; surfaceDisplayPath: InkCardPresentationTest | EMU |  |  |
| 83 | InkCardPresentationTest (surfaceDisplayPath) |  |  |  |
| 84 | - | EMU |  |  |
| 85 | SurfaceControllerHudTest: item85_a_card_whose_overlay_cannot_attach_falls_back_to_the_activity, item85_on_overlay_unavailable_moves_an_on_screen_card_to_the_activity_path; HudStateMachineTest: item_85_F21_...; HudReviewFixesTest | EMU |  |  |
| 86 | SurfaceControllerHudTest: item102_closed_carries_the_reason_... (link_lost) | EMU |  |  |
| 87 | - | EMU |  | Decision 87: HEAD behavior kept (surface stays), flagged for product review |
| 88 | HudStateMachineTest: item_88_native_app_launch_steps_nexus_aside_and_returns_to_hidden | EMU |  | [decide] resolved: native launch steps Nexus aside (External NATIVE_APP) |
| 89 | SurfaceOrderingCoordinatorTest |  |  |  |
| 90 | SurfaceIngressModelTest: item90_* (2 cases) |  |  |  |
| 91 | SurfaceIngressModelTest: item91_an_unknown_kind_is_rejected_and_an_empty_kind_is_a_card |  |  |  |
| 92 | ReaderSurfaceModelsTest: reader_parser_truncates_every_SDK_cap_without_throwing |  |  |  |
| 93 | ReaderSurfaceModelsTest (resolveReaderScrollTarget); ReaderSurfaceViewTest: item93_* (6 cases: own offset, 45% page, clamps, anchors, same-surface re-render) |  |  | Own-offset scrolling replaces ScrollView (HARDWARE S5) |
| 94 | SurfaceListViewportTest |  |  |  |
| 95 | SurfaceHudSeeThroughTest; NoticeComposeMirrorTest |  |  |  |
| 96 | NoticeComposeMirrorTest |  |  |  |
| 97 | SurfaceControllerHudTest: item97_* (5 cases: hardware Enter, IME send, BACK cancel, handlesBack failsafe cancel, no text-committed without a field) | EMU |  | Cancel is now the machine's CloseApp(WEARER_DISMISSED) or BACK_FAILSAFE, not the view |
| 98 | HudInputTest: editable_card_passes_everything_but_back_and_never_triggers | EMU |  |  |
| 99 | ImageSurfaceStateTest; ImageSurfaceContractTest |  |  |  |
| 100 | SurfaceIngressModelTest: item100_* (3 cases); InkRenderLogicTest: document_or_base_revision_mismatch_requests_resync |  |  |  |
| 101 | SurfaceControllerHudTest: item102_ready_is_sent_once_...; InkPresentationGateTest (gate, forced timeout) | EMU |  |  |
| 102 | SurfaceControllerHudTest: item102_ready_..., item102_closed_carries_the_reason_..., item102_a_patch_for_another_document_asks_for_a_resync_...; item115 (action payload) |  |  |  |
| 103 | - | EMU |  |  |
| 104 | - | EMU |  |  |
| 105 | HudInputTest: item105_generic_order_notice_launcher_surface_activity_pass; hook_position_notice_is_asked_before_the_owner_chain_...; HudControllerGlueTest: a_visible_notice_takes_enter_before_the_launcher_does |  |  | Router is HudInput (§2.2) |
| 106 | HudInputTest: item106_*, R6_prog_blue_...; HudStateMachineTest: item_106_prog_blue_is_passed_in_every_state |  |  |  |
| 107 | HudInputTest: item107_*, R3_* (6 cases) |  |  | Owed UPs stamped and expire after 5 s (review fixes) |
| 108 | NoticeKeyInputRouterTest (4 cases) |  |  |  |
| 109 | NoticeKeyInputRouterTest; HudKeyEventAdapterTest (press identity survives the raw event) |  |  |  |
| 110 | NoticeTouchpadInputPolicyTest |  |  |  |
| 111 | HudInputTest: item111_*; HudStateMachineTest: item_111_surface_keys_and_intents_are_forwarded_not_passed; SurfaceControllerHudTest: item124_raw_keys_... | EMU |  |  |
| 112 | HudInputTest: item112_*; HudStateMachineTest: item_112_* (4 cases); SurfaceControllerHudTest: item97_back_on_a_card_that_handles_back_... | EMU |  |  |
| 113 | HudInputTest: item113_*, T3_* |  |  |  |
| 114 | HudInputTest: item114_*, reader_leaves_space_and_media_play_pause_to_the_system; SurfaceControllerHudTest: item114_124_a_reader_scrolls_... |  |  | Reader forwards only BACK/ENTER/CENTER (review fixes) |
| 115 | InkNavigationTest (view rules, 26 cases); SurfaceControllerHudTest: item115_next_moves_the_ink_selection_locally_and_select_emits_the_selected_action, item115_without_a_selectable_action_ink_directions_fall_through_as_a_forwarded_pair |  |  | Ink now receives synthesized KeyEvents from SurfaceController.deliverKey (down time = event time = uptime, repeat 0): the down time itself is not observable without a spy, its effect (one selection move per DOWN) is asserted |
| 116 | ActivityPresentationPolicyTest (canResolveActivityTap); HudControllerGlueTest: item130_* (claim through ActivityController.claimsInput) |  |  |  |
| 117 | HudInputTest: activity_generic_83_is_consumed_directions_need_actions_enter_fires |  |  |  |
| 118 | HudInputTest: item118_* (2 cases), a_non_contact_down_cancels_the_pending_flush |  |  |  |
| 119 | HudInputTest: item119_...; HudStateMachineTest: item_119_* (5 cases) |  |  | Decision 119: launcher has exclusive input |
| 120 | HudInputTest: item120_*, device_classifier_by_name_R1; HudKeyEventAdapterTest |  |  |  |
| 121 | HudInputTest: item121_*; HudStateMachineTest: item_121_hidden_passes_every_key_to_the_system; HudControllerGlueTest: a_hidden_hud_passes_every_key_to_the_system |  |  |  |
| 122 | HudInputTest: item122_ring_precedence_... |  |  |  |
| 123 | HudInputTest: item123_* |  |  |  |
| 124 | HudInputTest: item124_taps_resolve_...; SurfaceControllerHudTest: item124_next_prev_and_select_become_a_dpad_pair_each, item124_raw_keys_keep_their_direction_..., item124_dismiss_forwards_back_down_once_... |  |  | RingSurfaceInputPolicy deleted; mapping is HudInput + SurfaceController.onHudIntent (review fixes) |
| 125 | SurfaceControllerHudTest: item124_next_prev_and_select_... (same events as the touchpad) | EMU |  |  |
| 126 | HudInputTest: item126_*; HudStateMachineTest: item_112_* | EMU |  |  |
| 127 | HudInputTest: item127_reader_ring_scroll_is_deduped_at_150ms; SurfaceControllerHudTest: item114_124_... | EMU |  |  |
| 128 | HudInputTest: item128_*; HudControllerGlueTest: item128_* (4 cases: single tap, direction then tap, double tap, unclaimed key) |  |  |  |
| 129 | HudInputTest: item129_cancel_drops_a_pending_tap; HudControllerGlueTest: item129_* (replaced, closed, update keeps the tap) |  |  | Trigger is HudController's notice observer comparing interaction identity |
| 130 | HudInputTest: item130_*; HudControllerGlueTest: item130_the_activity_a_ring_tap_is_for_is_fixed_by_its_first_tap, item130_a_tap_whose_activity_ended_... |  |  |  |
| 131 | - |  | DEVICE |  |
| 132 | HudInputTest: item132_ring_keycodes_from_a_non_R08_device_are_launcher_noops, debug_seam_injected_R08_sequence_produces_intents; HudKeyEventAdapterTest | EMU |  | Decision 132: debug injection seam carries an R08 device class |
| 133 | - |  | DEVICE |  |
| 134 | NoticeStateMachineTest |  |  |  |
| 135 | NoticeInteractionStateTest; NoticeStateMachineTest: a_band_answers_once_... |  |  |  |
| 136 | NoticeStateMachineTest: show_sets_the_ttl_..., first_page_turn_kills_both_countdowns_... |  |  |  |
| 137 | NoticeStateMachineTest: forward_and_backward_wrap_..., an_update_keeps_the_wearer_on_the_action_... |  |  |  |
| 138 | NoticeStateMachineTest: a_notice_with_one_answer_still_pages_... |  |  |  |
| 139 | NoticeStateMachineTest: backdrop_owns_all_input_..., non_backdrop_interactive_...; AmbientCameraFanOutTest: item181_... |  |  |  |
| 140 | NoticeStateMachineTest: height_driven_body_capacity_..., image_page_spends_five_grown_lines_... |  |  |  |
| 141 | NoticeInteractionStateTest: failed_delivery_stays_spent_displays_uncertainty_... |  |  |  |
| 142 | NoticeOverlayRendererTest (motion helpers) | EMU |  |  |
| 143 | NoticeStateMachineTest: renderer_gives_fade_alpha_only_to_an_opted_in_backdrop; NoticeOverlayRendererTest | EMU |  |  |
| 144 | InkCardPresentationTest (band geometry); AmbientScreenshotTest | EMU |  | HudBandGeometry.topPx in dp (Q17) |
| 145 | NoticeStateMachineTest; NoticeInteractionStateTest: back_ttl_and_owner_hide_...; HudControllerGlueTest: item128_a_ring_double_tap_dismisses_the_notice_as_the_wearer (user) |  |  |  |
| 146 | NoticeSleepPolicyTest; DisplayWakePolicyTest |  |  |  |
| 147 | InkCardMorphStateTest | EMU |  |  |
| 148 | NoticeOverlayRendererTest |  |  |  |
| 149 | PinControllerTest |  |  |  |
| 150 | PinSurfaceContractTest (:shared); PinControllerTest: carries_the_size_tier_... |  |  |  |
| 151 | AmbientScreenshotTest (pin corners) | EMU |  |  |
| 152 | AmbientCameraFanOutTest: item152_* (pin, activity, receiver broadcast); item181 (notice) | EMU |  | Fan-out now reaches pin, notice and activity in the main process (U6; F-16 fixed) |
| 153 | ActivityStateMachineTest |  |  |  |
| 154 | ActivityPresentationPolicyTest |  |  |  |
| 155 | ActivityPresentationPolicyTest; ActivityStateMachineTest (flare budgets) |  |  |  |
| 156 | ActivityStateMachineTest; ActivityPresentationPolicyTest |  |  |  |
| 157 | ActivityPresentationPolicyTest (corner allocation) |  |  |  |
| 158 | HudControllerGlueTest item130_* and AmbientCameraFanOutTest start activities only with the '<owner>:activity' identity; the rejection of any other id has no test |  |  | Local surface id is '<owner>:activity' |
| 159 | ActivityStateMachineTest |  |  |  |
| 160 | AmbientScreenshotTest | EMU |  |  |
| 161 | ActivityPresentationSettingsTest |  |  |  |
| 162 | AmbientCameraFanOutTest (activity HIDDEN under camera) | EMU |  |  |
| 163 | See items 116, 117 |  |  |  |
| 164 | StatusBadgeReserveTest |  |  |  |
| 165 | StatusBadgeGeometryTest |  |  |  |
| 166 | - |  | DEVICE |  |
| 167 | - |  | DEVICE |  |
| 168 | PhoneBatteryControllerTest: item168_*; PhoneBatteryContractTest (:shared) |  |  |  |
| 169 | HudTopInsetTest |  |  |  |
| 170 | HudControllerGlueTest: item170_a_top_inset_change_repositions_the_open_home_without_recreating_the_window; ListHomeTest: a_top_inset_takes_rows_away_...; notice/pin/activity/ink card top: AmbientScreenshotTest, InkCardPresentationTest | EMU |  |  |
| 171 | RemotePointerGeometryTest |  |  |  |
| 172 | - | EMU |  |  |
| 173 | RemotePointerGestureCompletionGateTest |  |  |  |
| 174 | NativeAppsControllerTest |  |  |  |
| 175 | RemoteNavigationPolicyTest |  |  |  |
| 176 | MainActivityScreenshotTest (stopped stage only) | EMU |  |  |
| 177 | SelfArmOnboardingStateMachineTest |  |  |  |
| 178 | - |  | DEVICE |  |
| 179 | - | EMU |  |  |
| 180 | CameraInputRouterTest | EMU |  |  |
| 181 | AmbientCameraFanOutTest: item181_the_camera_overlay_takes_the_notice_out_of_view_and_out_of_input, item152_181_the_broadcast_from_the_camera_process_... |  | DEVICE | Decision (U6): main-process notice and pin honour the camera-overlay flag; F-16 fixed. The JVM covers the fan-out; a real :camera process broadcast is device-only |
| 182 | - |  | DEVICE |  |
| 183 | - | EMU |  |  |
| 184 | HudStateMachineTest: item_184_keys_in_hidden_belong_to_the_rom_...; HudInputTest: unowned_back_belongs_to_the_rom_... |  | DEVICE | Intentionally changed: ROM owns keys in Hidden, no guard (HARDWARE B1, 2026-09-30) |
| 185 | - |  | DEVICE |  |
| 186 | DisplayStandbyLauncherGateTest: item186_the_launcher_gate_follows_the_host_and_blocks_standby; DisplayStandbyPolicyTest |  |  | Gate reads HudController::isLauncherShown |
