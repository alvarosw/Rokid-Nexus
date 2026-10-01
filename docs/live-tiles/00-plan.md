# Live tiles and system widgets

Status: approved direction (product owner, 2026-10-01). Supersedes
`docs/grid-hud-roadmap/05-plugin-adoption-batches.md`, whose assumptions no longer hold (see §1).
Reference design: the "Nexus Launcher — Live tiles" canvas,
https://claude.ai/artifact/MFcnzc2dipgMsdEvBSWkVX, mirrored in `docs/live-tiles/design/`
(one artboard per plugin at all seven sizes, plus the home screen in context). The exact content
of each tile is not frozen: the owner reviews the result after implementation and may adjust it,
so follow the artboards closely but keep every layout decision in the shared renderer, where it is
cheap to change.

The work has three parts:

- **PR 0 — foundation** (one PR, done first, alone): the content contracts, the single tile
  renderer, the editor's size preview. Both deliveries build on it.
- **Delivery A — plugin live tiles**: pipeline hardening, the tile lease, the per-template
  layouts, one PR per plugin, distribution through the fork's registry.
- **Delivery B — system widgets**: non-interactive hub-owned tiles (clock, status, weather) the
  wearer adds and sizes in the layout editor.

A and B run in parallel after PR 0 lands, on separate branches, with the file ownership in §6.

## 1. Where the code stands (2026-10-01)

- The `/tile/publish` pipeline (Delivery 3), seven tile sizes and the free-placement editor exist.
  No real plugin publishes; only `plugins/sample` does.
- `TileSnapshot` (title/subtitle/badge/progress/unit/rows) cannot express the design: artwork,
  sectioned lists with per-item meta, a centered current lyric line, relative ages, header summary.
- Two renderers draw the same tile: `LiveTileView` (Views, glasses) and
  `TileLayoutCanvasView.drawTile` (Canvas, phone). They must become one.
- `TileRateLimiter` drops publishes past 5/min, so the last state can be lost. The phone forwards
  every publish over the link before the glasses drop it.
- `TileCache` (glasses, persisted) and `TileSnapshotCache` (phone) are never pruned per plugin on
  uninstall or revocation.
- Staleness is a fixed 10 minutes.
- Background policy: Media Deck, Lyrics and Feeds have no wake while closed (their runtime starts in
  `onNexusOpen`, and `docs/PLUGINS.md` keeps notification listeners idle until open). Only Relay is
  awake on its own. The "fourth exception" in `docs/PLUGINS.md` cites wakes that do not exist.
- Distribution: the Store reads the upstream registry
  (`Anezium/RokidBrew-Registry`) and installs APKs pinned to the upstream signer. The grid exists
  only in this fork. An upstream hub rejects a plugin whose `CAPABILITIES` lists an unknown value
  (`UNKNOWN_CAPABILITY`), but silently ignores unknown meta-data keys.
- News is a third-party plugin (`beyondlevi/news-nexus`, Apache-2.0).

## 2. Content contracts (`:shared`, `shared/.../tile/`) — PR 0

Four public templates. A plugin only ever supplies data; the hub owns every layout decision per
size. Hub-internal content (system widgets, Delivery B) is not part of this public contract.

```kotlin
data class TileSnapshot(
    val pluginId: String,          // stamped by the phone hub; the plugin's value is ignored
    val contentKey: String,
    val content: TileContent,
    val tone: TileTone = TileTone.OFF,
    val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS, // clamped to 60 s .. 24 h
)
// A secondary constructor keeps the old (title, subtitle, badge, progress, unit, tone, rows)
// call sites compiling: it builds TileContent.Generic.

sealed interface TileContent {
    /** Today's fields, unchanged: every plugin that does not fit a richer template. */
    data class Generic(title, subtitle = "", badge = "", progress: Float? = null, unit = "", rows = emptyList())

    /** A playing track: Media Deck, any player. */
    data class Music(
        title, artist = "", album = "", source = "",
        playing: Boolean,
        positionMs: Long? = null,  // position at publish time; the glasses advance it while playing
        durationMs: Long? = null,
        artworkKey: String = "",   // Delivery A: resolved through the tile artwork path; "" = no art
    )

    /** A scrolling list of lines with the current one always in the middle: Lyrics, steps. */
    data class Lines(
        lines: List<Line>,         // <= 9
        current: Int,              // index shown centered when the lines carry no start times
        title = "", subtitle = "", source = "",   // e.g. track · artist · "LrcLib"
        positionMs: Long? = null, durationMs: Long? = null, playing: Boolean = false,
    ) { data class Line(text, startMs: Long? = null) }
    // With startMs on the lines and a playing anchor, the glasses move the current line
    // themselves: one publish per track or seek, not one per line.

    /** Sectioned list: Relay, News, Feeds, and most list-shaped plugins. */
    data class List(
        sections: List<Section>,   // <= 3; total items across sections <= 6
        summary = "",              // header right at width >= 2, e.g. "3 new"
        summaryShort = "",         // header right at width 1, e.g. "3"
        footer = "",               // e.g. "Updated 12 s ago" is rendered from the receipt time, not sent
        paragraphLines: Int = 2,   // 1..6: the one density knob (a long post vs short messages)
        overflow: Int = 0,         // items not sent, rendered as "+N more" where room allows
    ) {
        data class Section(title = "", detail = "", items: List<Item>) // divider between sections
        data class Item(
            title,                 // sender, headline, author
            detail = "",           // app, source, @handle
            paragraph = "",        // message, summary, post; media markers ("[photo]") go at its end
            ageMs: Long? = null,   // age at publish; rendered relative and kept current ("1 min")
            leading: Leading? = null,
        )
        sealed interface Leading {
            data class Initials(text) : Leading      // 1..2 chars, e.g. "AR"
            data class Glyph(name) : Leading         // a glyph from the plugin's GLYPHS set
        }
    }
}
```

