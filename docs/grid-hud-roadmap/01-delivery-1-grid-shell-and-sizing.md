# Delivery 1 — Grid shell, sizing model & mode toggle

## Goal

Ship a fully working grid launcher as an **opt-in alternative** to today's
list launcher, with every installed plugin rendered as a fallback tile at a
predefined size, navigable with the exact input model that exists today.
No live plugin data, no expand animation (that's Delivery 2) — this delivery
is the structural skeleton everything else attaches to.

Two requirements apply to this delivery specifically and are treated as
non-negotiable, not aspirational: (a) the grid's visuals must be built
against the actual Rokid HUD design system's tokens and component
contracts, ported into native code — not `BusTheme`, not an approximation
— and (b) tapping a tile must open the real plugin surface, for real, from
this delivery onward. Both are expanded on below (§0 and §5a) precisely
because they're easy to shortcut under time pressure and neither is
allowed to be.

## Non-goals (explicitly deferred)

- Live closed-state data (Delivery 3).
- The expand/collapse morph animation — tapping a tile opens the plugin's
  existing full-screen surface with whatever transition (or none) is
  cheapest to ship first; the *motion* is Delivery 2's job.
- User-driven layout editing beyond picking a size from a plugin's declared
  list (Delivery 4 adds phone-app drag/reorder).

## What already exists and is being reused, unmodified

- `SurfaceController` / `NexusSurface` (`glasses-hub/.../SurfaceController.kt`,
  `SurfaceModels.kt`) — opening a plugin still calls into the same exclusive
  surface-ownership pipeline. This delivery only replaces *how the picker
  looks*, not what happens after you pick.
- `GlassesHub.LauncherEntry` (`id`, `displayName`, `iconKey`) and
  `GlassesHub.launcherDrawable(context, entry)` — today's list launcher
  already renders exactly this; the grid's fallback tile renders the same
  two fields, just in a bigger box.
- `GlassesHub.observeLauncher { entries -> ... }` — the existing reactive
  entry-list feed. The grid subscribes to the same feed the list does.
- The ring/dpad input vocabulary in `LauncherOverlayRenderer` (forward,
  backward, tap, back) — reused as-is (see "Input model" below).

## New pieces

### 0. Port the design system into native tokens — prerequisite for everything else in this delivery

Full detail (exact values, component contracts, the reasoning) is in the
[overview's design-system section](00-overview.md#design-system--source-of-truth-governs-every-delivery-below);
this is the concrete task list for landing it here, first:

1. **New token object**, e.g. `RokidHudTokens` — pick a location alongside
   `BusTheme.kt` (`bus-client/src/main/java/com/anezium/rokidbus/client/ui/`)
   but a **separate class, not an extension of `BusTheme`**. Port every
   color (six intensities + aliases), type style, spacing step, radius,
   border width, icon size, and duration from `tokens.json` verbatim —
   these are exact values, not approximations, and each should carry a
   comment citing its source token name so drift is easy to spot in review.
   `BusTheme` itself is not touched — it keeps serving every surface that
   already depends on it (phone settings screens, the current list
   launcher, notices).
2. **A `HudFrameLayout`-equivalent base container**: applies `safe-x`
   16dp / `safe-y` 12dp insets, and — critically — never paints an opaque
   fill for anything but `ground`. `ground` is transparent on-device (an
   unlit pixel = the real world showing through); `BusTheme.glassesBg =
   Color.BLACK` already happens to satisfy this and can be reused as-is for
   that one value, but nothing else about `BusTheme` should leak into this
   container.
3. **Target canvas is 480×352** (the AIUI reference viewport), matching the
   original mockup exactly — not the 480×400 physical display. Don't treat
   the extra 48px as forbidden or guaranteed; design for 352.
4. **Icon rendering compliance**: the existing custom-glyph pipeline
   (`GlyphDrawable`, `PluginGlyphCache`) keeps working structurally, but
   anything drawn inside the new grid must render as 1px constant-stroke
   monoline, matching the design system's fixed icon set and sizes
   (`icon-sm`/`icon-md`/`icon-lg` = 16/20/24) — check this rather than
   assume it, since the current glyph renderer was built for a different
   visual language.
5. **Single-hue enforcement**: nothing built in this delivery (or any later
   one) introduces a color outside the six defined intensities. There is no
   "error red" or "warning amber" anywhere in this system — state is
   intensity + border shape + icon + text, per the `Status` component's
   tone table (`ok`/`info`/`warn`/`critical`/`off`).

### 1. Fixed tile-size enum (shared)

```kotlin
// shared/src/main/java/com/anezium/rokidbus/shared/tile/TileSize.kt
enum class TileSize(val cols: Int, val rows: Int, val wireValue: String) {
    SMALL(1, 1, "1x1"),
    WIDE(2, 1, "2x1"),
    TALL(1, 2, "1x2"),
    LARGE(2, 2, "2x2");

    companion object {
        fun fromWireValue(value: String): TileSize? = entries.firstOrNull { it.wireValue == value }
    }
}
```

A fixed, small enum rather than free `w×h` — this is what keeps the
auto-pack algorithm and the phone-app size picker simple (a handful of known
shapes, not arbitrary rectangles).

### 2. Plugin size declaration (manifest metadata + parser)

Add `META_PLUGIN_TILE_SIZES` to `BusConstants` next to the existing
`META_PLUGIN_ICON` / `META_PLUGIN_GLYPHS` keys, and extend
`PluginDescriptorParser.knownKeys` / `PluginDescriptor` in
`shared/.../plugin/PluginDescriptor.kt` with a `supportedTileSizes: Set<TileSize>`
field, parsed from a comma list (`"1x1,2x1"`), same validation style as the
existing `apiVersion`/`isValidId` checks. **Absent or empty is valid** — it
means "no declared sizes," which resolves to the generic fallback renderer
at whatever size the wearer picks (see below), not an error and not a
launcher exclusion. This mirrors exactly how an unrecognized `ICON` key
degrades today (`docs/PLUGINS.md` §"Plugin icon": falls back gracefully,
never removes the plugin from the launcher).

### 3. Generic fallback tile renderer (glasses-hub)

One renderer, size-class-agnostic: centered icon (via the existing
`launcherDrawable`) + name, laid out differently per `TileSize` (e.g. icon
only at `SMALL`, icon + two-line name at `LARGE`). This is the renderer used
for every plugin in this delivery, and it's also the permanent fallback in
Delivery 3 for any plugin that never adopts the tile pipeline — building it
now means Delivery 3 doesn't need to build a fallback path at all, it
inherits this one.

### 4. Auto-pack layout engine

Given the ordered list of `(pluginId, TileSize)` (default: every plugin at
`SMALL`, in the same order `observeLauncher` already returns), pack them
row-major into a fixed-column grid (mirroring the mockup's `rect(col, row,
w, h)` — same math, native `View` bounds instead of CSS). This is pure,
easily unit-testable logic — no Android framework dependency — and is
exactly the piece Delivery 4 later overrides with a wearer-chosen
arrangement instead of the auto-pack default. Keep it in a plain Kotlin
class (e.g. `TileGridPacker`) precisely so it's testable without
instrumentation.

### 5. Grid launcher view

A new `GridLauncherView` (sibling to today's `LauncherMenuView` inside
`LauncherOverlayRenderer.kt`, not a replacement of it — see mode toggle
below) that lays out fallback tiles per the packer's output and reuses the
*existing* focus/scroll pattern already in `LauncherMenuView.render()`
(`ensureVisible`-equivalent auto-scroll to the focused row already exists
there for the list; the grid needs the same idea applied to a 2-D-positioned
but 1-D-traversed set of tiles).

### 5a. Opening a tile must open the real plugin — non-negotiable, required from this delivery

**This is a hard requirement, explicitly called out by the person who
requested this roadmap: the plugin must really open, starting in this
delivery.** There is no phase of this delivery, even an early or
in-progress one, where tapping a tile is allowed to show a stub, a
placeholder panel, or a "coming soon" state instead of the real thing.
`GridLauncherView`'s tap handler must call the exact same
`GlassesHub.openLauncherEntry(entry.id)` that `LauncherOverlayRenderer.
openSelected()` calls for the list today, hand off to the real, unmodified
`SurfaceController`/`NexusSurface` pipeline, and hide the launcher exactly
as the list does now (`launcherReturnCoordinator.recordLauncherOpen`,
`RingFocusBroadcastCoordinator.beginSurfaceHandoff`, `hide()` — see
`LauncherOverlayRenderer.openSelected()` for the exact sequence to
replicate). Build this wiring first, or at minimum alongside the tile
visuals — never defer it to "polish later." A version of this delivery
where the grid looks right but tapping a tile does nothing real does not
satisfy the requirement, even temporarily.

### 6. Input model — unchanged, confirmed

Per the earlier discussion: the grid's interactive tiles flatten into the
same one-dimensional order the list already uses. `LauncherOverlayRenderer.
handleKeyEvent` / `handleRingKey` — forward/backward moves a flat
`selectedIndex`, tap/enter opens, back closes — is reused **verbatim**. The
only new rule is fixing the traversal order to row-major over the grid
positions so it matches what the eye expects; no new gesture, no new key
handling code path.

### 7. Mode toggle (list vs. grid)

- **Shared**: `HudModeContract` (default `LIST`, wire key, matching the
  `GlassesRepairContract` shape) in `shared/.../hudmode/`.
- **Phone**: `HudModeSettingsStore`, same shape as
  `GlassesRepairSettingsStore.kt` — a `SharedPreferences` wrapper with
  `isGridModeEnabled()` / `setGridModeEnabled(Boolean)`, written from a new
  toggle in `SettingsActivity` (or `GlassesDisplaySettingsActivity`, wherever
  HUD-appearance settings already live), and **re-pushed on every glasses
  capabilities announce** exactly like the repair-auto flag, so a toggle
  flipped while disconnected still lands on reconnect.
- **Glasses**: `LauncherOverlayRenderer` picks `GridLauncherView` or
  `LauncherMenuView` based on the synced flag at `show()` time. Both classes
  keep compiling and running — nothing is deleted. This is also the hook
  Delivery 3 uses to decide whether to ever start the tile pipeline at all
  (see that delivery's "battery gating" section) — Delivery 1 only needs to
  land the flag and the renderer switch; gating the pipeline start is
  Delivery 3's concern since the pipeline doesn't exist yet here.

## Acceptance criteria (must hold with nothing from Delivery 2/3/4 present)

1. Toggling grid mode on/off in the phone app while connected switches the
   glasses launcher's rendering immediately on next open.
2. Toggling it while disconnected and reconnecting still lands correctly
   (proves the re-push-on-announce path).
3. Every installed, approved, launchable plugin appears as a fallback tile
   (icon + name) in the grid at `SMALL` by default.
4. Forward/backward/tap/back behave identically in feel to today's list —
   same key codes, same dedupe, same ring-tap policy.
5. **Opening a tile actually opens the plugin's real, unmodified surface —
   verified by call, not by appearance** (see §5a). No stub, no
   placeholder, at any point in this delivery.
6. A plugin declaring `TILE_SIZES` for sizes it hasn't otherwise implemented
   anything for still renders correctly via the generic fallback at any of
   those sizes.
7. Malformed/duplicate/conflicting `TILE_SIZES` metadata is rejected the
   same way other conflicting plugin metadata already is (`CONFLICTING_METADATA`
   in `PluginDescriptorParser`), not silently accepted.
8. Every color drawn anywhere in the new grid code path is one of the six
   design-system intensities (or their aliases) — no other RGB literal
   appears in the new package, and none of it comes from `BusTheme`.
9. Fallback tiles and the grid container respect `safe-x`/`safe-y`, the 4px
   spacing grid, and use `radius-control`/`radius-panel`/`radius-data`
   correctly per element (tile border vs. container vs. any data chip).
10. Any list-shaped content in this delivery (if a fallback tile ever shows
    more than icon+name) caps at `ListItem`'s fixed 32px row height and a
    maximum of 3–4 simultaneous rows — not an arbitrary count.

## Verification

**Automatable now (run as the actual merge gate):**
- `TileGridPacker` — pure unit tests: fixed set of sizes packs without
  overlap, is deterministic for a given input order, handles 0/1/N plugins,
  handles a plugin with an invalid/absent size falling back to `SMALL`.
- `PluginDescriptorParser` additions — unit tests for the new
  `TILE_SIZES` key mirroring the existing `PluginDescriptorTest.kt` cases
  (valid list, empty, malformed token, conflicting duplicate declarations).
- `HudModeSettingsStore` — unit test mirroring
  `GlassesRepairSettingsStoreTest.kt` (default value, set/get round-trip).
- A Robolectric/instrumented test for `GridLauncherView` covering "N fallback
  tiles laid out, focused index N-1 is reachable via N-1 forward presses" —
  this doesn't need the accessibility overlay window itself, just the View
  hierarchy, so it's runnable in this environment.
- **A spy/fake test on `GlassesHub.openLauncherEntry`** asserting the grid's
  tap handler invokes it with the focused entry's real `id`, and that the
  same post-open sequence the list uses runs (`recordLauncherOpen`,
  surface-handoff broadcast, `hide()`). This is the actual enforcement
  mechanism for the "must really open" requirement in §5a — a regression
  that swaps in a stub can't pass this test.
- **A static/lint check that no hardcoded color literal (`0x...`,
  `Color.rgb`/`Color.argb`, or a `BusTheme.*` color reference) appears
  anywhere in the new grid package outside the new token object** — the
  concrete, automatable enforcement of "every color comes from the design
  system, not from `BusTheme` or an invented value."
- Exact build commands (per this repo's `AGENTS.md`):
  `./gradlew :shared:test :bus-client:testDebugUnitTest -PskipCxrGlobal=true`
  for the plain-Kotlin/SDK pieces (`TileGridPacker`, `PluginDescriptorParser`,
  `RokidHudTokens`), and, **without** `-PskipCxrGlobal=true` (the hubs link
  the vendor CXR library, and that flag will make the build fail looking for
  it) — `./gradlew :phone-hub:testDebugUnitTest :phone-hub:assembleDebug` and
  `./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug` for
  `HudModeSettingsStore`, `GridLauncherView`, and the open-entry spy test. If
  any of these fail for an environment reason (missing SDK, no sibling
  `CxrGlobal` checkout, network-gated Gradle distribution), report that
  plainly rather than working around it — per this repo's own stated policy
  on build-environment issues.

**Requires on-device check (hand off explicitly, not skipped):**
- Actual ring-hardware forward/backward/tap/back feel in grid mode on real
  glasses (the key-dedupe logic is unit-testable, but the physical ring
  timing is not).
- Visual check that the accessibility-overlay window still composes
  correctly over whatever the wearer was looking at before opening the
  launcher (this class of issue — overlay z-order/insets — has historically
  been hardware-specific here, per `AdaptiveOverlayCollision.kt` existing at
  all).
- Reconnect timing for the re-pushed mode flag against a real SPP link
  (the mechanism is proven by unit test; the real Bluetooth timing isn't).
- **Visual side-by-side against the design system's own component previews**
  (`project/components/*/preview.html` in the design-system artifact) and
  against the original mockup — colors, spacing, and type must read as the
  same screen, not an approximation of it. This is the real acceptance
  check for "looks exactly like the design," and it's a human, on-device
  judgment call, not something the automated color-literal lint alone can
  certify (the lint proves *only* tokens were used, not that they were used
  correctly).
