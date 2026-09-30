> Superseded for the glasses UI by docs/ui-rewrite/ — kept for history; the tile data pipeline (03) remains current.

# Delivery 2 — Expansion motion & focus polish

## Goal

Make opening/closing a tile look and feel like the reference design: the
tapped tile's rect animates into the plugin's real open-state surface (and
back on exit), non-focused tiles dim, and the focused tile gets the outline
style from the mockup. Purely a rendering/animation layer on top of
Delivery 1's already-shipped, already-working grid — no new data, no new
input semantics.

## Depends on

Delivery 1 only. Independent of Delivery 3 and Delivery 4 — either can ship
before, after, or never, without affecting this one.

## Design

### Geometry tween

`GridLauncherView` already knows each tile's on-screen `Rect` (from the
`TileGridPacker` output). On open: capture that `Rect` as the animation's
`from`, capture the destination panel's full-safe-area `Rect` as `to`
(`safe-x` 16 / `safe-y` 12 inset on the 480×352 reference canvas — matching
both the mockup's `EXP` constants and the design system's `content-width`
448px), and drive a `ValueAnimator` over `left/top/width/height` at
**`duration-structural`, 320ms**, using the `RokidHudTokens` constant from
Delivery 1 rather than a literal `320` — every timing value in this
delivery must come from that token object, not a re-typed number, so a
future token change doesn't require hunting for magic numbers. On close,
reverse it. This is a straightforward, self-contained `Animator` — no
dependency on Ink or Compose, consistent with every other renderer in this
codebase being a plain custom `View` (`InkHudView`, `SurfaceHudView`,
`InkNxCanvasView`).

**Reduced motion**: before starting the tween, check the system's
animation-scale/reduce-motion setting (Android has no CSS-style media
query for this; the equivalent is the system's animator-duration-scale, or
whatever accessibility "remove animations" signal this device exposes).
If motion is reduced, skip straight to the end state — tile becomes the
open panel instantly, no geometry tween, no cross-fade — per the design
system's explicit rule: motion communicates state, and when it's turned
off, a static end-state + text must convey the same thing without it.

### Sibling dim

Non-focused tiles fade to the design's dimmed opacity during the transition.
**Blur is intentionally not implemented as a real blur.** `RenderEffect`
(Android 13/API 33 for the reliable blur APIs) isn't guaranteed available
across this project's target range (SDK `minSdk 26` for `bus-client`, `30`
for the hubs), so the workaround already agreed on is used instead: a
cheap downscale-then-upscale bitmap blit of the dimmed tiles (roughly
1/8 resolution, blitted back up unfiltered) approximates the softened look
at near-zero cost on weak GPUs, and works identically on every supported
device instead of being a feature-detected bonus on newer hardware only.

### Content cross-fade

Once the geometry tween settles, cross-fade the destination panel's content
in (mirrors the mockup's `content-in` opacity step) — this is the point
where control hands off to the existing, unmodified `SurfaceController` /
`NexusSurface` rendering; this delivery owns only the container transition,
never the plugin's actual content.

### Focus outline

Apply the design system's exact two-state border treatment to
`GridLauncherView`'s tile drawing: unfocused = `border-default` (1px) in
`line` (24% intensity); focused = `border-strong` (2px) in `focus`
(`green-100`, solid), one tile at a time — a small, self-contained style
change, no animation dependency, using the Delivery 1 token object rather
than re-declaring these values here.

### Critical-state blink (generic support, used by later deliveries)

The design system's `Status` component defines a specific motion pattern
for its `critical` tone: 3 blinks at `duration-default` (200ms), then
settle to steady — never a continuous loop, and only one `critical`
element on screen at a time. No tile needs this in this delivery (no live
data exists yet), but this delivery's animation layer should expose it as
a generic, reusable primitive (e.g. a small blink-then-settle `Animator`
helper) rather than something built ad hoc per-widget later — Delivery 5's
plugins will need exactly this pattern for any critical-tone tile, and
building it once here, to spec, avoids five slightly-different
reimplementations downstream.

## Explicitly out of scope here

- Anything about what data is inside a tile (still Delivery 1's generic
  fallback, or real data once Delivery 3 + adoption exist — this delivery
  animates a box, it doesn't care what's drawn inside it before or after).
- Any change to focus *order* or input handling — Delivery 1's flattened
  1-D traversal is untouched; this delivery only changes what happens
  visually when you enter/exit the currently focused tile.

## Acceptance criteria

1. Tapping a tile in grid mode animates from its grid position/size to the
   plugin's real open-state surface at the design's timing, on real
   hardware, without dropping frames badly enough to feel broken.
2. Back reverses the same animation back to the tile's grid position.
3. Non-focused tiles visibly dim/soften during the transition and return to
   normal once settled.
4. List mode is completely unaffected — this delivery touches
   `GridLauncherView` only.
5. Disabling/removing this delivery's animator falls back cleanly to
   Delivery 1's instant show/hide (i.e., it's an additive layer, not a
   rewrite of Delivery 1's open/close path) — useful as a kill switch if the
   animation misbehaves on a given hardware revision.

## Verification

**Automatable now:**
- Unit tests for the geometry tween math itself (given a source `Rect`, a
  destination `Rect`, and a progress fraction, the interpolated `Rect` is
  correct) — pure math, no `View` needed.
- Unit tests for the downscale-blur helper: given a bitmap, the output
  dimensions/scale factor are correct and it doesn't throw on edge sizes
  (1×1 tile, full-screen large tile).
- Unit tests confirming every duration/inset used by this delivery's
  animator reads from `RokidHudTokens`, not a literal — a quick grep-style
  test (no bare `320`/`200`/`120` outside the token file) is enough to
  catch drift.
- A unit test for the blink-then-settle helper: exactly 3 blinks at
  `duration-default`, then a stable end state, never a looping animator.
- A unit test asserting the reduced-motion path skips the tween entirely
  and lands directly on the end-state `Rect`/content, exercised with a
  faked "reduce motion" signal (no device needed to fake that signal).
- `./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug`
  (per `AGENTS.md` — do **not** pass `-PskipCxrGlobal=true` here, the
  glasses hub links the vendor CXR library and that flag only applies to
  modules that don't).
- A Robolectric test asserting the animator's start/end `Rect`s match the
  tile's packed position and the destination safe-area constant, and that
  cancelling mid-animation (e.g., a rapid tap-then-back) leaves the view
  hierarchy in a consistent end state rather than stuck mid-transition —
  this is exactly the kind of state-machine bug that's cheap to catch here
  and expensive to catch on-device.

**Requires on-device check:**
- Actual frame-rate/smoothness of the animation on the real glasses GPU —
  this is precisely the risk this delivery exists to manage, and it's not
  something this environment can measure without the hardware.
- Whether the downscale-blur approximation "reads" as blur to a human eye
  at the glasses' real pixel density and viewing distance — a subjective,
  on-device call, not a unit-testable one.
- Interaction with `AdaptiveOverlayCollision.kt` / display-standby policy
  (`DisplayWakePolicy.kt`) during a mid-animation state — anything touching
  window composition timing on this hardware has historically needed a
  physical check per the existing files dedicated to exactly that class of
  issue.
- Confirming the reduced-motion fallback is actually reachable via
  whatever real accessibility setting this hardware/OS build exposes for
  it — the *code path* is unit-tested against a faked signal, but whether
  a real setting toggle reaches that signal is a device-level check.
