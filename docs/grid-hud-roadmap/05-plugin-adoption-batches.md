# Delivery 5+ — Plugin adoption batches

## Goal

Retrofit real plugins to publish through the Delivery 3 `WidgetTileContract`
so their closed-state tiles show live data instead of the Delivery 1
generic fallback. Each batch is its own delivery: independently shippable,
independently verifiable, and dependent only on Delivery 3 (already shipped
and already proven with a synthetic publisher) — never on each other.

## Depends on

Delivery 3 only. Fully independent of Delivery 2 and Delivery 4.

## Why batching by tile *shape*, not by plugin identity

The hub-side renderer work for "a progress-bar-shaped tile" or "a
list/badge-shaped tile" is shared across whichever plugins use that shape,
so grouping by shape means the renderer is built once and reused, not once
per plugin. Suggested batches, matching each plugin's existing wake trigger
(no new background time is granted to any of them — this is exactly the
point made in Delivery 3's design, publishing rides an existing wake):

| Batch | Plugins | Tile shape | Existing wake reused |
|---|---|---|---|
| A | Media Deck, Lyrics | progress/now-playing | `MediaSession` callbacks |
| B | Transit, Relay, Feeds | list/badge | Transit's location-driven update; Relay's notification-listener wake; Feeds' existing refresh trigger |
| C | Tasker, Lens | action/status | Tasker's existing task-list refresh; Lens' session open/close |

Each row can be its own delivery, or split further into one delivery per
plugin — the grouping above is a suggestion for amortizing shared renderer
work, not a hard requirement. What matters for the independence guarantee
is: **one plugin, one PR, one verification pass**, regardless of batching.

## Per-plugin/batch work shape (repeats identically each time)

1. Bump that plugin's `bus-client` SDK dependency to the version that ships
   `nexusWidgetTileSession`.
2. Request the `widget_tile` capability in its descriptor — this returns
   the plugin to `Pending` for that install until the wearer re-approves,
   by the existing mechanism (Delivery 3, §2) — no new approval UI needed.
3. Call `.publish(TileSnapshot(...))` at the plugin's existing wake point
   (see table above) — a few lines, not a new subsystem, since Delivery 3
   already built the session/session-cache/rate-limit machinery this call
   lands in.
4. Declare `TILE_SIZES` in its manifest for whichever sizes it wants a
   *specifically authored* layout for (optional — per Delivery 3 §5, the
   default per-size truncation of one snapshot already covers every size
   with zero extra plugin code; declaring sizes here is only needed if the
   plugin wants something other than "the hub's generic truncation of my
   one snapshot" at some size).
5. Update that plugin's own `README.md`/`CHANGELOG.md` per the existing
   per-plugin documentation convention (`plugins/<id>/README.md`,
   `plugins/<id>/CHANGELOG.md`, per `plugins/README.md`).

**Design-system note**: a plugin adopting this pipeline never introduces
its own colors, icons, or layout — it only ever supplies `TileSnapshot`
data (including which `TileTone` it's in) into the hub's already
design-system-compliant renderer from Delivery 3. There is nothing for an
adopting plugin to get wrong visually here by construction; if a plugin's
integration seems to need a color or icon outside the fixed sets, that's a
sign the tone/content model is being bent to fit something it shouldn't,
not a case for a one-off exception.

## Acceptance criteria (per batch/plugin)

1. Installing the updated plugin and re-approving `widget_tile` makes its
   tile show real data within the rate-limit window, using the already-
   shipped, already-verified Delivery 3 pipeline — this delivery is not
   re-testing the pipeline, only proving this plugin's integration with it.
2. The plugin's existing open-state behaviour (tapping the tile) is
   completely unchanged — this batch touches only the closed-state publish
   call, never `SurfaceController`/`NexusSurface`.
3. Uninstalling/disapproving the plugin correctly ages its tile out via
   Delivery 3's staleness handling, then removes it from the grid entirely
   on actual uninstall — no orphaned cache entry lingers indefinitely
   (the `TileCache` should be cleared on the same uninstall signal that
   already clears the plugin's other stored grants/state today, per
   `docs/PLUGINS.md` §5: "Uninstalling removes the plugin and all its
   state").
4. The plugin's existing unit tests (e.g. `TransitRuntime`, `FeedsRuntime`
   equivalents) still pass unmodified aside from the new publish-call
   assertions added.

## Verification

**Automatable now:**
- Per plugin: a unit test on its runtime class asserting `publish()` is
  called with a correctly-shaped `TileSnapshot` at the right trigger (e.g.,
  "on location update with a soonest-departure change, Transit calls
  publish with that departure's text") — same testing style already used
  for these plugins' existing runtime classes.
- Regression run of that plugin's full existing test suite. Per this
  repo's `AGENTS.md`, plugin modules build with `-PskipCxrGlobal=true`
  (they don't link the vendor CXR library, unlike the hubs):
  `./gradlew :plugins:<id>:testDebugUnitTest :plugins:<id>:assembleDebug -PskipCxrGlobal=true`
  (module path per the actual Gradle module name — check
  `settings.gradle.kts` for the exact name, since some plugins live under
  `plugin-<id>` rather than `plugins:<id>`, e.g. `plugin-feeds`). Report
  plainly if it fails for an environment reason rather than working
  around it.
- Reuse of Delivery 3's end-to-end harness, pointed at the real plugin
  instead of the synthetic publisher, as an integration test.

**Requires on-device check:**
- Confirming the real trigger (an actual Bluesky post arriving, an actual
  bus approaching, an actual track change) produces a tile update with
  acceptable latency on physical glasses — this is the one thing that
  genuinely can't be simulated, since it depends on the real external
  data source and the real SPP transport timing together.
- Store/registry-side re-publish: each plugin author (including the four
  outside this repo) updating their own release and registry manifest is
  an operational step per plugin, not a code-verification step, and is
  outside what any automated check here can confirm.