Bounds (checked at construction in the plugin process and again, defensively, in
`WidgetTileContract.fromPayload`): every text field <= 120 chars except `paragraph` <= 280,
`summary`/`detail` <= 60, `summaryShort` <= 6; `MAX_PAYLOAD_BYTES` raised to 12 KiB.

Wire (`/tile/publish`, `BUSSPEC.md`): the payload gains `template` (`generic|music|lines|list`),
`content` (the template's fields) and `staleAfterMs`. The legacy top-level fields (`title`,
`subtitle`, `badge`, `progress`, `unit`, `rows`) are always filled too, by a down-level mapping in
`WidgetTileContract.toPayload`, so an older fork glasses hub still shows something. A payload
without `template` decodes as `Generic` from the legacy fields, so old plugins keep working.
Unknown templates decode as `Generic` from the legacy fields.

Time-relative fields (`positionMs`, `ageMs`) are relative to the publish: the glasses add the time
since receipt (`elapsedRealtime`), so neither device's wall clock matters.

## 3. The tile renderer (`:hud-tiles`) — PR 0

A new Android library module used by both hubs only (not part of the plugin SDK), depending on
`:shared` and `:bus-client` for `RokidHudTokens`/`HudGridMetrics`. Canvas-based, no Views:

- `TileRenderer.layout(input, size): TileLayout` — pure positioning: a list of draw ops (text runs
  with style and clamp, track, separator, initials box, artwork slot, glyph), in glasses pixels at
  the tile's real size. `TileLayout.draw(canvas)` paints it. Tests assert on the ops.
- `input` carries the content, tone, stale flag, receipt and current `elapsedRealtime`, the plugin
  name and icon, and the focus intensity (text brightens with focus as today).
- It draws the whole tile interior including the header row (icon + uppercase name + summary on the
  right). Chrome stays in the hosts: border, focus fill, loader, alert mark, opening loader.
- `nextChangeAtElapsed(input)`: when the drawing will next change on its own (music position
  second, next timed line, next age minute), so a host schedules exactly one redraw, and only
  while the home is visible.
- Per-template layout lives in one class per template (`GenericTileLayout`, `MusicTileLayout`,
  `LinesTileLayout`, `ListTileLayout`). In PR 0 only `Generic` is real (parity with today's
  `TileContentRules`); the other three render their down-level `Generic` until Delivery A
  replaces them. Delivery B adds its own layouts for system widgets.

Hosts:

- Glasses: `LiveTileView` keeps chrome, focus and loaders and draws its content with the renderer.
  Its existing tests move to layout-op assertions; Roborazzi baselines are re-recorded and checked
  by eye against the previous ones (no intended visual change for `Generic`).
- Phone: `TileLayoutCanvasView` draws tile content with the renderer under its scale transform;
  its own tile-content drawing code is deleted.

## 4. Editor size preview — PR 0

The selected-tile card gains a preview: the tile drawn by the renderer at the size of the last
tapped size chip, at glasses geometry scaled to the card width, even when that size has no room in
the grid (the amber "No room" line still shows). Content source, in order: the live snapshot
(`TileSnapshotCache`), else the plugin's declared sample, else the header only.

The sample: a new manifest meta-data key `com.anezium.rokidbus.plugin.TILE_PREVIEW` pointing to a
raw JSON resource holding one `/tile/publish` payload, read cross-package by the phone hub (as
`GLYPHS` is) and decoded by `WidgetTileContract.fromPayload`. An invalid sample is ignored, never
fatal to the descriptor. Hubs that predate the key ignore it (unknown keys are filtered).

`plugins/sample` publishes each of the four templates in turn and declares a `TILE_PREVIEW`, so
the pipeline and the preview can be exercised in emulation.

## 5. Delivery A — plugin live tiles

A0. Pipeline:
- Phone hub: a per-plugin coalescing limiter before the link (latest wins, flushed when the
  budget allows; never drops the final state). Glasses keep `TileRateLimiter` as a defense.
- Prune `TileCache` and `TileSnapshotCache` per plugin on uninstall, on `widget_tile` revocation,
  and when a plugin leaves the launcher list.
- Staleness from `staleAfterMs`.

