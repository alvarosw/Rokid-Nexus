# Tile layout editor: free placement from phone to glasses

Status: approved direction (product owner, 2026-09-30). Reference design: the "Rokid Nexus — Tile
Layout" canvas, https://claude.ai/artifact/WCUcnUNxFYg1hyjLjkNZPg (a 390x844 phone screen; its
source is mirrored in `docs/tile-layout/reference-Main.dc.html`). The phone screen follows that
layout and behavior exactly; the glasses show exactly what the phone previewed.

## 1. What changes and why

Today the phone editor is an ordered list (▲/▼ + size chips) and the glasses ignore the stored
`col`/`row`: `GridHome` re-runs `TileGridPacker` over the stored order. A free-placement editor
(holes allowed, drag with displacement) therefore cannot reach the glasses. Only four sizes exist.

After this work:

- Seven tile sizes: `1x1 2x1 3x1 1x2 2x2 3x2 3x3` (the picker shows them in that order).
- The stored `col`/`row` are authoritative on the glasses. Holes are kept.
- The phone preview uses the glasses' real grid geometry and the glasses' real visible-row count,
  so the "first view" fold line on the phone is where the glasses screen actually ends.
- Selection order on the glasses (ring next/prev) and the list-mode order are the grid's reading
  order (top-left corner by row, then column), so moving a tile on the phone moves it in both.

## 2. Shared model (`:shared`)

### 2.1 `TileSize`
Append (never reorder: `entries` order is load-bearing for defaults) `BANNER(3,1,"3x1")`,
`PANEL(3,2,"3x2")`, `JUMBO(3,3,"3x3")`. Add `TileSize.PICKER_ORDER` =
`[SMALL, WIDE, BANNER, TALL, LARGE, PANEL, JUMBO]`. `TILE_SIZES` manifest values accept the new
wire values (an older hub rejects them: document it in `docs/PLUGIN_SDK.md`).

### 2.2 `TileGridLayout` (new, pure Kotlin, `shared/.../tile/`)
A port of the reference's layout math, the single implementation both hubs use.

