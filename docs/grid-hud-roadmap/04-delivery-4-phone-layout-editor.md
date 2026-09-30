> Current: the ordered-list editor described here is replaced by the free-placement editor in docs/tile-layout/00-plan.md; `TileSize` and the packer remain in use. The glasses-side grid it feeds is superseded by docs/ui-rewrite/ (kept for history); the tile data pipeline (03) remains current.

# Delivery 4 — Phone app layout editor

## Goal

Let the wearer, from the phone app, override Delivery 1's auto-pack default
by picking each plugin's tile size (from that plugin's declared
`TILE_SIZES` list, or the full fixed enum if it declared none, since the
generic fallback covers every size) and its position in the grid.

## Depends on

Delivery 1 only (needs the `TileSize` enum, the `TILE_SIZES` descriptor
field, and the auto-pack packer whose output this delivery overrides).
Independent of Delivery 2 and Delivery 3 — sizing/placement doesn't care
whether a tile currently shows generic fallback content or real published
data; it's the same box either way.

## Design system note (this delivery is the one exception)

Everything this delivery builds is **phone-app** UI — a normal Android
screen, not the glasses' micro-LED HUD. It should use the phone app's
existing design kit (`NexusUi` + `BusTheme`, per `docs/PLUGINS.md` §4),
**not** the new `RokidHudTokens` object from Delivery 1. Those are two
different displays with two different, intentionally separate design
languages (see the [overview](00-overview.md#critical-implementation-note-this-repo-already-has-a-different-design-system-and-it-is-not-this-one))
— don't cross-apply the glasses-only green-monochrome system to this
screen, and don't cross-apply `NexusUi`'s amber/danger accents to
anything on the glasses side either.

### Layout config (shared model + phone-side store)

```kotlin
data class TileLayoutEntry(
    val pluginId: String,
    val size: TileSize,
    val col: Int,
    val row: Int,
)
```

Stored phone-side following the same `*SettingsStore` shape used everywhere
else in this roadmap (`GlassesRepairSettingsStore` pattern) —
`TileLayoutSettingsStore` holding a `List<TileLayoutEntry>` (serialized JSON
in `SharedPreferences`, consistent with how other structured phone-side
state is already persisted in this codebase), re-pushed to the glasses on
every capabilities announce, same as the mode flag.

### Size picker

Per plugin, the phone UI offers only the sizes in its `TILE_SIZES`
descriptor field (already synced down as part of the existing plugin
registry sync — no new sync mechanism needed, `PluginDescriptor` already
travels phone→glasses today). A plugin with an empty/absent list still
offers the full fixed enum, since Delivery 1's generic renderer covers any
size with no plugin cooperation required.

### Placement + collision handling

Two reasonable options, either satisfies the ask; pick based on how much
phone-app UI effort is budgeted:

1. **Simple (recommended for this delivery):** an ordered list with a size
   picker per row; position is implied by list order, re-run through
   Delivery 1's `TileGridPacker` on the glasses side using this order
   instead of the install-order default. No collision UI needed at all,
   because the packer already guarantees no overlap by construction — this
   reuses Delivery 1's code unchanged, just feeds it a different order.
2. **Richer:** an actual drag-and-drop grid canvas in the phone app with
   explicit `col`/`row` per tile and a repack-on-conflict rule when a
   newly-installed plugin needs a spot. This needs real collision
   resolution logic (not present anywhere in the codebase today, since
   nothing has ever needed 2-D placement before) and is a materially bigger
   phone-app UI investment.

Recommendation: ship option 1 first as this delivery; option 2 can be a
later, separate, equally-independent delivery if the ordered-list picker
turns out not to feel expressive enough in practice — the underlying model
(`TileLayoutEntry`) supports both without rework.

### New plugin installed after a custom layout exists

Falls back to Delivery 1's auto-pack default, appended after the
custom-ordered entries, until the wearer explicitly places it — the same
"missing config isn't an error, it's the default" posture used everywhere
else in this roadmap (unset `TILE_SIZES`, unset layout entry, both resolve
to a sane built-in default rather than a special case to handle).

## Acceptance criteria

1. Picking a size outside a plugin's declared list is not offered by the
   UI (or the phone UI shows a size not supported and the glasses correctly
   fall back to the generic renderer at that size, if unsupported sizes are
   allowed to be picked anyway — pick one behaviour and test it explicitly,
   don't leave it undefined).
2. Reordering in the phone app changes the glasses grid's layout on next
   sync, with no overlap, using the existing packer.
3. A newly installed plugin with no explicit layout entry appears via the
   default auto-pack rule without disturbing already-placed tiles' order.
4. Layout changes made while disconnected land correctly on reconnect (same
   re-push-on-announce mechanism as the mode toggle).

## Verification

**Automatable now:**
- `TileLayoutSettingsStore` unit tests (serialize/deserialize round-trip,
  default-empty behaviour, mirrors `GlassesRepairSettingsStoreTest.kt`).
- `TileGridPacker` re-tested with a custom order input (already built for
  Delivery 1; this delivery only adds test cases with a non-default order,
  not new packer logic).
- Phone-app UI logic (which sizes are offered per plugin, given a
  `TILE_SIZES` descriptor) as a plain unit test against the descriptor
  parsing from Delivery 1 — no device needed, it's a pure function of
  already-tested data.
- Build commands (per `AGENTS.md`):
  `./gradlew :shared:test -PskipCxrGlobal=true`, and, without that flag
  (the hubs link the vendor CXR library):
  `./gradlew :phone-hub:testDebugUnitTest :phone-hub:assembleDebug` and
  `./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug`.
  Report plainly if any fail for an environment reason rather than working
  around it.

**Requires on-device check:**
- The actual phone-app editing UX (drag/reorder feel, if option 2 is ever
  built) on a real phone screen.
- Visual confirmation that a reordered grid renders in the expected order
  on the real glasses display end-to-end through a real sync.