A1. Layouts for `Music`, `Lines`, `List` at all seven sizes, following the artboards. Rule of
thumb per size, in this drop order as space shrinks: paragraph, then detail, then section headers;
an item title never drops. Screenshot tests: 3 templates × 7 sizes.

A2. Tile artwork: a bounded binary path (reusing `MediaArtworkContract`'s encoding) keyed by
`artworkKey`, deduplicated, cached on the glasses next to the snapshot. No art: text-only layout.

A3. Tile lease (policy, SDK, phone hub):
- The phone hub considers a plugin's tile *active* while: grid mode is on, the glasses link is up,
  the plugin's tile is placed in the stored layout, and `widget_tile` is granted.
- SDK callbacks: `onNexusTileActive(active: Boolean)` — while active, the plugin may observe its
  own event sources (media sessions, notifications) and publish; when inactive it returns to
  dormant. `onNexusTileRefresh()` — for poll-based plugins, sent by the hub on a hub-owned cadence
  (when the home becomes visible, and at most every 15 minutes while active); the plugin does one
  fetch, publishes, and goes dormant.
- Lease grants and refreshes are recorded in the bus journal.
- `docs/PLUGINS.md` Background policy: the fourth exception is rewritten around the lease.

A4. Compatibility: plugins declare `widget_tile` in a new meta-data key
`com.anezium.rokidbus.plugin.OPTIONAL_CAPABILITIES`, which hubs that do not know it ignore, so the
same APK still loads on an upstream hub. New hubs merge it into the requested capabilities.

A5. One PR per plugin: Media Deck (`Music`), Lyrics (`Lines`), Relay (`List`; one untitled section,
app in each item's detail, initials as leading; notifications Relay treats as sensitive never reach
the tile), Feeds (`List`, refresh-driven, long `paragraphLines`), News (`List`, sections per
outlet with the unread count as detail, refresh-driven) in a fork of `beyondlevi/news-nexus`.
Each: `TILE_SIZES`, `TILE_PREVIEW`, runtime tests, README/CHANGELOG, version bump.

A6. Distribution: a registry published by the fork, merged with the upstream registry per plugin id
(the fork's entry wins for the plugins it publishes; every other plugin keeps coming from
upstream). If the fork registry cannot be loaded, its plugins are shown from the last cached copy,
never replaced by upstream entries (their signer differs). The registry URLs are build config. The
update badge only offers versions whose signer matches the installed one. Plugins switching to the
fork's signer need one reinstall; the Store says so.

## 6. Delivery B — system widgets

B1. Model: item ids in the reserved `sys:` namespace (`sys:clock`, `sys:weather`, `sys:status`;
`:` is invalid in plugin ids). A widget is enabled exactly when the stored layout has an entry for
it. `TileGridLayout.resolve` treats stored `sys:` ids this hub knows as present; older glasses
drop them as unknown.

B2. Glasses: widgets are placed and drawn but are not launcher entries: not in the selection ring,
not in list mode, never opened. Selecting the last interactive tile scrolls to the end of the
content, so widgets below it stay reachable. A grid of widgets only has no selection and ignores
Select. Drawn with the renderer (hub-internal layouts in `:hud-tiles`).

B3. Clock (local time, redrawn on the minute only while the home is visible) and Status (glasses
battery and link locally, phone battery from the existing `/phone/battery`).

B4. Weather: the phone hub fetches Open-Meteo (no key) about every 30 minutes while the link is up
and the widget is placed, using coarse location (last known location; a city typed in settings
when location is unavailable or denied), and sends it on a new trusted hub-to-hub path
`/phone/weather`. Never a plugin capability.

B5. Editor: "+ ADD WIDGET" lists the system widgets not yet placed; the selected card shows a
"SYSTEM" chip and REMOVE; sizes and the size preview as for plugins.

The home header stays as it is (widgets only, owner decision 2026-10-01).

## 7. File ownership after PR 0

| Delivery A | Delivery B |
|---|---|
| `WidgetTileContract`, `TileContent`, `TileController`, `TileCache`, `TileRateLimiter`, `TileSnapshotCache`, SDK (`bus-client`), lease in `BusHubService`, `MusicTileLayout`/`LinesTileLayout`/`ListTileLayout`, `plugins/*`, `plugin-feeds`, `RegistryClient`/Store | `TileGridLayout.resolve`, `TileLayoutContract`, `GridHome`/`HudStateMachine` selection and scroll, system widget layouts in `:hud-tiles`, `/phone/weather`, editor add/remove |

Shared files (`BusConstants`, `BUSSPEC.md`, `CHANGELOG.md`, `docs/PLUGIN_SDK.md`) are append-only
for both; whoever merges second resolves.

## 8. Validation

Every PR: the build/test commands in `AGENTS.md` for each module touched (shared, bus-client,
hud-tiles, both hubs; plugins with `-PskipCxrGlobal=true`), with the real output tail in the PR.
Every visible change: emulation (`docs/EMULATION.md`, API 32 emulator) with the fake phone or the
sample plugin publishing, glasses captures compared against the artboards.