- `COLUMNS = 4`, `MAX_ROWS = 8` (the editor's limit; the glasses render whatever they get).
- `data class TileRect(col, row, cols, rows)`; `overlaps`, `fits(rect, placed)`.
- `placeAt(layout, id, rect)`: put `id` at `rect`; every tile it covers moves to the nearest free
  spot that fits it (distance `|dc| + 1.5·|dr| + 0.01·r`, displaced tiles handled in reading
  order of their old position); null if `rect` is out of bounds or a displaced tile has no room.
  Exactly the reference's `placeAt`.
- `packAll(layout)`: re-place every tile first-fit in the reading order of its current position
  (the "Auto-pack" button). Returns the input unchanged if anything does not fit.
- `resize(layout, id, size)`: rect = `(min(c, COLUMNS-w), min(r, MAX_ROWS-h), w, h)` then
  `placeAt`; null = "No room for W×H — shrink another tile first."
- `resolve(entries: List<Pair<id, TileSize?>>, stored: List<TileLayoutEntry>)`: the glasses'
  placement. Stored entries for ids present in `entries` are pinned in stored order if their rect
  is inside the 4 columns (no upper row bound here) and does not overlap an already pinned one;
  every other id (new plugin, camera, rejected entry) is placed first-fit, row-major, into the
  free cells, in `entries` order. Size: stored size, else the declared size passed in, else
  `SMALL`. With no stored layout this equals `TileGridPacker.pack` (tested).
- `readingOrder(placements)`: sort by `(row, col)`.

`TileGridPacker` stays (it is still the no-layout default and is referenced by tests/docs); it
may delegate to the new code only if its tests stay green unchanged.

### 2.3 `TileLayoutContract` v2
`VERSION = 2`; same payload shape. v2 means positions are authoritative; `entries` are written in
reading order. `entriesFromConfig` keeps accepting v1 (positions from v1 phones were produced by
the packer, so treating them as authoritative reproduces what those glasses showed). An old
glasses hub accepts v2 (it only rejects `version < 1`) and falls back to order + packing, dropping
entries whose size it does not know: acceptable degradation, documented in `BUSSPEC.md`.

### 2.4 Grid metrics (`:bus-client`, next to `RokidHudTokens`)
New `HudGridMetrics`: `UNIT = (CONTENT_WIDTH - 3·SPACE_2) / 4 = 106`, `GAP = SPACE_2`,
`PITCH = 114`, header/status/gap heights now private to `HomeScreenView`/`HomeHeaderView`/
`HudStatusView` move here, and `visibleRows(topInsetPx, screenHeight = CANVAS_HEIGHT)` = the exact
formula `HomeScreenView.fit()` + `GridHome.fitBody()` use today (min 2). `GridHome` and
`HomeScreenView` consume it (no numeric change; the Roborazzi baselines must not move).

### 2.5 Glasses → phone: visible rows
`GlassesHubCapabilities.homeGridVisibleRows: Int = 0` (additive JSON field; 0 = unknown). The
glasses compute it with `HudGridMetrics.visibleRows(HudTopInset px)` and re-announce capabilities
when it changes (HudTopInset already has listeners). The phone uses it for the fold line; when 0
(old glasses, never connected) it computes the same formula from its own stored inset (manual
mode) or inset 0 (auto mode), and labels the fold "estimated".

## 3. Glasses (`:glasses-hub`)

- `GlassesHub.allLauncherEntries()`: resolve the grid (`TileGridLayout.resolve`) over camera +
  plugin entries and return the entries in reading order (both modes). With no stored layout the
  order is today's (camera first, then catalog order).
- `GridHome.relayout`: placements come from `resolve` (via the `sizes` seam turned into a
  placement seam), not from `TileGridPacker.pack`; `totalRows` = bottom of the lowest tile, holes
  are empty. Whole-row scrolling and `followSelection` unchanged.
- `LiveTileView` / `FallbackTileView` render all seven sizes. Live tile anatomy, matching the
  reference tile: header (icon + uppercase name, `label`), title (or data value + unit), subtitle
  (width ≥ 2 or height ≥ 2), rows (height 1: none; 2: up to 3; 3: up to `MAX_ROWS`), badge, and a
  3 px progress track (`line` track, `text-primary` fill) at the foot when `progress != null`.
  Tone and focus chrome unchanged (dashed stays WARN only).
- Capabilities: report `homeGridVisibleRows`, re-announce on inset change.
- Tests: `TileLayoutStoreTest`, `TileLayoutIntegrationTest`, `GridHomeTest` (holes, 3x sizes,
  reading order, scrolling to a tile below a hole), `LiveTileViewTest`, Roborazzi captures for the
  new sizes, `GlassesHubLauncherTest` ordering.

## 4. Phone (`:phone-hub`)

Replace `TileLayoutSettingsActivity`'s ordered list with the reference screen, built with
`NexusUi` (its palette is the reference's phone palette: BG #070A08, INK, INK2, INK3, GREEN,
LINE, PANEL, AMBER…):

- Header: back, "TILE LAYOUT", "RESET" (restores the default layout: no stored layout, i.e. what
  `resolve` gives with an empty store; not saved until Save).
- Intro: "Drag a tile to move it — the others slide into the nearest space that fits. Tap a tile
  to change its size."
- "GLASSES HUD" label + "4 cols · N rows" (N = max(4, used + 1), capped at 8, as the reference).
- Preview (`TileLayoutCanvasView`, custom View): black rounded frame (16 dp, 1 px LINE, 10 dp
  padding) containing the glasses content area at `HudGridMetrics` geometry (448 px wide) scaled to
  the frame; dashed slot outlines (brighter while dragging), the tiles drawn with the glasses'
  tile anatomy and HUD tokens (green-48 border, green-06 fill; selected = 2 px green-100 +
  green-12; lifted = no animation, 92 % alpha), size label bottom-right, a dashed ghost at the
  snap target, the "SCROLL ↓" fold line after the glasses' visible rows, 200 ms tile moves.
  Content: the plugin's last `TileSnapshot` seen by this phone hub (in-memory cache fed by
  `/tile/publish` forwarding; never persisted), otherwise the fallback tile (glyph + name).
