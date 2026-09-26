# Grid HUD — delivery roadmap

Goal: replace the current list-style glasses launcher with an always-visible,
grid-based dashboard (the "widget tiles" HUD design), as an **additional**
mode the wearer opts into from the phone app — the existing list launcher
stays fully supported and is the default.

This directory breaks that work into deliveries that can each be built,
reviewed, and verified **on their own**, without any of them depending on a
delivery that hasn't shipped yet. Later deliveries only ever depend on
earlier ones.

## Delivery map

| # | Delivery | Depends on | New runtime behaviour it unlocks |
|---|---|---|---|
| 1 | [Grid shell, sizing model & mode toggle](01-delivery-1-grid-shell-and-sizing.md) | — | Grid launcher exists, selectable from the phone app, every plugin renders as a fallback tile at a user-chosen predefined size, **built on ported design-system tokens**, and **tapping a tile really opens the plugin** — both required from this delivery, not later. Zero live data. |
| 2 | [Expansion motion & focus polish](02-delivery-2-motion-and-focus.md) | 1 | Tapping a tile morphs it into the plugin's real (existing) open-state surface and back, matching the reference design's motion. |
| 3 | [Tile data pipeline](03-delivery-3-tile-data-pipeline.md) | 1 | The `WidgetTileContract` wire protocol, capability, rate limiting and staleness handling exist and are proven with a synthetic publisher. No shipped plugin uses it yet. |
| 4 | [Phone app layout editor](04-delivery-4-phone-layout-editor.md) | 1 | Wearer can pick each plugin's tile size (from its declared list) and position from the phone app instead of the auto-pack default. |
| 5+ | [Plugin adoption batches](05-plugin-adoption-batches.md) | 3 | Each batch of plugins starts publishing real closed-state data into tiles that already render correctly (deliveries 1–3 proved the pipeline against fake data first). |

Deliveries 2, 3 and 4 are mutually independent once 1 has shipped — they can
be built and released in any order or in parallel. Delivery 5+ items are
independent of each other and of 2/4.

## What "full job done" means here (scope boundary)

Per the agreed boundary: deliveries 1–4 make the HUD behave **exactly** like
the reference design (`design-mockups` artifact) — layout, sizing, motion,
navigation, the settings surface — with one explicit exception: closed-state
tiles show generic fallback content (icon + name) until a plugin adopts the
Delivery 3 contract. Open-state (what you see once you tap a tile) is
untouched throughout 1–4: it already works today via the existing
`SurfaceController` / `NexusSurface` pipeline (`glasses-hub/.../SurfaceModels.kt`,
`glasses-hub/.../SurfaceController.kt`) and none of these deliveries change it.

## Design system — source of truth (governs every delivery below)

Two references anchor this whole roadmap, both read in full while planning it:

- **Reference screen**: the original HUD mockup, `https://claude.ai/artifact/2SaWsHrydmE4ECqXi7RQTK` — the exact grid/tile/expand behaviour every delivery targets.
- **Design system**: "Rokid HUD" v1, `https://claude.ai/artifact/UheQVnEGeH8Y1QRK5c1oBK` — a Design System artifact (`project/tokens.json`, `project/README.md`, `project/components/*`). This is the actual specification; the mockup is a rendering of it, verified line-for-line against `tokens.json` while planning this roadmap (its CSS variables are the same values under different names — see the viewport note below for the one place that's worth being precise about).

**Non-negotiable rule the whole system is built on**: a single hue, `#40FF5E`, in six intensities only. Never introduce red, amber, or blue — not even for "critical." Every state is communicated by **intensity + border shape + icon + text (+ motion when useful)**, never by color alone. This applies to every tile, status indicator, and focus state built in any delivery below.

### Exact tokens (port these values verbatim — do not re-derive or approximate them)

**Color** (six intensities of `#40FF5E`, plus semantic aliases):

| Token | Value | Alias | Usage |
|---|---|---|---|
| `green-100` | `#40FF5E` | `focus`, `critical` | Focus ring, critical state. One element at a time. Never large fills (bloom). |
| `green-72` | `rgba(64,255,94,0.72)` | `text-primary` | Primary text/information. |
| `green-48` | `rgba(64,255,94,0.48)` | `text-secondary`, `line-control` | Secondary text, labels, control borders. |
| `green-24` | `rgba(64,255,94,0.24)` | `line` | Dividers, panel borders, progress tracks. Structure only — never text. |
| `green-12` | `rgba(64,255,94,0.12)` | `surface-selected` | Local background of a selected item. |
| `green-06` | `rgba(64,255,94,0.06)` | `surface-subtle` | Extremely subtle panel emphasis. Never more than this. |
| `ground` | `#000000` | — | Background. **On-device, black is transparent (unlit pixel) — the real world shows through.** Never fill a large area with any other intensity as "background." |
| `on-emphasis` | alias of `ground` | | Text on a rare filled `green-100` badge. |

**Typography** — `sans-serif` for UI, `monospace` for numbers/technical data (prevents digits from "dancing" on update). At most **one `display`-style element per screen**:

| Style | Size / line-height / weight | Family | Usage |
|---|---|---|---|
| `display` | 22px / 28px / 600 | sans | One value/title per screen, max. |
| `heading` | 16px / 22px / 600 | sans | Panel/screen title. |
| `body` | 14px / 20px / 400 | sans | Default text: buttons, list items, messages. |
| `body-small` | 12px / 16px / 400 | sans | Supporting text, metadata. |
| `label` | 11px / 14px / 500, letter-spacing 0.06em | sans | Data labels, panel titles — UPPERCASE, `text-secondary`. |
| `caption` | 10px / 14px / 400 | sans | Minimal notes. Avoid; never essential info. |
| `data` | 13px / 18px / 500 | mono | Numeric values in `DataReadout`, lists, charts. |
| `mono` | 11px / 14px / 400 | mono | Coordinates, codes, progress percentages. |

**Spacing** (4px grid): `space-1` 4px (icon↔text) · `space-2` 8px (row gap/list padding) · `space-3` 12px (panel/button padding) · `space-4` 16px (between groups) · `space-6` 24px (between info blocks — prefer this over adding another panel). **Safe area**: `safe-x` 16px, `safe-y` 12px.

**Radius**: `radius-control` 4px (buttons, list items, small controls) · `radius-panel` 6px (panels) · `radius-data` 2px (bars, chart markers, data chips).

**Borders**: `border-default` 1px (`line` for panels, `line-control` for buttons) · `border-strong` 2px (focus/active/critical only, in `focus`/`critical`).

**Icons**: monoline, 1px constant stroke regardless of size (`vector-effect: non-scaling-stroke`), sizes `icon-sm` 16 / `icon-md` 20 / `icon-lg` 24. Fixed set only: `check, cross, alert, info, chevron-up/down/left/right, arrow-up/down, circle, dot, clock, mic, battery`. No emoji, no filled icons except `dot`. Icon inherits the intensity of the text next to it.

**Motion** (communicates state, never decorates): `duration-feedback` 120ms (press) · `duration-default` 200ms (normal transition, focus change) · `duration-structural` 320ms (layout/screen change) · `duration-ambient` 6s (rare, 4–8s range) · `duration-scan` 1200ms (Loader cycle, only while real work is in flight). **Must respect reduced-motion**: when the system's animation-scale/reduce-motion setting is on, swap motion for a static end-state + text — there is no CSS media query on Android for this, so the equivalent check is the system's animator-duration-scale (or an accessibility "remove animations" setting) queried at animation-start time. No decorative loops, no continuous glow/breathing, no permanent parallax.

**Viewport — read this carefully, it's the one place naming differs from the numbers**: the design system defines *two* canvases. `display-width`/`display-height` (480×400) is the **physical** display. `aiui-width`/`aiui-height` (480×352) is the **reference viewport** used for design — not a resolution claim about the extra 48px, and not something to treat as forbidden either. **The original mockup's `--display-height: 352px` variable is numerically the AIUI reference viewport, not the physical display** — despite its name. Every delivery below targets the 480×352 reference canvas (matching the mockup, matching `HudFrame`'s guidance: "design for 352, take advantage of 400 when the runtime allows"), inset by `safe-x`/`safe-y` to a `content-width` of 448px — which is exactly the mockup's own `EXP` inset constants (`left:16, top:12, width:448`). This is a confirmed match, not a coincidence: the mockup was built against these exact tokens.

### Component contracts that constrain this roadmap's UI work directly

- **`HudFrame`** — the required root of every screen: applies `safe-x`/`safe-y` automatically. Never paint the background — `ground` is transparent on-device.
- **`Panel`** — outline only (`border-default` in `line`, `radius-panel`), transparent fill by default, `emphasis="subtle"` adds *at most* `surface-subtle` (6%). No shadows, no nested panels, no large filled cards. If `space-6` whitespace is enough to separate two things, don't wrap either in a `Panel`.
- **`ListItem`** — fixed 32px row height, optional 20px icon, `body` label, `data`-styled value, optional chevron. Selected = `surface-selected` background + 1px `text-primary` border. Focused = 2px `focus` border + `focus` text, one at a time. **Show at most 3–4 items simultaneously** — this is the hard cap for any list-shaped tile's expanded content, not an arbitrary number.
- **`Status`** — the only sanctioned way to show state: `ok` (check, 72%, 1px line) · `info` (info icon, 48%/text 72%, 1px line) · `warn` (alert icon, 72%, 1px *dashed* line-control) · `critical` (alert icon, 100%/`critical`, 2px border, blinks 3× at `duration-default` then settles — never loops continuously, and only one `critical` on screen at a time) · `off` (circle icon, 48%, 1px line). Any "urgent/normal/far" style tone anywhere in this roadmap (tiles, list rows) must map onto this five-value set — not a bespoke color or an ad hoc string.
- **`DataReadout`** — the mandated shape for any bare number: `label` (uppercase, 48%) + `data`-styled value (mono, 13px, or 22px mono `display`-equivalent for the one hero number per screen) + optional `unit` (mono, 48%) + optional trend arrow. Governs how countdowns, temperatures, and numeric badges render.
- **`Loader`** — the mandated in-flight indicator: `scan` (sweeping `focus` segment on a `line` track), `point` (back-and-forth dot), or `progress` (`text-primary` fill + `mono` percentage) when the value is known. 1200ms cycle, static under reduced-motion, only while real work is happening — never a decorative idle animation.
- **`Icon`** / **`Button`** — per the token tables above; `Button` specifically caps at one `variant="primary"` per screen.

### Critical implementation note: this repo already has a *different* design system, and it is not this one

`bus-client/src/main/java/com/anezium/rokidbus/client/ui/BusTheme.kt` — "RokidBus design system — quiet premium terminal" — is the theme **currently** used across the phone app's settings screens (`NexusUi`, per `docs/PLUGINS.md` §4) *and* the current glasses-side list launcher (`LauncherMenuView` uses `BusTheme.phosphor/dim/hairline/glassesBg/text` directly today). It uses a different green (`#71FF97`), a near-black-but-not-transparent background convention, a `danger` red token, and none of the type/spacing/radius/icon/motion contracts above.

**Do not extend or reuse `BusTheme` for the new grid HUD.** It must stay exactly as-is for every surface that already ships on it (phone settings, current list launcher, notices, etc.) — none of that is in scope here and none of it should regress. The new grid HUD needs its **own** token object, ported verbatim from `tokens.json` above, consumed only by the new grid/tile/motion code this roadmap adds. This porting work is the first concrete task in [Delivery 1](01-delivery-1-grid-shell-and-sizing.md) — it is a prerequisite for that delivery's UI, not a separate delivery.

One reassuring exception: `BusTheme.glassesBg = Color.BLACK` on the glasses side is already correct under the new system too (black = unlit = transparent either way) — it's the accent colors, typography, spacing, radii, icon set, and component behaviour that must not be inherited from `BusTheme`.

Also note: the design system's own component bundle (`window.RokidHUD`, React 18) is a **reference for visual/behavioural porting**, written for an AIUI (WXML/WXSS)-style runtime or web preview. `glasses-hub` renders natively via plain Android `View`/Canvas code (`InkHudView`, `SurfaceHudView`, `LauncherOverlayRenderer` — no AIUI, no WXSS, no Compose, no WebView anywhere in this repo). "Following the design system" means porting its exact token values and component visual rules into native Kotlin `View` rendering — it does not mean embedding the bundle, a WebView, or any AIUI runtime.

## Verification philosophy (see each delivery's own section for specifics)

Every delivery separates verification into two tiers, because of what's
actually checkable here versus what needs the physical hardware:

- **Automatable now** — anything expressible as a JVM unit test, an Android
  lint pass, or a Robolectric/instrumented test that doesn't require the
  Rokid glasses' accessibility-overlay window or its ring input hardware.
  These are run as part of each delivery and are the actual merge gate.
- **Requires on-device check** — the physical animation feel, ring-input
  timing/dedupe against real hardware, display burn/battery behaviour, and
  anything routed through the authenticated SPP link to a real paired
  glasses unit. These are called out explicitly per delivery as manual
  checks for the person doing the on-device pass; they are not skipped, just
  not something this planning/build environment can execute itself.

## Cross-cutting pieces introduced once, referenced by every delivery below

- **A new native token object** (name suggestion: `RokidHudTokens`, deliberately *not* part of `BusTheme` — see the design-system section above), ported verbatim from `tokens.json`, plus a `HudFrameLayout`-equivalent base container applying `safe-x`/`safe-y` and never painting an opaque non-`ground` background. Built in Delivery 1, consumed by every delivery after it.
- **`BusConstants.META_PLUGIN_TILE_SIZES`** (new manifest metadata key,
  `shared/src/main/java/com/anezium/rokidbus/shared/BusConstants.kt`) — a
  plugin's declared list of supported tile sizes, parsed alongside the
  existing `META_PLUGIN_ICON`/`GLYPHS`/`CAPABILITIES` keys in
  `PluginDescriptorParser` (`shared/.../plugin/PluginDescriptor.kt`).
- **Fixed tile-size enum**, shared type used by both hubs and the phone app:
  `TileSize { SMALL /*1x1*/, WIDE /*2x1*/, TALL /*1x2*/, LARGE /*2x2*/ }`.
- **Settings-store pattern**: every new phone-side toggle in this roadmap
  (`HudModeSettingsStore`, `TileLayoutSettingsStore`) follows the existing
  `GlassesRepairSettingsStore` shape — a small `SharedPreferences` wrapper,
  a paired `*Contract` object in `shared` holding the default and wire keys,
  and a re-push on every glasses capabilities announcement so a change made
  while the link is down still lands (see
  `phone-hub/.../GlassesRepairSettingsStore.kt` and the "re-pushes it on
  every glasses capabilities announce" comment there — this roadmap reuses
  that exact mechanism rather than inventing a new one).
