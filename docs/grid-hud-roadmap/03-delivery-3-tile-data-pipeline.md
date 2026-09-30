> Current: the tile data pipeline described here remains in use. Only the glasses-side rendering of tiles is superseded by docs/ui-rewrite/ (kept for history).

# Delivery 3 — Tile data pipeline (`WidgetTileContract`)

## Goal

Build the entire closed-state data pipeline — wire contract, capability,
rate limiting, staleness handling, per-size content adaptation — and prove
it end-to-end with a **synthetic publisher only**. No real shipped plugin is
touched in this delivery; that's Delivery 5+.

## Depends on

Delivery 1 only (the `TileSize` enum, the `RokidHudTokens` object, the
generic fallback renderer as the permanent no-data/no-adoption path, the
mode-toggle flag). Independent of Delivery 2 and 4 — see the note under
"tone rendering" below for how `CRITICAL` stays independently correct with
or without Delivery 2 having shipped. Also depends on having read the
[design system section of the overview](00-overview.md#design-system--source-of-truth-governs-every-delivery-below)
— the `Status`/`DataReadout`/`Loader`/`ListItem` contracts referenced
throughout this delivery are defined there, not re-derived here.

## Why this can be fully verified without any real plugin

The acceptance bar is "publish → cache → render → expire → throttle," all of
which can be exercised with a fake publisher (a debug harness, or extending
`plugins/sample` — the repo's own "canonical copyable reference plugin," per
`docs/PLUGINS.md`). A plugin that never adopts this at all must keep
rendering via Delivery 1's fallback with zero change — that path was already
proven in Delivery 1, so this delivery's own tests only need to prove the
*new* path, not re-prove the old one.

## Design

### 1. Wire contract (`shared`)

New `WidgetTileContract` (`shared/.../tile/`), same discipline as the
existing `SurfaceModels.kt` field limits (`contentKey ≤ 128 chars`,
`MonoArtwork` size/format caps): a small, bounded payload —

```kotlin
data class TileSnapshot(
    val pluginId: String,
    val contentKey: String,      // dedupe key, same 128-char cap pattern as surfaces
    val title: String,
    val subtitle: String = "",
    val badge: String = "",      // e.g. unread count, rendered as-is
    val progress: Float? = null, // 0f..1f, for media/progress-shaped tiles
    val unit: String = "",       // for DataReadout-shaped numeric tiles (see below)
    val tone: TileTone = TileTone.OFF,
    val rows: List<String> = emptyList(), // up to 3–4 short lines, per ListItem's cap
)

enum class TileTone { OK, INFO, WARN, CRITICAL, OFF }
```

**Correction from an earlier pass of this roadmap**: `tone` does **not**
reuse `SurfaceRow.TONE_*` (`alert`/`normal`/`dim`/`body`) — that vocabulary
predates this delivery's grounding in the actual design system and doesn't
map cleanly onto it. The design system defines exactly one sanctioned way
to express state — the `Status` component's five-value tone table
(`ok`/`info`/`warn`/`critical`/`off`, each with its own fixed icon +
border-shape combination, see the [overview](00-overview.md#component-contracts-that-constrain-this-roadmaps-ui-work-directly))
— so `TileTone` mirrors that set directly. The hub-side tile renderer owns
translating a tone into the `Status`-specified icon/border treatment; a plugin only
ever picks which of the five states it's in, never a color.
**Delivered rendering of `CRITICAL` (U5, docs/ui-rewrite/00-architecture.md §6)**: a `CRITICAL` live
tile has a solid 1 px `text-primary` border and the `alert` icon at 100 %, and the icon alone blinks
three times at `duration-default` and then stays steady (not at all under reduced motion). It never
draws a 2 px critical frame: the 2 px `focus` frame belongs to focus alone, so there is never a second
one on screen beside the focused tile. `WARN` is the `alert` icon at 72 % on the dashed border. When
several tiles are `CRITICAL` only one is (the focused one, else the first in packer order) and the
rest read as `WARN`. State is still told by border, icon and text, never by a color. The original plan
below (a 2 px `critical` border rendered statically, with Delivery 2's blink as an optional extra) is
kept for history.

**Independence note (original plan)**: `CRITICAL`'s spec includes a blink-then-settle
motion, which is Delivery 2's helper — but this delivery does not depend
on Delivery 2 to be correct. Render `CRITICAL` here with the static parts
of its spec (2px `critical` border, `alert` icon, text) regardless of
whether Delivery 2 has shipped; if Delivery 2's blink helper is present at
build time, use it as a strict visual enhancement, otherwise fall back to
the static treatment, which is already spec-compliant on its own (state is
communicated by border+icon+text even without the blink). This keeps both
deliveries independently shippable in either order.
If any existing `SurfaceRow`-based rendering needs to feed into a tile
later, bridge `SurfaceRow.TONE_ALERT → CRITICAL/WARN` (context-dependent)
and `TONE_NORMAL/TONE_DIM/TONE_BODY → INFO/OFF` explicitly at that call
site — don't let the two vocabularies blur together.

### 2. New capability + descriptor field

Add `widget_tile` to the plugin capability enum next to `surfaces`/`stt`/
`camera` (`shared/src/main/java/com/anezium/rokidbus/shared/plugin/PluginCapability.kt`).
Requesting it goes through the
**exact existing approval flow**: per `docs/PLUGINS.md` §5, adding a
capability to an already-approved plugin returns its grant to `Pending`
until the wearer reviews it again. Nothing new to build here — this
delivery just adds one more entry to a set that already has this behaviour
for free.

### 3. Publish path (bus-client SDK)

```kotlin
// bus-client, alongside the existing nexusSurfaceSession(id)
fun nexusWidgetTileSession(id: String): WidgetTileSession
interface WidgetTileSession {
    fun publish(snapshot: TileSnapshot)
}
```

Explicitly documented (mirroring the Background Policy section of
`docs/PLUGINS.md`) as callable **only from an existing legitimate wake**:
Transit's location-driven wake, Relay's notification-listener wake, Media
Deck's `MediaSession` callback. This delivery does not grant any plugin new
background time — it adds a side effect to wakes that already exist. This
needs a short new paragraph in `docs/PLUGINS.md` §Background Policy, framed
as a fourth exception alongside Pins/Scheduled delivery/Lease-bounded audio,
with the same "buys you one push, not a foothold" framing as Pins already
have — reuse that precedent's wording and spirit rather than writing a new
policy from scratch.

### 4. Hub-side cache, staleness, and rate limiting (glasses-hub)

- `TileCache`: keyed by `pluginId`, holds the last `TileSnapshot` +
  `receivedAtElapsedRealtime`. Persists across the publishing plugin's
  process death (unlike surfaces today, which have no state once a plugin
  goes dormant) — a small on-disk store (SQLite/prefs), since the grid must
  show *something* immediately after the glasses hub itself restarts,
  before any plugin has re-published.
- **Staleness**: `GridLauncherView`'s renderer checks
  `now - receivedAtElapsedRealtime` against a fixed threshold (proposed
  10 minutes, configurable constant) and dims/greys the tile past it,
  independent of whether the plugin is even installed anymore — the hub
  decides freshness, it never trusts the plugin's own timing.
- **Rate limiting**: a token-bucket per `pluginId` (e.g., N publishes per
  minute); publishes past the ceiling are dropped silently, not queued —
  matching the "give up quietly rather than retry-looping" ethos already
  stated for `SURFACE_BUSY` in `docs/PLUGINS.md`.
- **Mode gate**: at startup, if `HudModeContract` (Delivery 1) says list
  mode, the whole `TileCache`/rate-limiter subsystem does not start at all —
  no listener registration, no cache warm-up. This is the actual battery
  win from the earlier mode-toggle discussion: it's not a hidden-but-running
  grid, the subsystem is genuinely absent in list mode.

### 5. Per-size content adaptation

Default behaviour lives entirely in the hub's renderer, not the plugin: a
`SMALL` tile shows `title` (+ `badge` if present); `WIDE` adds `subtitle`;
`LARGE` renders `rows` as `ListItem`-shaped rows — **capped at 3–4
simultaneous rows**, per that component's explicit hard limit, not an
arbitrary N chosen for this pipeline. One snapshot, the hub decides how
much fits — this is the "we handle that automatically" default agreed
earlier. A plugin wanting a genuinely different (not just more truncated)
layout at a specific size is an opt-in enhancement layered on top later;
it is explicitly not required for this delivery and isn't blocked by it
either — the contract's fields already support "just show more of the
same," which covers the default case fully.

**Numeric content renders as `DataReadout`**, not as arbitrary text: a
tile whose primary value is a bare number (a countdown, a temperature, a
count) renders `title`/`badge` as `DataReadout`'s `label` + mono `data`
value + optional `unit` — mono specifically because the design system
calls out that plain proportional digits "dance" visually as a value
ticks over, which matters for something like a live countdown.

**Before the first snapshot ever arrives** (fresh install, just-approved
plugin, hub just restarted with no cached entry), the tile shows the
`Loader` component's `scan` or `point` pattern — never a blank box, never
an ad hoc spinner — for as long as real work is actually pending, and
switches to `progress` instead once/if the plugin's own `progress` field
is populated. This is a real "in-flight" state, distinct from staleness
(§4): staleness is *stale data*, this is *no data yet*, and the design
system has a specific, different primitive for each.

## Acceptance criteria (all provable with a synthetic publisher)

1. A debug/sample publisher calling `publish()` renders live in the grid
   within the rate-limit window.
2. Stopping publication: the tile visibly greys out past the staleness
   threshold, without needing the plugin to say anything.
3. Publishing above the rate ceiling: excess calls are dropped, no crash,
   no unbounded queue growth.
4. A plugin that requests `widget_tile` after already being approved for
   `surfaces` correctly drops back to `Pending` and stops publishing
   (enforced) until re-approved.
5. A plugin that never calls `publish()` at all renders via Delivery 1's
   fallback, unchanged, for as long as it stays uninstalled from this
   pipeline — proving Delivery 1 and Delivery 3 compose without a gap state.
6. In list mode, none of this subsystem starts (verified by absence of its
   listener registrations/cache warm-up, not just by the UI not showing it).
7. Hub restart with a previously-cached snapshot still on disk shows that
   last-known tile immediately, marked with its real (now-stale) age.
8. Each of the five `TileTone` values renders with its `Status`-specified
   icon and border shape, never a distinct color; `CRITICAL` is a 1 px
   `text-primary` border with the `alert` icon at 100 % that blinks three
   times and settles, and focus alone owns the 2 px frame (see the delivered
   rendering note above).
9. A tile with no snapshot yet (fresh approval, no cache) shows `Loader`,
   never a blank view; a numeric primary value renders via `DataReadout`
   (mono value, optional unit), never as plain proportional-font text.

## Verification

**Automatable now:**
- `TileCache` unit tests: set/get, persistence round-trip across a simulated
  process restart (write, reconstruct the object, read), staleness
  threshold boundary (just under / just over).
- Rate limiter unit tests: token-bucket refill math, burst-then-drop
  behaviour, per-plugin isolation (one plugin's flood doesn't throttle
  another's normal cadence).
- `WidgetTileContract`/`TileSnapshot` parsing unit tests mirroring
  `SurfaceModels`'s existing `fromPayload` test style — bounds checks on
  every field, malformed payload rejected without crashing the session.
- `PluginCapability` addition + the Pending-on-capability-change behaviour:
  extend whatever existing test already covers that transition for
  `surfaces`/`stt` to also cover `widget_tile` — it's the same code path by
  design, so the test is close to free to add.
- An end-to-end JVM-level test using `plugins/sample` (or a small dedicated
  debug harness module) driving `publish()` through the AIDL boundary in a
  local/instrumented test and asserting the tile renders, ages out, and
  gets throttled — this is the delivery's real integration proof, and it
  doesn't need physical glasses hardware since it's exercising the bus
  protocol and hub-side cache, not the display compositor.
- Unit tests for `TileTone` rendering: each of the five tones produces the
  `Status`-specified icon + border-shape combination, `CRITICAL` is the
  1 px `text-primary` border with the `alert` icon at 100 %, blinking the
  icon alone 3 times, then steady, never looping, only one `critical` tile
  at a time, and none of the five tones
  ever resolves to a color outside the six design-system intensities.
- A unit test for the pre-first-snapshot state: a tile with no cached
  entry and no snapshot yet renders `Loader` (`scan`/`point`), never a
  blank view; once a `progress` value arrives it switches to `Loader`'s
  `progress` mode.
- Build commands (per `AGENTS.md`):
  `./gradlew :shared:test :bus-client:testDebugUnitTest -PskipCxrGlobal=true`
  for the contract/capability/SDK pieces, and, without that flag (the hubs
  link the vendor CXR library):
  `./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug`
  for the cache/rate-limiter/renderer pieces. For the sample-plugin
  integration harness:
  `./gradlew :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug -PskipCxrGlobal=true`.
  Report plainly, without working around it, if any of these fail for an
  environment reason (missing SDK, no sibling `CxrGlobal` checkout for the
  hub builds, a network-gated Gradle distribution).

**Requires on-device check:**
- Real battery draw comparison, list mode vs. grid mode with the pipeline
  idle vs. active — the subsystem-absence in list mode is provable in code,
  but the actual milliamp-hour difference is a hardware measurement.
- Real end-to-end latency from a plugin's genuine wake trigger (e.g., an
  actual Bluetooth notification arriving for Relay) to the tile updating on
  a real display, through the real authenticated SPP transport.
- Confirming the staleness threshold "feels right" to a wearer in practice
  (10 minutes is a starting proposal, not a validated number).