- Drag: long-press not required — a pointer move > 5 px lifts the tile; target = rounded grid
  cell under the tile's top-left, clamped; `placeAt` on every target change from the drag-start
  layout; cancel restores it. Tap selects. TalkBack: each tile is a virtual node with its
  description ("Name, W by H, column C, row R") and custom actions move left/right/up/down.
- Selected-tile card: glyph, name, "W×H · col C · row R", kind chip ("LIVE" when the plugin holds
  `widget_tile`, else "APP"), "TILE SIZE" + "k of 7 supported", 4-column grid of 7 size chips
  (mini 3x3 cell icon + label; selected filled GREEN, supported outlined, unsupported dashed and
  disabled), amber status line for "No room for …". Empty state: "Tap a tile to pick its size."
- System widgets (added with live tiles, `docs/live-tiles/00-plan.md` §6): "+ ADD WIDGET" under the
  preview opens a list of the widgets not on the grid (icon, name, one-line description, ADD);
  adding places the widget at its default size in the first free cell and selects it, or shows
  "No room for …" in the list. A selected widget's card shows the "SYSTEM" chip, only its
  supported sizes, a preview with sample content (current time; sample readings) and REMOVE.
  Widgets drag and auto-pack like tiles; RESET drops them (the default layout has none). The
  editor loads with `TileGridLayout.resolveWithWidgets`, so saving keeps stored `sys:` entries.
- The weather widget's preview shows the phone's last reading (else a Lisbon sample), and its card
  adds "WEATHER SETTINGS" above REMOVE, opening `WeatherSettingsActivity` (also linked from Display
  settings as "Weather widget"): "Use approximate location" (asks for coarse location there, never
  at app start), a City field with SET (confirmed through Open-Meteo geocoding: "Found: …" or "No
  place by that name."), AUTO/°C/°F unit chips and a STATUS card (on the grid or not, what the
  forecast is for, last update).
- Footer: "AUTO-PACK" (outlined) and "SAVE LAYOUT" (filled). Save writes
  `TileLayoutSettingsStore` (reading order), pushes via `BusHubService.onTileLayoutSettingChanged`,
  and shows "SENT TO GLASSES" for 1.8 s when the link is up, "SAVED · SYNCS ON CONNECT" otherwise.
  The screen stays open (as the reference).
- Entries: launchable plugins plus the camera entry when this phone's camera consumer is ready
  (same id `camera` and name the glasses use). Sizes offered: `TileSizeOptions` (declared subset,
  or all seven).
- Tests: `TileSizeOptionsTest`, `TileLayoutSettingsStoreTest`, a JVM test of the editor's state
  holder (drag, resize, reset, auto-pack, save order), Robolectric screenshot of the screen if the
  module has the harness.

## 5. Docs

`BUSSPEC.md` (tile-layout config v2, `homeGridVisibleRows`), `docs/PLUGIN_SDK.md` and
`plugins/AGENTS.md` (seven sizes, hub-version note), `docs/grid-hud-roadmap/04-...` header note,
`CHANGELOG.md`, `TileLayoutEntry` KDoc.

## 6. Validation

Unit/JVM: `:shared:test`, `:bus-client:testDebugUnitTest`, `:glasses-hub:testDebugUnitTest`,
`:phone-hub:testDebugUnitTest`, and both hubs `assembleDebug`. Emulation: Cuttlefish at 480x640
with the fake phone pushing a v2 layout (holes, 3x sizes, a tile below the fold) and a capture of
the glasses grid compared against the phone preview for the same layout.
