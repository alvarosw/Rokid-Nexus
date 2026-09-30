# Glasses UI: current behavior specification

Purpose: a precise, code-derived description of what the glasses-side UI of `:glasses-hub`
does today, so the rewrite (one persistent overlay host, layered, driven by one pure state
machine) can reach parity and every behavior becomes a test. This document describes; it does
not propose the new design.

Baseline: git `HEAD` = `fa9c7715` (tracked tree clean when this was written; only untracked
screenshots present). All `file:line` citations are `HEAD` line numbers. A later working-tree edit
by another task adds 7 lines to `G/GlassesHub.kt` after line 1250 (a debug `outboundInterceptor`),
so `GlassesHub.kt` citations above 1250 are exact and those below are 7 lines off in the working
tree (`git show HEAD:glasses-hub/.../GlassesHub.kt` matches this document).
The stashed WIP (`stash@{0}`, "pre-upstream-sync: grid launcher WIP patches") was read with
`git stash show -p` only and is described in section 6.4. Statements below describe `HEAD`
unless they say "stash".

Things I could not verify from code alone are marked **(unverified)**; they are listed again in
appendix C.

## 0. Conventions

Path prefixes used in every citation (`prefix/File.kt:line`):

| Prefix | Expands to |
|---|---|
| `G/` | `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/` |
| `S/` | `shared/src/main/java/com/anezium/rokidbus/shared/` |
| `B/` | `bus-client/src/main/java/com/anezium/rokidbus/client/ui/` |
| `M` | `glasses-hub/src/main/AndroidManifest.xml` |
| `R/` | `glasses-hub/src/main/res/` |

Short file aliases used in tables and the checklist (all under `G/`): `RBAS` =
`RokidBusAccessibilityService.kt`, `LOR` = `LauncherOverlayRenderer.kt`, `SOR` =
`SurfaceOverlayRenderer.kt`, `SC` = `SurfaceController.kt`, `NOR` = `NoticeOverlayRenderer.kt`,
`RFC` = `RingFocusCoordinator.kt`, `LRC` = `LauncherReturnCoordinator.kt`, `MA` = `MainActivity.kt`.
In section 7 a bare `File:lines` citation means `G/File.kt:lines`; `S/` and `B/` are always spelled out.

Display. The design reference canvas is 480x352 px (`B/RokidHudTokens.kt:92-93`,
`CANVAS_WIDTH`/`CANVAS_HEIGHT`), inset by `SAFE_X = 16dp` and `SAFE_Y = 12dp`
(`B/RokidHudTokens.kt:65-66`). The emulator harness sizes the display 480x352 at 240 dpi
(`tools/emulator/env.sh:5-6`), that is density 1.5, so the canvas is 320 x 234.7 dp. Two other
sizes appear in the repo and are not reconciled here: the roadmap calls 480x400 the physical
display (`docs/grid-hud-roadmap/00-overview.md`, "Viewport" paragraph), and
`G/StatusBadgeGeometry.kt:7-9` quotes ROM-launcher coordinates measured on a 480x640 @1.5 layout.
Every pixel number below is per the 480x352 canvas unless stated.

Vocabulary: "overlay" = a window of type `TYPE_ACCESSIBILITY_OVERLAY` added by
`RokidBusAccessibilityService` (the service, abbreviated `RBAS`). "ring" = the Rokid R08 ring.
"temple touchpad" = the glasses' own touchpad. "ROM launcher" = `com.rokid.os.sprite.launcher`.
"Handoff" = the interval between the wearer choosing a launcher entry and the plugin's surface
arriving.

## 1. Inventory of UI surfaces

### 1.1 Every surface, who creates and removes it, and how it is drawn

| # | Surface | Created by | Removed by | Window type and flags | Cite |
|---|---|---|---|---|---|
| 1 | **Launcher overlay** (list or grid) | `LauncherOverlayRenderer.show()`: triple tap, `OPEN_LAUNCHER` broadcast, return-from-plugin | `hide()`: BACK, ring double tap, entry selected, `OpenLauncherReceiver` toggle, `SurfaceController.stepLauncherAside`, service destroy | `TYPE_ACCESSIBILITY_OVERLAY`, `MATCH_PARENT` x `MATCH_PARENT`, flags `FLAG_LAYOUT_IN_SCREEN or FLAG_KEEP_SCREEN_ON`, `PixelFormat.TRANSLUCENT`. Focusable and touchable (no `NOT_*` flags). Root `FrameLayout` is `isFocusable`; both content views paint opaque black. | `G/LauncherOverlayRenderer.kt:83-131` (params 91-98), `:138-156`, `:245-287` |
| 2 | **Surface overlay** (default path for cards, lists, readers, media, image, timed lines, ink) | `SurfaceOverlayRenderer.show()` from `SurfaceController.displaySurface`; also re-shown when the service reconnects with an active overlay surface | `SurfaceOverlayRenderer.hide()` from `hideLocalOnMain` and `showActivity` | Same params and flags as the launcher overlay (`FLAG_LAYOUT_IN_SCREEN or FLAG_KEEP_SCREEN_ON`, TRANSLUCENT, focusable, touchable). Hosts one `SurfaceHudView`. | `G/SurfaceOverlayRenderer.kt:15-26`, `:36-67` (params 40-47), `:69-75`; `G/SurfaceController.kt:609-655`, `:729` |
| 3 | **`SurfaceActivity`** (ACTIVITY display path) | `SurfaceController.showActivity` (`startActivity` with `NEW_TASK or SINGLE_TOP`) | `finishAndRemoveTask()` when the active surface becomes null or is ink | Manifest: `singleTask`, own `taskAffinity=...glasses.surface`, `excludeFromRecents`, portrait, `Theme.RokidBus.Surface` (translucent, fullscreen, transparent background). Code: `setShowWhenLocked(true)`, `setTurnScreenOn(true)`, `FLAG_KEEP_SCREEN_ON`. Hosts its own `SurfaceHudView`. | `M:80-87`; `R/values/styles.xml:13-25`; `G/SurfaceActivity.kt:14-38`; `G/SurfaceController.kt:1022-1035` |
| 4 | **`MainActivity`** | Launcher app icon (`MAIN`/`LAUNCHER`); `SetupEntryActivity.openOnboarding`; `RBAS.returnToOnboarding()` (`NEW_TASK or CLEAR_TOP`) | `finish()` by user BACK; `MainActivity.finishIfStale()` from `SurfaceController` | Manifest: `singleTask`, portrait, `Theme.RokidBus.Glasses` (opaque, fullscreen). Holds two screens in one activity: onboarding (self-arm setup) and a list-only launcher fallback. | `M:55-64`; `G/MainActivity.kt:26-664`; `G/SetupEntryActivity.kt:54-60`; `G/RokidBusAccessibilityService.kt:1114-1119` |
| 5 | **`SetupEntryActivity`** | Started by the phone over CXR ("Start setup") | `finish()` immediately in `onCreate` | Translucent, `noHistory`, `excludeFromRecents`, exported. Draws nothing; opens Accessibility settings or hands to `MainActivity`. | `M:69-75`; `G/SetupEntryActivity.kt:16-52` |
| 6 | **`CameraActivity`** (camera viewfinder; `TextureView` plus `CameraOverlayView` plus empty-state text) | `GlassesHub.openLauncherEntry("camera")` (`NEW_TASK`) | Default Activity BACK/finish; nothing in Nexus routes back to the launcher afterwards | Runs in **process `:camera`**; `Theme.RokidBus.Surface`; `FLAG_KEEP_SCREEN_ON`; portrait; immersive system UI; black decor. | `M:89-96`; `G/CameraActivity.kt:76-107`, `:110-122`, `:817-823`; `G/GlassesHub.kt:587-595` |
| 7 | **Nexus IME** (`NexusRemoteInputMethodService`) | Selected by the wearer/`GlassesKeyboardKeeper`; bound by the system when a field gets input focus | System | Draws no keyboard: 1x1 transparent input view, `onEvaluateInputViewShown() = false`, empty touchable region. Forwards editing to the phone through `RemoteInputController`. | `M:111-124`; `G/NexusRemoteInputMethodService.kt:10-61`; `G/GlassesKeyboardKeeper.kt:21-66` |
| 8 | **Notice band overlay** (full-screen container, black scrim plus a band child) | `NoticeOverlayRenderer.ensureWindow` on first non-null notice render | `teardown()` after the exit animation, or immediately for the Ink morph | `TYPE_ACCESSIBILITY_OVERLAY`, `MATCH_PARENT`, gravity `TOP or START`, flags `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_KEEP_SCREEN_ON`, TRANSLUCENT. The band is a child (width 92% of the display, top margin `12dp + hudTopInset`); the window itself never animates. | `G/NoticeOverlayRenderer.kt:39-42`, `:271-316`, `:329-337`, `:339-360` |
| 9 | **Pin overlay** | `PinOverlayRenderer.render` on a non-null pin | `hide()` on null pin, expiry, camera-overlay flag | `WRAP_CONTENT` window, `TYPE_ACCESSIBILITY_OVERLAY`, flags `NOT_FOCUSABLE or NOT_TOUCHABLE`, TRANSLUCENT, gravity set to one of four corners with `x = 12dp`, `y = 12dp (+ topInset for top corners)`. Layout is `updateViewLayout`-ed on content and inset changes. | `G/PinOverlayRenderer.kt:61-101`, `:113-136` |
| 10 | **Activity overlay** (one full-screen window holding one `HudIslandView` per activity, max 2) | `ActivityOverlayRenderer.ensureWindow` when at least one non-hidden item exists | `dismissAll()` after islands fold, or `teardown()` when all items are HIDDEN (camera) | `MATCH_PARENT`, `TYPE_ACCESSIBILITY_OVERLAY`, flags `NOT_FOCUSABLE or NOT_TOUCHABLE`, gravity `TOP or START`. The window is never animated or updated. | `G/ActivityOverlayRenderer.kt:87-176`, `:222-264`; `S/ActivitySurfaceContract.kt:145` |
| 11 | **Status badge** (phone battery chip inside the ROM launcher's status row) | `StatusBadgeOverlayRenderer.render` when the ROM launcher is the top window and a phone reading exists | `hide()` | `WRAP_CONTENT` x 20dp, `TYPE_ACCESSIBILITY_OVERLAY`, flags `NOT_FOCUSABLE or NOT_TOUCHABLE or LAYOUT_NO_LIMITS`, gravity `TOP or START`, x/y placed from ROM view bounds. | `G/StatusBadgeOverlayRenderer.kt:197-253`, `:255-368` |
| 12 | **Remote pointer** cursor | `RemotePointerOverlayRenderer.show` on `/core/pointer/command` (show/move/click/long_press) | `hide()` on `hide`, 8 s idle, link loss, service destroy | `MATCH_PARENT`, `TYPE_ACCESSIBILITY_OVERLAY`, flags `NOT_FOCUSABLE or NOT_TOUCHABLE or LAYOUT_IN_SCREEN or LAYOUT_NO_LIMITS`, TRANSLUCENT. A custom `View` draws a 11 dp-radius ring. | `G/RemotePointerOverlayRenderer.kt:251-306`; `G/RemotePointerController.kt:57-187` |
| 13 | **Camera overlay layer** (inside `CameraActivity`, not a window of its own) | `CameraOverlayView` child of the camera content view | With the activity | Plain view; reports attach/detach to the main process by broadcast. | `G/CameraActivity.kt:167-185`; `G/CameraOverlayView.kt:110-120`; `G/CameraOverlayVisibilityBridge.kt:13-104` |
| 14 | Debug-only | `CameraFixtureActivity`, `GridLauncherScreenshotHostActivity`, `ProbeBroadcastReceiver`, `DebugInkBroadcastReceiver` | | Declared in the debug manifest only. `ProbeBroadcastReceiver` can show demo cards on either display path, or call `openLauncherEntry`. | `glasses-hub/src/debug/AndroidManifest.xml`; `G/ProbeBroadcastReceiver.kt:11-30`; `G/SurfaceController.kt:218-250` |

Non-window entry points that change UI state:

- `OpenLauncherReceiver`, exported, no permission, action
  `com.anezium.rokidbus.glasses.action.OPEN_LAUNCHER`. It **toggles**: hides if shown, else shows
  (`"show failed: accessibility service not connected"` when the service is down). `M:156-162`;
  `G/OpenLauncherReceiver.kt:7-19`. `tools/emulator/ring.sh:11` uses it.
- `CameraOverlayVisibilityReceiver` (not exported), receives the camera process's
  attach/detach edge and forwards it to `ActivityController.setCameraOverlayActive` only.
  `M:143-145`; `G/CameraOverlayVisibilityBridge.kt:93-104`.
- `BusHubService`, `BootReceiver`: only start `GlassesHub`, no UI. `G/BusHubService.kt`,
  `G/BootReceiver.kt:11-40`.

Accessibility service configuration: event types all, flags
`flagRequestFilterKeyEvents|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews|flagReportViewIds`,
`canPerformGestures`, `canRetrieveWindowContent`, `notificationTimeout=50`
(`R/xml/rokidbus_accessibility_service.xml`). At connect it additionally ORs in
`TYPE_WINDOW_STATE_CHANGED|TYPE_WINDOWS_CHANGED` and
`FLAG_REQUEST_FILTER_KEY_EVENTS|FLAG_RETRIEVE_INTERACTIVE_WINDOWS` (`G/RokidBusAccessibilityService.kt:64-71`).

### 1.2 Renderer wiring order at service connect

`RBAS.onServiceConnected` connects, in this order: `RemoteNavigationController`,
`RemotePointerController`, `RingFocusBroadcastCoordinator` (seeded with
`SurfaceController.activeSurface() != null` and `NoticeController.ownsRingInput()`),
`SurfaceOverlayRenderer` (which immediately re-shows an active OVERLAY-path surface),
`PinOverlayRenderer`, `ActivityController`, `NoticeController`, `ActivityOverlayRenderer`,
`NoticeOverlayRenderer`, `LauncherOverlayRenderer`, `StatusBadgeOverlayRenderer`, then
`GlassesHub.start` and the display standby watchdog (`G/RokidBusAccessibilityService.kt:75-96`).
Because `SurfaceOverlayRenderer` connects before the ambient renderers have a `WindowManager`,
the re-assert after that first re-show is a no-op (`G/SurfaceOverlayRenderer.kt:18-25`,
`G/HudOverlayStack.kt:28-33`).

`onDestroy` removes launcher, badge, pin, activity, surface, notice, pointer windows, cancels ring
input in the surface/notice/activity controllers, resets `NoticeKeyDispatcher` and the ring focus
broadcaster, clears `consumedDownKeys` (`G/RokidBusAccessibilityService.kt:277-309`). The launcher is
**not** restored on reconnect; an active OVERLAY-path surface is (`G/SurfaceOverlayRenderer.kt:18-25`).

### 1.3 Z-order rules (`HudOverlayStack`)

Windows of type `TYPE_ACCESSIBILITY_OVERLAY` stack in the order they were added; every overlay
sits above every application window (`SurfaceActivity`, `MainActivity`, `CameraActivity`, the ROM).
The only ordering mechanism is `HudOverlayStack.reassert()`, which calls `ensureOnTop()` on, in this
order, **Pin, Activity, Notice, RemotePointer** (`G/HudOverlayStack.kt:19-34`). Each `ensureOnTop()`
is `removeView` followed by `addView` of the same root and the same params (pin
`G/PinOverlayRenderer.kt:51-59`, activity `G/ActivityOverlayRenderer.kt:78-85`, notice
`G/NoticeOverlayRenderer.kt:150-157`, pointer `G/RemotePointerOverlayRenderer.kt:287-295`); each no-ops
if nothing is on screen.

Intended stack, bottom to top: `[launcher | surface overlay] < pin < activity < notice < pointer`.
Status badge is outside the stack; it is a small chip that only exists while the ROM launcher, not a
Nexus window, is the top window.

Where `reassert()` is called: after adding the launcher root (`G/LauncherOverlayRenderer.kt:100`),
after adding the surface root (`G/SurfaceOverlayRenderer.kt:56`), and after creating the activity
window (`G/ActivityOverlayRenderer.kt:174`). It is **not** called when the pin window, the notice
window, or the pointer window is created (`G/PinOverlayRenderer.kt:71-84`,
`G/NoticeOverlayRenderer.kt:306-315`, `G/RemotePointerOverlayRenderer.kt:262-273`). Consequence: the
stated order is only guaranteed immediately after one of the three calling events; a pin created
while a notice is on screen ends up above the notice, and a notice created while the pointer is
visible ends up above the pointer. See F-17.

A notice opened over an open launcher is explicitly supported and depends on the re-assert
(`G/HudOverlayStack.kt:3-17`).

## 2. Screen states and transitions

### 2.1 States

Foreground ("what the wearer is looking at"), mutually exclusive in intent but not enforced by one
owner (each row lists what the code actually keys off):

| State | Meaning in code |
|---|---|
| `IDLE` | No Nexus full-screen window: `LauncherOverlayRenderer.isShown()` false, `SurfaceController.activeSurface() == null`, no Nexus activity resumed. The ROM home or a foreign app shows. |
| `LAUNCHER_LIST` / `LAUNCHER_GRID` | `LauncherOverlayRenderer.root != null` (`isShown`, `G/LauncherOverlayRenderer.kt:76`); mode fixed at `show()` (`:104`). |
| `HANDOFF` | Launcher entry sent (`launcherOpen=true`), surface not yet arrived. At `HEAD` the launcher is already hidden here. Ring focus is retained by a 10 s timer (`G/RingFocusCoordinator.kt:45-49`, `:111-117`). No Nexus window exists. |
| `SURFACE_OVERLAY` | `SurfaceController.active != null` and `activeDisplayedViaActivity == false`; `SurfaceOverlayRenderer.root != null`. |
| `SURFACE_ACTIVITY` | `active != null` and `activeDisplayedViaActivity == true`; a `SurfaceActivity` task exists. |
| `CAMERA` | `CameraActivity` resumed in `:camera`. Main process tracks it only via `GlassesHub.isCameraSessionActive` and the overlay-visibility broadcast. |
| `MAIN_ONBOARDING` / `MAIN_LIST` | `MainActivity` showing setup screens, or its own list launcher once setup is COMPLETE (`G/MainActivity.kt:296-310`). |
| `NATIVE_APP` | A foreign package in front (launched by `/core/native-apps`, by the ROM, or by the assist button). Nexus keeps no state for it. |

Ambient layers are independent overlays, each with its own single-slot or multi-slot state machine:
notice (`NoticeStateMachine`, 1 slot), pin (`PinStateMachine`, 1 slot), activity
(`ActivityStateMachine`, max 2), status badge, pointer.

### 2.2 How each way of opening the launcher behaves

1. **Triple tap on the temple touchpad.** `TripleTapDetector` counts three `KEYCODE_NOTIFICATION`
   (83) DOWN events with `repeatCount == 0` within 600 ms (`G/TouchpadGestureDetectors.kt:19-51`,
   `:69-71`). `TRIGGER` calls `LauncherOverlayRenderer.show(this)` **only if not already shown**, and
   consumes the key (`G/RokidBusAccessibilityService.kt:194-200`). After a trigger, BACK and ENTER
   classifications within 800 ms are consumed (`CONSUME`), because the firmware sends a
   classification after the contacts (`G/TouchpadGestureDetectors.kt:49`, `:60-62`;
   `G/RokidBusAccessibilityService.kt:201`). The trigger is skipped entirely (detector fed
   `PASS`) while an editable card is active (`:184-188`). It has no guards for camera, notice,
   onboarding, or setup.
2. **Ring triple tap.** Not implemented in Nexus: R08 events never reach `TripleTapDetector`
   (`G/RokidBusAccessibilityService.kt:155-157`). The 1.0.41 changelog says the ring's triple tap
   opens the launcher and that Nexus exposes an `OPEN_LAUNCHER` endpoint that needs the R08 Access
   Bridge's "Nexus launcher" ring action, so the ring gesture is recognised outside Nexus and arrives as
   the broadcast in item 3 (`CHANGELOG.md:1134-1136`, `tools/emulator/ring.sh:11`).
3. **`OPEN_LAUNCHER` broadcast.** Toggle (`G/OpenLauncherReceiver.kt:9-16`).
4. **Launcher app icon** (`MainActivity`). At `HEAD` this does **not** open the overlay launcher:
   `MainActivity` renders its own list-only launcher inside the activity when setup is complete
   (`G/MainActivity.kt:302-308`, `:446-470`). Grid mode is ignored on this path. (Stash changes this,
   section 6.4.)
5. **Return from a plugin surface.** `SurfaceController.hideLocalOnMain` calls
   `LauncherOverlayRenderer.show()` after removing the surface window when
   `launcherReturnCoordinator.consumeReturnOnHide(surfaceId)` says this surface was opened from the
   launcher (`G/SurfaceController.kt:719`, `:729-731`; `G/LauncherReturnCoordinator.kt:39-45`).
6. There is no path that opens the launcher when the phone or bus is absent other than 1-4. `show()`
   also calls `GlassesHub.start(...)` and clears the pending launcher-open (`:85-86`).

Every `show()` after a `hide()` builds a brand-new `LauncherOverlayRoot` and content view
(`root` is nulled in `hide()`, `G/LauncherOverlayRenderer.kt:149`). `selectedIndex` is a field of the
singleton and survives close/open (`:52`); it is only clamped, never reset, when entries change
(`:108`).

### 2.3 Launcher content mode selection (`HudModeStore`)

- Storage: `SharedPreferences("hud_mode")`, boolean `grid_mode_enabled`, default
  `HudModeContract.DEFAULT_MODE == MODE_GRID`, i.e. **list** (`G/HudModeStore.kt:14-29`;
  `S/HudModeContract.kt`, `DEFAULT_MODE`).
- Written by `/glasses/hud-mode/config` from the phone (`BusPaths.HUD_MODE_CONFIG`): parsed with
  `HudModeContract.gridModeFromConfig`; invalid payloads are ignored and the stored value stands
  (`G/GlassesHub.kt:347-358`). The phone re-pushes on every capabilities announce
  (`S/HudModeContract.kt` KDoc).
- Applied only inside `show()`: `currentRoot.setMode(HudModeStore.isGridModeEnabled(...))`
  (`G/LauncherOverlayRenderer.kt:102-104`). A flip while the launcher is open does nothing until it
  is next shown. `setMode` swaps the content view (`removeAllViews`, new `GridLauncherView` or
  `LauncherMenuView`) only if the mode changed (`:259-267`).
- Side effect at the hub: grid mode starts `TileController`, list mode stops it and clears its rate
  limiter, so in list mode `/tile/publish` envelopes are consumed and dropped without touching
  `TileCache` (`G/GlassesHub.kt:223`, `:231-233`, `:355`; `G/TileController.kt:24-43`).
- Tile layout order/size arrive on `/glasses/tile-layout/config`; entries are re-ordered by
  `TileLayoutStore.applyOrder` for **both** modes, and a layout push notifies launcher observers
  (`G/GlassesHub.kt:359-369`, `:1388-1392`; `G/TileLayoutStore.kt`).

### 2.4 Hand-off to a plugin, by path

Selecting an entry runs `LauncherOverlayRenderer.openSelected` then `completeOpen(entry)`
(`G/LauncherOverlayRenderer.kt:222-243`):

1. In grid mode, `GridLauncherView.beginOpenTransition` first tweens a ghost from the tile rect to
   the safe-area rect over `DURATION_STRUCTURAL_MS` (320 ms; skipped when animation is disabled, the
   platform animator scale is 0, or the tile has zero size) and calls `completeOpen` on settle
   (`:222-230`; `G/TileExpansionAnimator.kt:44-56`; `G/GridLauncherView.kt:161-188`).
2. `GlassesHub.openLauncherEntry(id)`:
   - `id == "camera"`: `startActivity(CameraActivity, NEW_TASK)`; result
     `launcherOpen=true` or `code=ACTIVITY_START_FAILED` (`G/GlassesHub.kt:587-595`).
   - Otherwise sends `/launcher/open {pluginId}` to the phone (`:596-606`). `launcherOpen=true`
     means "envelope sent", not "plugin will show a surface".
3. On `launcherOpen=true` only: `lastOpenedEntryId = id`;
   `launcherReturnCoordinator.recordLauncherOpen(id)`; if the entry opens a surface (not camera)
   `RingFocusBroadcastCoordinator.beginSurfaceHandoff`; then `hide()` **unconditionally**
   (`:235-242`).
4. On failure (`launcherOpen=false`) the launcher stays open.

What happens next depends on how the plugin's surface is displayed
(`G/SurfaceController.kt:609-655`, `InkCardPresentation.kt:63-76`):

| Surface | Display path chosen | Launcher/MainActivity handling |
|---|---|---|
| Ink (`kind == "ink"`) | Always OVERLAY (`surfaceDisplayPath`), no fallback to ACTIVITY: if the overlay cannot be shown, `onInkRendererError` closes the session | none needed; launcher already hidden |
| Editable card (`kind == card && editable != null`) | Always ACTIVITY (real window focus needed for the IME) | `stepLauncherAside()` only when `isHandoff` (a different `surfaceId` than the previous active): `LauncherOverlayRenderer.hide()` + `MainActivity.finishIfStale()` (`:634-638`) |
| Everything else | Persisted preference `surface_renderer/display_path`, default OVERLAY (`:84-91`); falls back to ACTIVITY if `SurfaceOverlayRenderer.show` fails (`:645-653`) | as above when it ends up on ACTIVITY |
| Camera | Not a surface; `CameraActivity` in `:camera` | launcher hidden by `completeOpen` (`launcherEntryOpensSurface == false`, `G/GlassesHub.kt:609`) |
| Native app | Not launched from the launcher; only via `/core/native-apps/request launch_request` (`G/NativeAppsController.kt:102-111`) | The launcher and any surface are **not** dismissed by the launch |

Display path persistence: `setDisplayPath` is called only by `showDemoCard` (debug probe), which
persists its choice (`G/SurfaceController.kt:93-98`, `:218-220`). No production code sets it.

Activity owner shortcut: an idle activity chip/panel tapped with no action calls
`GlassesHub.openLauncherEntry(ownerPluginId)`, i.e. `/launcher/open`, bypassing the launcher overlay
and `launcherReturnCoordinator` (`G/ActivityController.kt:741-759`).

### 2.5 How a surface returns to the launcher

`LauncherReturnCoordinator` (`G/LauncherReturnCoordinator.kt:1-48`) holds two strings:

- `pendingPluginId`: set by `recordLauncherOpen(id)` (also clears `launcherReturnSurfaceId`);
  cleared by `LauncherOverlayRenderer.show(...)` (`:85`) or by a match. **No timeout.**
- `launcherReturnSurfaceId`: set by `onSurfaceShown(surfaceId)` when a `/surface/show` (not update,
  and only via `launcherShow = envelope.path == SURFACE_SHOW`, `G/SurfaceController.kt:180`) arrives
  with `surfaceId == pending || surfaceId.startsWith("$pending:")`; consumed by
  `consumeReturnOnHide(activeSurfaceId)` on any hide of that same surface id.

`SurfaceController.hideLocalOnMain` is the single teardown for BACK, remote `/surface/hide`, the
1.5 s BACK failsafe, ink renderer errors and phone link loss (ink only). It removes the surface
window first, then, if a return was claimed, calls `LauncherOverlayRenderer.show()`, then
`RingFocusBroadcastCoordinator.setSurfaceInactive()` (`G/SurfaceController.kt:698-732`). Returning
from a plugin is therefore "the surface whose show matched a pending launcher open was hidden", not
"the wearer pressed BACK". If the plugin hides its own surface for its own reasons, the launcher
appears as well.

### 2.6 State/transition table

`Ev` = event. Rows are ordered by area. "Ring focus" = `RingFocusBroadcastCoordinator`, whose output
`NEXUS_RING_FOCUS` is sent to `com.anezium.r08accessbridge` whenever the union of
{launcher shown, surface active, handoff pending, notice owns ring} changes
(`G/RingFocusCoordinator.kt:65-70`, `:74-77`, `:142-148`).

| # | From | Event | Guard | To | Side effects | Cite |
|---|---|---|---|---|---|---|
| T1 | any except editable-card | Triple tap (3 x key 83 DOWN within 600 ms) | `!LauncherOverlayRenderer.isShown()`; not an editable card | `LAUNCHER_*` | Window added, `reassert()`, mode picked, observer subscribed, `requestFocus`; ring focus true; `ActivityController.onLauncherVisibilityChanged()`; the trigger key consumed; BACK/ENTER classifications for 800 ms consumed | `RBAS:194-201`; `LOR:83-131` |
| T2 | any | Triple tap | launcher already shown | unchanged | Trigger key consumed, nothing else | `RBAS:196-199` |
| T3 | any | `OPEN_LAUNCHER` broadcast | launcher shown | prior state under launcher | `hide()` | `G/OpenLauncherReceiver.kt:9-11` |
| T4 | any | `OPEN_LAUNCHER` broadcast | not shown, service connected | `LAUNCHER_*` | as T1 | `:12-13` |
| T5 | any | `OPEN_LAUNCHER` broadcast | not shown, service down | unchanged | log only | `:14-15` |
| T6 | `LAUNCHER_*` | BACK (temple) | key reaches `LauncherOverlayRenderer.handleKeyEvent` | prior state under launcher | `hide()`; UP later swallowed via `consumedDownKeys` | `LOR:198-201`; `RBAS:220-222` |
| T7 | `LAUNCHER_*` | Ring double tap (2 x key 85 within 350 ms, resolved at +351 ms) | launcher shown and no notice claims the ring | prior state | `hide()` | `LOR:206-214`; `G/RingTapPolicy.kt:16-38` |
| T8 | `LAUNCHER_*` | ENTER/DPAD_CENTER or ring single tap | entries non-empty | `HANDOFF` (or `CAMERA`) | grid: 320 ms open tween first; `/launcher/open` sent; `recordLauncherOpen`; ring handoff begins (10 s); `hide()` | `LOR:222-243` |
| T9 | `LAUNCHER_*` | Same as T8 | `entries` empty or index out of range | unchanged | nothing | `LOR:222-224` |
| T10 | `LAUNCHER_*` | Same as T8 | `openLauncherEntry` returns `launcherOpen=false` | unchanged | launcher stays; error logged | `LOR:233-241` |
| T11 | `LAUNCHER_*` | Same as T8 for the `camera` entry | `startActivity` ok | `CAMERA` | no ring handoff; `hide()`; pending = "camera" (never matched) | `LOR:237-241`; `G/GlassesHub.kt:587-595`, `:609` |
| T12 | `HANDOFF` | `/surface/show` for the pending plugin | `launcherShow` and `surfaceId == pending` or `startsWith("$pending:")` | `SURFACE_OVERLAY` or `SURFACE_ACTIVITY` | `launcherReturnSurfaceId = surfaceId`; `setSurfaceActive(active, completesHandoff = true)`: handoff timer cancelled | `SC:452-453`, `:481-485`; `LRC:24-36` |
| T13 | `HANDOFF` | `/surface/show` for another plugin | no id match | that surface | no return claim; `completesHandoff = false`, so the 10 s timer keeps running until surface-active makes focus true anyway | `LRC:24-36` |
| T14 | `HANDOFF` | 10 s elapse, no surface | none | `IDLE` | ring focus released (`expireSurfaceHandoff`) if nothing else owns it; `pendingPluginId` still set | `RFC:45-49`, `:111-117` |
| T15 | `SURFACE_*` | `/surface/update` | same `surfaceId` | same | re-render in place; no handoff work | `SC:478-487` |
| T16 | `SURFACE_*` | `/surface/show` different surface | replaces | `SURFACE_*` | previous ordering deactivated; ink `closed(replaced)` sent if ink; ring input reset; `isHandoff` true | `SC:454-487`, `:734-754` |
| T17 | `SURFACE_*` | BACK (temple) or ring double tap | `handlesBack == false` | prior state under surface, or `LAUNCHER_*` per T20 | BACK forwarded as `/surface/input`; ink `closed(user)`; editable cancel reported; `hideLocal(WEARER_DISMISSED)` | `SC:276-286`, `:965-975` |
| T18 | `SURFACE_*` | BACK (temple) or ring double tap | `handlesBack == true` | unchanged until plugin hides | BACK forwarded; 1.5 s failsafe armed; when it fires and the surface is still active, hides locally | `SC:965-968`, `:986-1005` |
| T19 | `SURFACE_*` | `/surface/hide` | ordering accepts it (`seq` newer) and `surfaceId` is active | prior state | ink `closed(plugin)`; `hideLocalOnMain(reason)` (reason `WEARER_DISMISSED` if a failsafe was armed, else `SESSION_CLOSED`) | `SC:667-696` |
| T20 | `SURFACE_*` | any hide, via `hideLocalOnMain` | `consumeReturnOnHide(surfaceId)` true | `LAUNCHER_*` | surface window removed first, then `LauncherOverlayRenderer.show()`; ring focus updated | `SC:719`, `:729-731` |
| T21 | `SURFACE_*` | any hide | no return claim | prior state or `IDLE` | `SurfaceOverlayRenderer.hide()`; ring focus off if nothing else owns it | `SC:729-731` |
| T22 | `SURFACE_ACTIVITY` | hide | surface was displayed via ACTIVITY | prior state | `MainActivity.finishIfStale()` first; `SurfaceActivity` observer sees null and `finishAndRemoveTask()` | `SC:710`; `G/SurfaceActivity.kt:25-38` |
| T23 | `SURFACE_ACTIVITY` | ink surface replaces it | ink forces overlay | `SURFACE_OVERLAY` | `SurfaceActivity` finishes itself (`render(null)`, `finishAndRemoveTask`) | `G/SurfaceActivity.kt:25-30`; `SC:616-623` |
| T24 | `SURFACE_OVERLAY` | Overlay window cannot be added | `SurfaceOverlayRenderer.show` false, non-ink | `SURFACE_ACTIVITY` | `activeDisplayedViaActivity = true`; `stepLauncherAside`; `showActivity` | `SC:645-653` |
| T25 | non-ink `SURFACE_*` | phone link lost | | unchanged | **non-ink surfaces are not closed**; only ink is closed with `link_lost` | `SC:335-346` |
| T26 | any | `MainActivity` created/resumed | setup COMPLETE | `MAIN_LIST` | own list launcher in the activity (no grid, no ring, no return coordinator); after a 1.6 s "setup done" confirmation if it just completed | `MA:296-310`, `:385-405` |
| T27 | any | `MainActivity` created/resumed | setup incomplete | `MAIN_ONBOARDING` | onboarding view; DPAD swallowed; ENTER performs the action; receiver for `ACTION_CHANGED` registered while started | `MA:296-382`, `:128-150` |
| T28 | `MAIN_LIST` | ENTER/DPAD_CENTER | entries non-empty | plugin surface later | `GlassesHub.openLauncherEntry` only: **no** `recordLauncherOpen`, **no** ring handoff, **no** hide | `MA:527-531` |
| T29 | `NATIVE_APP` request | `/core/native-apps/request launch_request` | package launchable, not the hub | `NATIVE_APP` | `startActivity(NEW_TASK)`; Nexus overlays are left as they are | `G/NativeAppsController.kt:102-111` |
| T30 | any | `RBAS.onServiceConnected` | `SurfaceController.active != null` and path OVERLAY | `SURFACE_OVERLAY` | overlay re-shown; launcher not restored | `G/SurfaceOverlayRenderer.kt:18-25` |
| T31 | any | `RBAS.onDestroy` | | `IDLE` for overlays | all overlay windows removed; `SurfaceController.active` retained | `RBAS:277-309` |
| T32 | any | Camera overlay attach/detach (`:camera`) | | unchanged | activity presentation becomes HIDDEN; notice/pin flags set only in `:camera` (see F-16) | `G/CameraOverlayView.kt:110-120`; `G/ActivityController.kt:479-485` |
| T33 | `SURFACE_*` ink | first frame drawn | initial ink show | unchanged | gate released, `ready` sent, assistant episode renewed; 500 ms timeout forces release | `SC:383-412`; `G/SurfaceHudView.kt:657-664`, `:786-803` |

`RBAS` = `G/RokidBusAccessibilityService.kt`, `LOR` = `G/LauncherOverlayRenderer.kt`, `SC` =
`G/SurfaceController.kt`, `RFC` = `G/RingFocusCoordinator.kt`, `LRC` =
`G/LauncherReturnCoordinator.kt`, `MA` = `G/MainActivity.kt` in the table above.

### 2.7 Timeouts that shape transitions

| Timeout | Value | Owner | What it does |
|---|---|---|---|
| Triple-tap window | 600 ms (+1 ms for the expiry runnable) | `TripleTapDetector`, `RBAS.tapExpiry` | window for 3 contacts; on expiry unmatched contacts (max 2) are flushed to surface or activity |
| Post-trigger classification suppression | 800 ms | `TripleTapDetector` | swallow BACK/ENTER after a trigger |
| Ring tap window | 350 ms (+1) | `RingTapPolicy`, one instance each in launcher, surface, notice, activity | single vs double tap |
| D-pad duplicate window | 150 ms | `DpadPairDedupe`, separate instances in launcher, surface, notice router, activity, `MainActivity` | drop the second key of a duplicated swipe |
| Ring handoff | 10 000 ms | `RingFocusBroadcastCoordinator` | release ring focus if the plugin never shows |
| BACK failsafe | 1 500 ms | `SurfaceController` | hide locally if a `handlesBack` plugin does not hide |
| Ink first frame | 500 ms | `SurfaceHudView` | force-release the presentation gate |
| Setup confirmation | 1 600 ms | `MainActivity` | show "done" before the list |
| Notice TTL | default 8 s, clamp 2 to 45 s, hard cap 90 s from show; 30 s inactivity clock once paging | `NoticeStateMachine`, `NoticeSurfaceContract` | notice expiry |
| Activity collapse / flare interval / urgent interval | 10 s / 10 s / 60 s | `ActivityStateMachine`, policy | chip/panel and flare pacing |
| Pointer idle | 8 s | `RemotePointerController` | hide cursor |
| Status badge settle | 150 ms | `StatusBadgeOverlayRenderer` | debounce accessibility events |

All from the files cited in section 4; full list in appendix A.

## 3. Input routing

There are two separate pipelines, chosen by device name.

### 3.1 Entry point (`RBAS.onKeyEvent`, `G/RokidBusAccessibilityService.kt:153-224`)

Every key reaches the service first because of `FLAG_REQUEST_FILTER_KEY_EVENTS`. Returning `true`
consumes it (the focused window and the system never see it); returning `false` lets it through.

1. `displayStandbyWatchdog.noteKeyEvent(event)` for every key (`:154`).
2. If the input device name, upper-cased, contains `"R08"`, go to the ring pipeline (3.2) and return
   its result (`:155-157`). Anything else is "generic" (3.3), including temple touchpad, bonded
   keyboards, and `adb shell input keyevent` (no R08 name).
3. `KEYCODE_PROG_BLUE` (186) is always passed (`:158`; also passed by the launcher at
   `G/LauncherOverlayRenderer.kt:174`).

### 3.2 R08 ring pipeline (`handleRingKeyEvent`, `:226-264`)

Ring keycodes are `85` (tap, `MEDIA_PLAY_PAUSE`), `87` (forward, `MEDIA_NEXT`), `88` (backward,
`MEDIA_PREVIOUS`) (`G/RingSurfaceInputPolicy.kt:54-56`). Precedence, in order:

1. An UP whose DOWN was consumed (`consumedDownKeys`, a `Set<Int>` keyed by keycode only) is
   consumed (`:229-231`).
2. Compute `launcherShown`, `surfaceActive = SurfaceController.activeSurface() != null` (any surface,
   editable or not), `noticeOwnsRing = NoticeController.ownsRingInput()`,
   `noticeRingClaims = NoticeController.claimsRingKey(key)`,
   `activityClaims = ActivityController.claimsRingKey(key)` (`:232-236`).
3. If none of `launcherShown`, `surfaceActive`, `noticeOwnsRing`, `activityClaims` is true: **return
   false**, the key passes to the system and the bridge (`:237`).
4. Any non-DOWN event that reaches here is consumed (`:239`).
5. On `repeatCount == 0` exactly one owner acts, first match wins (`:240-261`):
   1. `noticeRingClaims` -> `NoticeController.handleRingKey` (deliberately before the launcher, because
      the band draws over it).
   2. `noticeOwnsRing` -> no-op (swallows every unclaimed ring key so hidden native UI is not driven).
   3. `launcherShown` -> `LauncherOverlayRenderer.handleRingKey`.
   4. `surfaceActive` -> `SurfaceController.handleRingKey`.
   5. `activityClaims` -> `ActivityController.handleRingKey`.
6. Repeats are consumed silently; the keycode is added to `consumedDownKeys`; return `true` (`:262-263`).

Owner semantics for ring keys:

| Owner | Forward (87) / Backward (88) | Tap (85) |
|---|---|---|
| Notice (`NoticeController.handleRingKey`, only when the key is claimed) | `handleDirection(+1/-1)`: pages if `isPaged`, else moves the selected action (wraps) | `RingSurfaceInputPolicy` resolves after 351 ms: 1 tap answers (ENTER), 2 taps dismiss (`USER`), 3+ ignored (`G/NoticeController.kt:786-844`). A tap is claimed only if the notice `expectsInput` |
| Launcher | `moveSelection(+1/-1)` with wraparound; re-renders | 1 tap opens selected, 2 taps hide, 3+ ignored, resolved at 351 ms (`G/LauncherOverlayRenderer.kt:158-170`, `:206-214`) |
| Surface (non-reader) | Synthesizes DPAD_RIGHT/LEFT DOWN then UP into `SurfaceController.handleKeyEvent` (so ink, the 150 ms dedupe and the forwarding rules apply) | 1 tap synthesizes ENTER DOWN+UP; 2 taps forward BACK to the plugin and run `handleBackDown`; 3+ ignored (`G/SurfaceController.kt:288-309`, `:911-943`, `G/RingSurfaceInputPolicy.kt:22-43`) |
| Surface (reader) | Scrolls a viewport page (`requestReaderScroll`), gated by a 150 ms dedupe | same as above (ENTER forwarded to the plugin) |
| Activity (idle only) | `moveSelection` if the primary activity has actions (claimed only then) | 1 tap fires the captured action or opens the owner plugin; 2 taps run `GLOBAL_ACTION_BACK`; the target is captured at first tap and re-validated (`G/ActivityController.kt:508-520`, `:548-564`, `:784-806`) |

Activity ring input applies only when idle: `claimsInput()` requires an activity presenting, camera
overlay off, no active surface, no visible notice, launcher not shown (`:501-506`).

### 3.3 Generic pipeline (touchpad, keyboards)

After steps 1 and 3 of 3.1:

1. `editableSurfaceActive = SurfaceController.hasFocusedEditableSurface()` (an active card with an
   `editable` field, `G/SurfaceController.kt:78-79`). When true, key logging is skipped (`:174-176`)
   and the triple-tap detector is not consulted (`:184-188`).
2. **ACTION_UP**: `NoticeKeyDispatcher.handleKeyEvent(event)` (consumes the UP of a press the notice
   consumed) or `consumedDownKeys.remove(keyCode)`; either consumes (`:178-182`).
3. Triple-tap decision (`:184-188`). A DOWN of any key except 83 cancels `tapExpiry` (`:189-191`).
   - `TRIGGER`: cancel `tapExpiry`, show launcher if not shown, consume (`:194-200`).
   - `CONSUME`: consume (`:201`).
   - `PASS`: on key 83 DOWN `repeatCount == 0` and not editable, (re)arm `tapExpiry` for 601 ms
     (`:203-210`), then the owner chain, first `true` wins (`:211-217`):
     1. `NoticeKeyDispatcher` (skipped for UP)
     2. `LauncherOverlayRenderer.handleKeyEvent`
     3. `SurfaceController.handleKeyEvent`
     4. `ActivityController.handleKeyEvent`
     5. otherwise `false` (passes to the focused window and the system)
4. On DOWN, record keycode in `consumedDownKeys` if handled, else remove it (`:220-222`).

`tapExpiry` -> `flushPendingTaps`: after 601 ms with no further contact, up to 2 unmatched contacts
are replayed. If **any** surface is active each is sent as `/surface/input {keyCode: 83, action: 0}`;
otherwise `ActivityController.handlePendingTempleTap` is called per contact. There is deliberately no
notice branch (a band must not be answered by an unclassified contact) (`:311-333`). The check is
`SurfaceController.activeSurface() != null` regardless of whether the launcher is open on top.

Owner rules in generic mode:

| Owner | Consumes | Passes through | Cite |
|---|---|---|---|
| **Notice router** (`NoticeKeyInputRouter`) | BACK DOWN always when any notice is visible (`dismissFromBack`, never forwarded to the plugin); direction keys (DPAD U/D/L/R) when `claimsDirection` (via a 150 ms dedupe, RIGHT/DOWN = +1, LEFT/UP = -1); ENTER/DPAD_CENTER when `expectsInput` (answers once); when the notice is a backdrop, any unclaimed ENTER/DPAD_* DOWN. A consumed press's repeats and UP are consumed by `(deviceId, keyCode, downTime)` | Everything else, and **everything except BACK** while an editable card is active | `G/NoticeKeyDispatcher.kt:38-84` (router), `G/NoticeController.kt:700-774` |
| **Launcher** (`root != null`) | **Everything**: non-DOWN and repeats consumed; directions moved via a 150 ms dedupe (RIGHT/DOWN forward, LEFT/UP backward); ENTER/DPAD_CENTER open; BACK hides; any other key consumed as a no-op | Only keycode 186 | `G/LauncherOverlayRenderer.kt:172-204` |
| **Surface, editable card** | BACK DOWN `repeatCount == 0` -> `handleBackDown` (no `/surface/input` forwarded) | All other keys reach the focused `EditText` | `G/SurfaceController.kt:255-269` |
| **Surface, reader** | DPAD directions and MEDIA_NEXT/PREVIOUS: scroll, never forwarded (150 ms dedupe on DPAD). ENTER, DPAD_CENTER, BACK forwarded (DOWN and UP) | Others | `G/SurfaceController.kt:311-329`, `:1072-1081` |
| **Surface, other kinds** | D-pad duplicates suppressed (150 ms, UP of a suppressed DOWN also suppressed); ink gets the first chance (`InkHudView.handleInkKeyEvent`: directions move the action selection or scroll, ENTER/CENTER emit the selected action if it is in the viewport); keys in `FORWARDED_KEYS` (BACK, ENTER, DPAD_CENTER, DPAD_*, SPACE, MEDIA_PLAY_PAUSE, NEXT, PREVIOUS) are forwarded to the plugin as `/surface/input` on DOWN and UP and consumed; BACK DOWN additionally runs `handleBackDown` | Keys outside that set (83, 186, volume, etc.) | `G/SurfaceController.kt:270-286`, `:899-909`, `:1051-1063`; `G/InkHudView.kt:182-209` |
| **Activity** (idle only) | key 83 DOWN and UP; directions if the primary has actions; ENTER/CENTER fire or open the owner | BACK and everything else | `G/ActivityController.kt:526-543` |

### 3.4 Keys delivered to windows, not to the service

If the service returns `false`, the key goes to the focused window: the launcher root, surface root,
`SurfaceActivity`, `MainActivity`, or `CameraActivity`. Each of the first four repeats the same
chain in `dispatchKeyEvent`: `NoticeKeyDispatcher`, then its own handler
(`G/LauncherOverlayRenderer.kt:282-286`, `G/SurfaceOverlayRenderer.kt:93-97`,
`G/SurfaceActivity.kt:47-51`, `G/MainActivity.kt:128-176`).

- `MainActivity` while onboarding: DPAD keys swallowed (dedupe applied first), ENTER/CENTER run
  `performOnboardingAction`, others go to `super` (BACK finishes) (`:134-150`). After setup:
  swipes move its own selection, ENTER/CENTER open the selected entry, DPAD swallowed, everything
  else (including BACK) to `super` (`:151-175`).
- `CameraActivity` (`:camera`): `CameraInputRouter` maps ENTER/DPAD_CENTER to freeze toggle,
  DPAD_RIGHT/DOWN/183 to zoom in, DPAD_LEFT/UP/184 to zoom out, each with a 400 ms debounce; UP of
  those keys consumed; BACK not handled, so it finishes the activity (`G/CameraActivity.kt:110-122`,
  `G/CameraInputRouter.kt:112-146`). The main-process service still sees these keys first: with no
  launcher/surface/notice, ring keys pass, generic keys fall through the chain to `false`; but the
  triple-tap trigger is not camera-aware.
- Editable card on `SurfaceActivity`: the `EditText` is single line, `IME_ACTION_SEND`, max length
  `EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH`; Enter (hardware, `OnKeyListener`) or the IME send
  action calls `forwardSurfaceText(text, cancelled = false)`; BACK while `handlesBack == false`
  forwards `cancelled = true` and hides (`G/SurfaceHudView.kt:105-154`; `G/SurfaceController.kt:965-975`).
  The hub's own IME forwards editing from the phone (`G/NexusRemoteInputMethodService.kt:36-59`); the
  `EditText` sets `privateImeOptions` so the phone may raise its keyboard for it
  (`G/SurfaceHudView.kt:111-113`).

### 3.5 Firmware facts the code depends on

- Touchpad tap: contact key 83 first, then (after up to ~500 ms classification, comment at
  `G/RokidBusAccessibilityService.kt:314-319`) ENTER or a swipe pair. Swipes are duplicated 20-80 ms
  apart (`G/TouchpadGestureDetectors.kt:116-120`). Two taps 188 ms apart fired a notice action twice
  before the `answered` flag (`G/NoticeController.kt:39-43`).
- ROM launcher key-up handling can start phone playback or sleep the display when it receives a key
  Nexus did not consume; that is why `consumedDownKeys` swallows the UP of a consumed DOWN
  (`G/RokidBusAccessibilityService.kt:28-32`).

## 4. Layer contracts

For each layer: **data** (source model), **update triggers**, **lifecycle**, **key facts**.

### 4.1 Launcher (list and grid)

**Data.** `GlassesHub.observeLauncher` delivers `List<LauncherEntry(id, displayName, iconKey)>`
immediately on subscribe and on every change (`G/GlassesHub.kt:579-583`, `:1404-1409`). The list is:

1. A synthesized `camera` entry first (`iconKey = "lens"`, name = the phone's `cameraConsumerName`),
   present only while the phone advertises `CAMERA_CONSUMER_READY` and a name
   (`:1388-1402`; cleared on link loss `:1411-1415`).
2. Then the `/launcher/list` payload `plugins[]` (`id`, `displayName`, `iconKey`; blank ids skipped)
   re-ordered by `TileLayoutStore.applyOrder` (stored order first, unplaced appended) (`:1196-1218`,
   `G/TileLayoutStore.kt:54-61`).
Icons resolve through `GlassesHub.launcherDrawable`: built-in key, then per-plugin glyph cache
(`/launcher/glyphs`), then a default (`:611-621`, `:1220-1251`).

**Update triggers.** `LauncherOverlayRenderer` subscribes on `show()` and unsubscribes on `hide()`
(`G/LauncherOverlayRenderer.kt:105-111`, `:139-140`). The callback runs on whatever thread notifies
(F-11). Each selection move calls `root.render(...)` (`:216-220`). The rendering content view
receives `(entries, selectedIndex)` only and never touches the bus
(`G/LauncherOverlayRenderer.kt:28-37`).

**Selection model.** One flat index over the ordered entries with wraparound; both list and grid move
linearly, D-pad DOWN/RIGHT = +1, UP/LEFT = -1, ring 87/88 = +1/-1. The grid has no 2-D navigation
(`:216-220`, `:179-189`).

**List mode (`LauncherMenuView`, `G/LauncherOverlayRenderer.kt:289-427`).** Vertical `LinearLayout`,
padding `18dp/(16+inset)dp/18dp/12dp`, black background. Header lines: `"ROKID NEXUS"` (12sp,
phosphor, bold), `"Launcher"` (24sp bold), a count line `"i/n"` (or `"Waiting for phone"` when empty),
`"PLUGINS"` (10.5sp dim), then a `ScrollView` of rows. Each row: min height 52dp, 12dp horizontal
padding, 24dp icon plus label (18sp mono, 2 lines with ellipsis; selected = phosphor, bold, 2dp
phosphor outline; unselected = 1dp hairline outline), 8dp gaps. Empty state text `"No phone plugins
synced"`. `render` calls `listView.removeAllViews()` and rebuilds every row; the selected row is
scrolled into view with `requestRectangleOnScreen` after layout (`:338-363`). The top inset applies
by re-padding (`:365-368`).

**Grid mode (`GridLauncherView` / `TileGridContainer`, `G/GridLauncherView.kt`).** A
`HudFrameLayout` (safe padding 16/12dp + top inset, `B/HudFrameLayout.kt`) holding a `ScrollView`
and an empty-state `TextView` ("No phone plugins synced"); visibility of the two toggles by
emptiness (`:56-65`). `TileGridContainer.render` (`:123-157`):

1. `removeAllViews()` on every render.
2. `TileGridPacker.pack(entries.map { id to TileLayoutStore.sizeFor(id) }, columns = 4)`: row-major
   first-fit, `null`/unknown size = `SMALL` (1x1); sizes `SMALL 1x1`, `WIDE 2x1`, `TALL 1x2`,
   `LARGE 2x2` (`S/tile/TileGridPacker.kt:12-58`, `S/tile/TileSize.kt`).
3. Unit `= 96dp` (`TILE_UNIT_DP`), gap `= 8dp` (`SPACE_2`); tile `= cols*unit + (cols-1)*gap` by
   the same for rows; absolute `leftMargin/topMargin = col*(unit+gap)` (`:130-152`). At 4 columns
   this is 4x96 + 3x8 = 408 dp versus a 320 dp wide display (F-8).
4. Per entry: if `TileController.isActive` and `TileCache.get(id)` returns a snapshot, a
   `LiveTileView(size)` is bound with `stale = TileCache.isStale(...)`; otherwise a
   `FallbackTileView(size)` bound to the entry.
5. Only `FallbackTileView` receives `setSelected(selected, focused)`; `LiveTileView` has no selection
   or focus rendering at all (`:143`).
6. The selected tile is scrolled into view with `requestRectangleOnScreen` after layout (`:153-155`).

`FallbackTileView` (`G/FallbackTileView.kt:27-93`): header row with a 16dp icon and the plugin name
upper-cased, 11sp label typeface, `letterSpacing 0.06em`, one line, ellipsized; padding 8dp;
`setSelected`: fill `SURFACE_SELECTED` if selected else transparent; stroke `FOCUS` (2dp) when
focused, else `TEXT_PRIMARY` if selected, else `LINE` (1dp); corner 4dp.

`LiveTileView` (`G/LiveTileView.kt:28-186`): loader ("..." or percentage) until `bind`; a numeric
title renders as a data readout plus `unit`, otherwise as a two-line title; subtitle shown only for
non-`SMALL`; badge line; up to 4 rows for `LARGE` only; tone maps to stroke
(`OK` primary 1dp, `INFO` secondary 1dp, `WARN` primary dashed, `CRITICAL` 2dp `CRITICAL` plus
`CriticalBlink`, `OFF` secondary); a stale snapshot draws at alpha `0x60`.

`TileCache` (`G/TileCache.kt`): `SharedPreferences("tile_cache")`, `tile.<pluginId>` -> JSON
`{snapshot, receivedAtElapsedRealtime}`; stale after 10 min; a snapshot from before a reboot
(`receivedAt > now`) is treated as exactly stale. `TileController.handleTileEnvelope` writes to it
only while active, after a per-plugin token bucket (capacity 5, refill 60 s) (`G/TileController.kt:35-43`,
`G/TileRateLimiter.kt`). **Nothing notifies an open grid of a cache write** (F-10).

**Grid open/close motion.** `beginOpenTransition`: ghost `View` with the tile's background, added to
the `GridLauncherView`, tweens tile rect -> safe-area rect over 320 ms; siblings are replaced by blurred
bitmap overlays at alpha 0.35 (`G/GridLauncherView.kt:161-188`, `:219-243`,
`G/TileExpansionAnimator.kt:44-72`, `G/DownscaleBlur.kt`). `beginCloseTransition`, used only from
`show()` on return, tweens back (`G/LauncherOverlayRenderer.kt:115-125`). Reduced motion =
`animator_duration_scale == 0` (`G/ReducedMotion.kt`).

### 4.2 Plugin surface host: `SurfaceHudView` and children

`SurfaceHudView` (1376 lines) is the single view for every non-ink and ink surface kind. One
instance lives in the overlay root, another in `SurfaceActivity`; both are driven by
`SurfaceController` (`observe`/direct `render`) and both re-render from scratch per update.

**Common chrome.** Status row (phone battery `"HP nn%"`, date `EEE d MMM`, glasses battery), title,
subtitle, content, footer; padding 18/16/18/12dp full-bleed with black background, or transparent for
Ink cards (`surfaceHostChrome`, `G/InkCardPresentation.kt:43-61`). The status row is refreshed by a
30 s ticker plus `PhoneBatteryController` changes (`G/SurfaceHudView.kt:58-85`, `:342-346`, `:366-397`,
`:1328`). Top inset re-applies chrome and Ink card margin (`:399-407`).

**Kinds** (`NexusSurface.KIND_*`, `G/SurfaceModels.kt:236-242`, dispatch `G/SurfaceHudView.kt:434-443`):

| Kind | Renderer | Notes |
|---|---|---|
| `card` plain | `currentView`, auto-sized 14-17sp, 15 lines | `renderPlainCard` |
| `card` board rows | `boardView` rows with badge/trail | rows with `badge`/`trail` |
| `card` list rows | `renderList`: attention list with selection rail, secondary line, tone; a measured row window (`surfaceListViewport`) follows the row flagged `selected` by the plugin; overflow indicators | `G/SurfaceHudView.kt:870-1121`, `G/SurfaceListViewport.kt` |
| `card` editable | `editView` (`CaretReportingEditText`), see 3.4; may be drawn in the owner's notice ("inNotice") by mirroring text to the band and going see-through | `:453-524`, `G/NoticeComposeMirror.kt` |
| `card` bare (title only) | holds under its owner's band: draws nothing (see-through) while that plugin's notice is visible | `isBareCard`, `cardHoldsUnderBand` (`G/NoticeComposeMirror.kt:63-81`), `:876-886` |
| `timed-lines` | previous / current (25sp autosized) / next lines; 100 ms ticker while anchor playing; predicted position from `SurfaceAnchor.effectivePositionMs` | `:821-842`, `:1272`, `G/SurfaceModels.kt:114-128` |
| `media` | `MediaHudView`: artwork (mono or decoded binary), title/artist/album, progress, time; 500 ms ticker while playing | `G/MediaHudView.kt`, `:1123-1133` |
| `image` | `ImageHudView`: RGB565 bitmap decoded off-thread (2 threads), drawn FIT_CENTER on black; 1..512 px per edge, compressed <= 64 KiB | `G/ImageHudView.kt`, `G/SurfaceController.kt:514-607` |
| `reader` | `ReaderSurfaceView` (`ScrollView`): header/prose/aside segments; renderer owns wrapping and scroll; re-render keeps position by `resolveReaderScrollTarget` (bottom-pinned unless anchor top); viewport-page scroll on direction keys | `G/ReaderSurfaceView.kt`, `G/SurfaceModels.kt:22-41` |
| `ink` | `InkHudView` inside `InkCardClipHost`, an opaque card 92% wide at `12dp + inset` top, height capped by the display; `InkRendererLayer` owns the document store and decoding, the view is a disposable projection | `G/InkRendererLayer.kt`, `G/SurfaceHudView.kt:575-637` |

**Ink card presentation gate and morph.** A launcher-originated ink show arms
`InkPresentationGate`; the `HEAD` code releases it after the first frame is drawn with non-zero size
and the display is not transitioning (screen off), sending `ready`; forced release after 500 ms
(`G/InkPresentationGate.kt`, `G/SurfaceController.kt:383-440`). If the owner's notice band is visible
the card morphs from the band's height (clip reveal 280 ms plus alpha fade) and the band is closed
with reason `OWNER` (`G/SurfaceHudView.kt:634-764`, `G/NoticeController.kt:601-641`).

**See-through surfaces.** "See-through" (`seeThrough = true`) means the host draws no background and
no status/title/footer: used for an editable field drawn in the owner's band and for a bare card held
under its owner's band, so the band sits over what the wearer was looking at
(`G/SurfaceHudView.kt:508-519`, `G/NoticeComposeMirror.kt:55-81`). It follows the notice via
`NoticeController.observe`, moving into a band immediately and out of it after a 1.5 s fallback
(`G/SurfaceHudView.kt:546-573`, `:1330`).

**Ordering rules feeding the host.** `SurfaceOrderingCoordinator` drops stale bases by `seq`, holds
one unmatched anchor-only update until its base, and treats hide as a base (`G/SurfaceOrderingCoordinator.kt`).
Anchor-only updates: `timed-lines` without `lines`; `media` with `anchor` but no `mediaTitle`/`artwork`
(`G/SurfaceController.kt:657-665`, `:200-216`). A surface `update` merges with the previous surface
when `surfaceId`, `kind` and `contentKey` match (`G/SurfaceModels.kt:263-267`).

### 4.3 Notice band

**Data.** `NoticeStateMachine` (single slot, pure), `NoticeController` (envelopes, expiry, wake,
input), rendered by `NoticeOverlayRenderer` via `NoticeController.observe` (`G/NoticeController.kt:261-546`,
`:548-1326`).

**Content.** `NoticeSurfaceContent`: `title`, `body` or structured `lines`, `footer`, `interactive`,
up to 3 `actions`, `ttlMs` (2-45 s, default 8 s), optional `image`, `wakeDisplay`, `backdrop`
(`S/NoticeSurfaceContract.kt:51-73`, `:198-214`). Wire paths in `BUSSPEC.md:887-1310`.

**Update triggers and rules.**
- `/notice/show`: validated; `seq` must exceed `latestSeq`; a different `surfaceId` replaces the
  previous notice and reports `closed(replaced)` to the phone; a notice with an `image` shows only
  after the decode completes (`:873-984`).
- `/notice/update`: patch; sender must own the visible slot with matching instance identity; a
  patch that would leave title, body and lines all empty is ignored; an update that carries actions or
  `interactive` re-arms a previously answered band; an engaged (paged) band keeps its expiry
  (`:306-379`, `:986-1012`).
- `/notice/hide`: owner hide, sequence-ordered (`:491-509`, `:1014-1025`).
- Local: BACK dismiss (`USER`), TTL expiry (`TIMEOUT`), link lost (`DISCONNECT`)
  (`:660-665`, `:700-708`, `:529-541`). Each close reports `/notice/closed` (`:1127-1138`).
- Answering: one answer per band, marked in the same transition that reads it (`answer`,
  `:390-404`); an action row answers `/notice/action`, otherwise `/notice/input` (`:1087-1097`). If
  the send fails the band stays answered and shows "Delivery not confirmed" (`:416-423`, `:215-216`).
- Paging: a band pages if it has <= 1 actions and the measured page count > 1; 2+ actions claim the
  directions and never page. First page turn sets `engaged` and a 30 s inactivity expiry (`:77-79`,
  `:444-485`).

**Input claim.** `claimsInput` = expects input; `claimsDirection` = more than 1 live action or paged;
`claimsAllInput` = backdrop and not hidden by the camera; `ownsRingInput` = expects input or claims
direction or backdrop (`:716-736`; `noticeOwnsRingInput`, `:92-97`). `NoticeController.setCameraOverlayActive`
makes `visibleNotice()` null (and therefore releases all of the above) (`:82-97`, `:864-871`).

**Display side effects.** Show requests a wake (`DisplayWakeKind.NOTICE`) with a 75 ms/2 s
lock-settle retry; close may sleep the display via the service's lock action unless the launcher,
surface, assistant episode, activity, or camera is up (`:1158-1235`, `:1251-1289`,
`G/NoticeSleepPolicy.kt`). Window flag `KEEP_SCREEN_ON`.

**Rendering and lifecycle** (`G/NoticeOverlayRenderer.kt:212-360`): container created lazily on the
first notice; band slides from `-bandHeight` to 0 and fades in over 280 ms on `ENTER`, `UPDATE` re-renders
in place, `REENTER` retargets an in-progress exit; dismiss slides out over 240 ms then `teardown()`
removes the window; `backdrop` shows an opaque black scrim that fades with the band; Ink morph fades
in place instead. Band width is 92% of the display, top `12dp + hudTopInset`
(`G/InkCardPresentation.kt:11-23`). Inline compose line comes from `NoticeComposeMirror`
(`:133`).

### 4.4 Pin

**Data.** `PinStateMachine` (single slot, `seq`-ordered, optional TTL) in `PinController`; wire
`/pin/show`, `/pin/hide` (`G/PinController.kt:27-174`). Content `title`, `lines`, `position`
(4 corners), `ttlMs`, `size` (SMALL: title 24 chars, 2 lines of 28; MEDIUM: 28, 3 lines of 32)
(`S/PinSurfaceContract.kt:23-66`).

**Update triggers.** Render on every `notifyChanged` (show, hide, expiry, camera flag)
(`G/PinOverlayRenderer.kt:61-90`). A position change re-runs `updateViewLayout`; a top inset change
re-positions only when a pin exists (`:103-111`).

**Lifecycle.** Window created on first non-null pin, removed on hide/null. The pin's corner is
reserved from the activity corner allocator (`G/ActivityController.kt:691`,
`G/ActivityPresentationPolicy.kt:137-165`). `PinPanelView` is also the activity chip view
(`G/PinOverlayRenderer.kt:142`).

### 4.5 Activity overlay

**Data.** `ActivityStateMachine` (max 2 residents, per-surface `seq`, a global clear watermark) and
`ActivityController` (`G/ActivityController.kt:70-826`); wire `/activity/start|update|end`, and the
hub-owned empty-slot assertion on `/activity/end`. Content has `glyph`, `primary`, `secondary`,
`progress`, `eta`, `detail`, `actions`, `maxDurationMs`, `wakeDisplay`, extras `badge`, `measure`,
`track` (`S/ActivitySurfaceContract.kt:54-75`). `surfaceId` must equal `"<ownerPluginId>:local"`
(`G/ActivityController.kt:815-821`).

**Presentation is platform-chosen** (`selectActivityPresentation`, `G/ActivityPresentationPolicy.kt:57-76`):
`HIDDEN` if the camera overlay is up; `FLARE` if significant and the 10 s flare budget (or the 60 s
urgent budget) allows; `PULSE` if significant without budget, or context is active-surface or
Nexus-launcher; otherwise `CHIP` after 10 s idle (or never, if "always expanded" is set) else
`PANEL`. Non-primary activities are `CHIP` (`PULSE` on an event). Context is derived from live state:
camera overlay > active surface > launcher shown > idle (`:705-710`). The primary is the most recent
significant, else the oldest (`:113-127`). Corners are allocated pin-first, sticky, order TL, TR, BL,
BR (`:137-172`).

**Rendering.** One `ActivityIsland` (`HudIslandView`) per resident holding chip, panel and flare
forms; springs (`HudSpring.STANDARD` 0.42 s / 0.8, `BEAT`, `EXIT`), a flare holds
`STANDARD_MS + HOLD_MS = 3 780 ms` then folds; motion tokens make hidden/camera restores not replay
a flare (`G/ActivityOverlayRenderer.kt:87-219`, `G/HudIsland.kt:22-49`, `G/HudMotion.kt:28-37`).

**Update triggers.** Wire events; `SurfaceController.observe`, `PinController.observe`,
`LauncherOverlayRenderer.show/hide`, camera flag, and `setAlwaysExpanded` (phone setting) all call
`contextChanged()`, which republishes; a deadline runnable handles collapse and `maxDurationMs`
(`G/ActivityController.kt:432-442`, `:487-497`, `:701-726`).

**Input.** Idle-only claim (3.2 and 3.3). Action/open results: `/activity/action`, or
`/launcher/open` for the owner (`:741-759`); closes report `/activity/closed`
(`:808-813`).

### 4.6 Status badge

Not a Nexus surface: a phone-battery chip placed in the **ROM launcher's** status row. Shown only
when the top full-screen window (>= 50% of screen area, highest layer) is `com.rokid.os.sprite.launcher`,
a phone battery reading exists, the row's vertical centre was read from view id `status_power_iv`,
and a reserve is known (`G/StatusBadgeOverlayRenderer.kt:80-98`, `:159-246`, `:282-368`). The chip
is `phone` glyph plus `PhoneBatteryContract.label`, in `NexusUi.GREEN`, placed left-pinned beside the
weather. `StatusBadgeReserve` keeps a per-layout (weather / clock-only) reserve that only grows,
persisted (`G/StatusBadgeReserve.kt`, `G/StatusBadgeGeometry.kt`). It re-evaluates 150 ms after
`WINDOW_STATE_CHANGED`, `WINDOWS_CHANGED`, or ROM-launcher content change (`:146-157`), and on any
phone-battery change. `PhoneBatteryController` is `seq`-ordered, never expires, and clears only when
the hub restarts (`G/PhoneBatteryController.kt`). Side effect: on the ROM home layout (weather
visible) the measured row centre feeds `HudTopInset.onRomRowMeasured` (`:168-174`).

### 4.7 Remote pointer

`/core/pointer/command` (hub-only) -> `RemotePointerHubBridge` (sequence and stream gating, replies
on `/core/pointer/result`) -> `RemotePointerController.perform` (always enqueued on the main thread,
re-checks that the command is still reserved) -> `RemotePointerOverlayRenderer.show(position)` (ring
cursor at a normalized (x, y) clamped inside the screen by the radius) and, for `click` /
`long_press`, `dispatchGesture` of a 40 ms / 550 ms tap at that pixel (`G/RemotePointerController.kt:83-174`,
`G/RemotePointerOverlayRenderer.kt:206-230`, `:251-284`). `show`, `click`, `long_press` also
note user interaction and request a wake (`:111-118`). The cursor auto-hides after 8 s and on link
loss. Hidden on `hide`. Top of the ambient stack (1.3).

### 4.8 `HudIsland` and `HudTopInset`

`HudIslandView` is one outline that morphs between forms with damped-spring animation; retargeting
keeps velocity; the layout params of the host window are never touched
(`G/HudIsland.kt:22-349`). Used only by the activity overlay.

`HudTopInset` is a process-wide singleton holding `{auto, manualDp, autoDp}`
(`SharedPreferences("hud_position")`), range 0..120 dp (`S/PhoneHubCapabilitiesContract.kt:17-20`,
`:60-61`), `effectiveDp = auto ? autoDp : manualDp`. `manualDp`/`auto` come from the phone in
`/system/hub/capabilities` (`G/GlassesHub.kt:1359-1375`); `autoDp` is `round((rowCentreY - 364) /
density)` measured from the ROM row with 2 dp hysteresis (`G/HudTopInset.kt:18-19`, `:49-69`).
Observers are called on the main thread. Consumers: launcher (list padding, grid safe padding), surface
host, ink card margin, notice band top, pin and activity top-corner offset, `MainActivity`.

## 5. Cross-module interactions the UI depends on

The wire authority is `BUSSPEC.md`; the public API is `docs/PLUGIN_SDK.md`. Anything below is
observable by the phone or by plugins, so it must stay byte-for-byte compatible.

### 5.1 Received by the glasses hub and routed to UI

Dispatch order in `GlassesHub.onRemoteEnvelope` (`G/GlassesHub.kt:275-459`): `/core/remote-input/command`,
`/core/navigation/request`, `/core/pointer/command`, `/core/native-apps/request` first (hub-only,
handled before any plugin routing, invalid -> error reply `INVALID_CORE_REQUEST`/`INVALID_NATIVE_APP_REQUEST`);
then capabilities, setup, `assistant dismiss`, repair, `/glasses/hud-mode/config`, `/glasses/tile-layout/config`, wireless
ADB, media sync, phone battery; then `TtsController`, `PinController`, `NoticeController`,
`ActivityController`, `SurfaceController`, `TileController`; then `/launcher/list`, `/launcher/glyphs`.

| Path | Consumer | Behavior that must be preserved |
|---|---|---|
| `/surface/show`, `/surface/update`, `/surface/hide` (`BusPaths` `S/BusConstants.kt:51-53`) | `SurfaceController` | `seq`-ordering, anchor stash, merge rules, image/media binary validation, `SURFACE_SHOW` vs update distinction for launcher-return (`G/SurfaceController.kt:111-198`, `BUSSPEC.md:321-428`) |
| `kind:"ink"` in `/surface/show|update` with `ink.document` / `ink.patch`; `/surface/hide` | `SurfaceController`, `InkRendererLayer` | exactly one of document/patch (`G/InkRendererLayer.kt:44-56`); ink is always overlay; revisions and resync (`BUSSPEC.md:575-641`) |
| `/notice/show|update|hide` | `NoticeController` | see 4.3; `BUSSPEC.md:887-1310` |
| `/pin/show|hide` | `PinController` | `BUSSPEC.md:642-847` |
| `/activity/start|update|end` (incl. empty-slot assert) | `ActivityController` | `BUSSPEC.md:1311-1615` |
| `/launcher/list`, `/launcher/glyphs` | `GlassesHub.updateLauncherEntries/Glyphs` | shape `plugins[{id,displayName,iconKey}]`; glyph payload validated by `GlyphContract` (`G/GlassesHub.kt:1196-1251`) |
| `/glasses/hud-mode/config` | `HudModeStore`, `TileController` | `version >= 1`, `mode` in {`list`,`grid`} (`S/HudModeContract.kt`) |
| `/glasses/tile-layout/config` | `TileLayoutStore` | `TileLayoutContract.entriesFromConfig` |
| `/tile/publish` | `TileController` -> `TileCache` | `WidgetTileContract.fromPayload`; ignored in list mode (`G/TileController.kt:35-43`) |
| `/system/hub/capabilities` | `GlassesHub.updateRemotePhoneCapabilities` | `cameraConsumerName`, `CAMERA_CONSUMER_READY` (launcher camera entry), `activityAlwaysExpanded`, `hudTopInsetDp`, `hudPositionAuto` (`G/GlassesHub.kt:1359-1386`) |
| `/phone/battery` (`BusPaths.PHONE_BATTERY`) | `PhoneBatteryController` | `seq`-guarded; drives surface status row and the badge |
| `/glasses/assistant/dismiss` | `RBAS.requestNativeAssistantDismiss` | arms a 3 s back-burst against the native assistant windows (`G/GlassesHub.kt:331-335`, `G/RokidBusAccessibilityService.kt:1195-1202`) |
| `/core/native-apps/request` | `NativeAppsController` | list (<= 64 apps, labels sanitized) and launch; results on `/core/native-apps/result` with the request id preserved (`BUSSPEC.md:2191-2206`) |
| `/core/remote-input/command`, `/core/navigation/request` | `RemoteInputHubBridge` | IME editing deltas; navigation over accessibility focus (`BUSSPEC.md:2207-2251`); navigation reads `AccessibilityWindowRoots.getNavigationRoot` and invokes a node action labelled `"Select"` on Nexus nodes (`G/RemoteNavigationController.kt:235-244`; `G/InkHudView.kt:442-443`) |
| `/core/pointer/command` | `RemotePointerHubBridge` | `BUSSPEC.md:2252-2291` |

The four `/core/*` families are **trusted hub-to-hub controls**: reserved by `PathRules`, consumed
before plugin routing, and never exposed as plugin capabilities (`BUSSPEC.md:2183-2190`, root
`AGENTS.md`). Nothing in the rewrite may make them reachable from plugin paths, plugin prefixes, the
SDK, or a new plugin-visible route. `/tile/publish` is a plugin path (capability `widget_tile`).

### 5.2 Sent by the glasses UI

| Path | Sender | When |
|---|---|---|
| `/launcher/open {pluginId}` | `GlassesHub.openLauncherEntry`, from launcher selection, `MainActivity` selection, and `ActivityController.fireOrOpen` | `G/GlassesHub.kt:596-601` |
| `/surface/input {surfaceId, keyCode, action}` | `SurfaceController.forwardSurfaceInput` | DOWN/UP of forwarded keys; ring double tap sends BACK DOWN; `flushPendingTaps` sends key 83 DOWN (`G/SurfaceController.kt:348-357`) |
| `/surface/text-committed {surfaceId, text?, cancelled, ownerPluginId}` | `forwardSurfaceText` | Enter/IME send, or BACK cancel (`:365-373`, `S/EditableSurfaceContract.kt`) |
| `/ink/event {surfaceId, type, ...}` types `ready`, `action{actionId,dataset}`, `closed{reason}`, `resync{documentId,revision,patchDocumentId,patchBaseRevision}`, and `error` | `SurfaceController.sendInkEvent` | `:410`, `:438`, `:794-867` (reasons `user`, `plugin`, `replaced`, `link_lost`, `renderer_error`, `S/InkSurfaceContract.kt:6-24`) |
| `/notice/action`, `/notice/input`, `/notice/closed` | `NoticeController` | `G/NoticeController.kt:846-862`, `:1127-1138`; payloads carry `interactionIdentity` |
| `/activity/action`, `/activity/closed` | `ActivityController` | `G/ActivityController.kt:746-813` |
| `/core/native-apps/result`, `/core/remote-input/session|status`, `/core/navigation/result`, `/core/pointer/result` | hub bridges | |
| `/camera/session/state`, `/camera/overlay`, ... | `CameraActivity` via its own `BusClient` (`clientId glasses-camera-domain`, prefixes `/camera/overlay`, `/camera/freeze/result`, `/camera/link/offer`) | `G/CameraActivity.kt:190-215` |

Also a non-bus contract: **`NEXUS_RING_FOCUS`** broadcast to package `com.anezium.r08accessbridge`
(action `com.anezium.r08accessbridge.action.NEXUS_RING_FOCUS`, extras `focused: Boolean`, `ts:
Long`) (`G/RingFocusCoordinator.kt:75-76`, `:142-148`). The external bridge stands down when true.

### 5.3 Plugin-visible semantics the UI implements

From `docs/PLUGIN_SDK.md` (foreground exclusivity, `handlesBack`, `SURFACE_BUSY`, editable fields,
reader, list rows, notices, pins, activities, ink, widget tiles) and `BUSSPEC.md`: the glasses
decide presentation (activity chip/panel/flare/pulse is never plugin-chosen,
`docs/PLUGIN_SDK.md:147-161`); a plugin cannot open the launcher; a notice BACK is never forwarded to
the plugin (`G/NoticeController.kt:692-708`); a tile publish is never foreground-exclusive.

## 6. Known bugs and fragile spots

Severity labels: **B** = observable bug or contract violation, **R** = race or fragility,
**D** = duplication or debt that makes the rewrite harder.

### 6.1 Windows, focus, and lifecycle

**F-1 (R) No Nexus window between launcher hide and surface arrival.** `completeOpen` hides the
launcher as soon as `/launcher/open` is *sent* (`G/LauncherOverlayRenderer.kt:235-242`); the surface
arrives after a phone round trip. During that gap: the ROM home is visible; ring keys pass
(`RBAS:237`) to the ROM launcher, where an unclaimed key-up can start phone music (comment at
`RBAS:28-32`); the activity presentation flips from `PULSE` (launcher) to `PANEL` (idle)
(`LauncherOverlayRenderer.hide` -> `ActivityController.onLauncherVisibilityChanged`, `:155`); the
status badge can appear over the ROM home; the standby watchdog reads `ROM_HOME`
(`G/DisplayStandbyWatchdog.kt:191-201`). Ring focus is held only by the 10 s handoff.

**F-2 (R) `launcherOpen=true` is not a confirmation.** The launcher closes and a return claim is
recorded even if the phone denies (`SURFACE_BUSY`, plugin missing) or the plugin never shows a
surface (`G/GlassesHub.kt:596-606`). There is no negative acknowledgement path to the glasses UI.

**F-3 (R) `LauncherReturnCoordinator.pendingPluginId` never expires** (`G/LauncherReturnCoordinator.kt:6-36`).
Any later `/surface/show` whose id equals or starts with `"<pending>:"`, including one the plugin
issues unprompted, is treated as launcher-originated: it completes a ring handoff and claims a
return to the launcher. A claim also survives the surface being replaced by another plugin's surface
(`launcherReturnSurfaceId` stays) and can fire later. The `camera` entry leaves `pending = "camera"`.

**F-4 (D) Duplicated "plugin never answered" bound.** The 10 s ring handoff
(`G/RingFocusCoordinator.kt:77`) and, in the stash, a second 10 s `surfaceArrivalTimeout` in the
launcher carry the same meaning in two places.

**F-5 (B) Camera does not return to the launcher.** `launcherEntryOpensSurface("camera")` is false,
so `completeOpen` hides immediately and `recordLauncherOpen("camera")` can never be consumed; camera
exit lands on whatever task is below (`G/LauncherOverlayRenderer.kt:237-241`, `G/GlassesHub.kt:609`).
Plugin surfaces return, camera does not. Camera also runs in another process, so main-process overlays
have no window ordering relation to it.

**F-6 (D) Two launchers.** `MainActivity` has its own list launcher (fixed 52 dp rows, `translationY`
scroll to avoid the waveguide grain it attributes to `ScrollView`, `G/MainActivity.kt:186-199`) while the
overlay list uses a `ScrollView` (`G/LauncherOverlayRenderer.kt:298-308`). The activity launcher has no
grid, no ring handling (it relies on the external bridge), no return coordination, no handoff
(`:527-531`), and stays alive under surfaces. Support hacks exist only because of it:
`MainActivity.finishIfStale`, the static `resumedInstance` / `liveInstance` / `interactiveFlowActive`
(`:632-656`), read by `SurfaceController` and `DisplayStandbyWatchdog` (`G/DisplayStandbyWatchdog.kt:151`,
`:197`).

**F-13 (R) Triple tap opens the launcher over anything except an editable card.** No guard for the
camera, an onboarding screen, a notice, or an active setup automation (`RBAS:184-200`).

**F-14 (B) Tap flush ignores the launcher.** `flushPendingTaps` sends key 83 to the active surface
even when the launcher is open above it (`RBAS:321-329`). Two-contact taps on the launcher
therefore reach a plugin underneath after 601 ms.

**F-16 (B, unverified on device) Camera-overlay flag reaches only the activity layer.**
`CameraOverlayView` calls `PinController.setCameraOverlayActive` and
`NoticeController.setCameraOverlayActive` inside the `:camera` process, where those singletons have
no windows; only `ActivityController` is bridged by broadcast
(`G/CameraOverlayView.kt:110-120`, `G/CameraOverlayVisibilityBridge.kt:93-104`, manifest
`android:process=":camera"` `M:94`). So the main-process notice and pin still see
`cameraOverlayActive == false` and can draw (and claim input) over the camera view.

**F-17 (B) Z-order invariant is not maintained.** See 1.3: `reassert()` runs only when launcher,
surface, or activity windows are added. Pin windows added after a notice, and notices added after
the pointer, violate the documented `pin < activity < notice < pointer` order until the next
launcher/surface add. `reassert()` itself removes and re-adds up to four live windows including one
mid-animation notice (`G/NoticeOverlayRenderer.kt:150-157`).

**F-18 (R) Window re-adds destroy per-window state.** `ensureOnTop` is remove + add; `PinOverlayRenderer`
reuses its params, but any transient WM state (focus, in-flight layout) is lost; the notice band is
re-added at the container level while `slide`/`fade` animators keep running.

**F-20 (D) Ordering/first-frame magic in surface teardown.** `MainActivity.finishIfStale`,
`finishAndRemoveTask`, and `activeDisplayedViaActivity` exist only to stop Android resuming a stale
task when a `SurfaceActivity` task disappears (`G/SurfaceController.kt:56-60`, `:624-638`, `:703-710`;
`G/SurfaceActivity.kt:32-37`).

**F-21 (R) Editable cards change window type mid-session.** `surfaceDisplayPath` forces ACTIVITY for
editable cards, so a session that starts on the overlay path moves to an activity task when an
editable card appears, and moves back on the next non-editable card
(`G/InkCardPresentation.kt:63-76`). `stepLauncherAside` only fires when the `surfaceId` changed.

**F-22 (B) A debug probe changes production display path persistently.** `showDemoCard` stores the
path in `surface_renderer/display_path` (`G/SurfaceController.kt:93-98`, `:218-220`).

**F-23 (R) `OpenLauncherReceiver` is exported with no permission** and works regardless of setup
state (`M:156-162`).

**F-25 (R) Ring focus broadcaster drops early events.** Every setter returns if the input service
has not connected (`G/RingFocusCoordinator.kt:104-141`); `onServiceConnected` seeds only
`surfaceActive` and `noticeOwnsRing`, not the launcher (it cannot be shown yet). After a service
restart with a surface active but no handoff state, focus is recomputed from those two only.

### 6.2 Input

**F-15 (R) The ring pipeline is selected by device-name substring** `"R08"` (`RBAS:155`). The emulator
`ring.sh` sends `input keyevent 85/87/88`, which does not carry that device name, so it goes through
the generic chain, where the launcher consumes 85/87/88 as no-ops (`G/LauncherOverlayRenderer.kt:191-203`).
**(unverified)**: what `input keyevent` reports as `device.name` on the emulator build used.

**F-27 (R) `consumedDownKeys` is keyed by keycode only**, shared by both pipelines and both devices
(`RBAS:32`, `:221`, `:229`, `:262`). A consumed DOWN whose UP never arrives would swallow a later UP of
that keycode from any device.

**F-28 (D) Duplicated gesture state.** `RingTapPolicy` (350 ms) lives in four objects and
`DpadPairDedupe` (150 ms) in five (launcher, surface, notice router, activity, `MainActivity`), each
with its own state and runnables. `LauncherOverlayRenderer.hide` resets the tap policy but not its
`swipeDedupe` (`G/LauncherOverlayRenderer.kt:141-142`). Owner changes during a pending tap are handled
per owner (activity captures a target, notice captures the notice) but not for launcher/surface.

**F-12 (B) BACK sleeps the display when nothing claims it.** The ROM launcher's own key-up handler
puts the display to sleep on an unclaimed BACK; a redundant dismiss right after Nexus consumed one
lands there. The stash adds a 4 s swallow window (section 6.4). At `HEAD` a fast double BACK can sleep
the glasses.

**F-29 (R) Phone link loss does not close non-ink surfaces.** `SurfaceController.onPhoneLinkLost` only
closes an ink surface (`G/SurfaceController.kt:335-346`); a card/list/reader/media stays on screen and
keeps capturing keys until the wearer presses BACK. Notices close (`G/NoticeController.kt:660-665`),
the pointer hides, activities are cleared by the phone's assertion on reconnect.

### 6.3 Rendering

**F-7 (D/B) Full re-render on every change.** List mode rebuilds every row (`removeAllViews`) on each
selection move (`G/LauncherOverlayRenderer.kt:339`); grid mode rebuilds every tile, including
`TileCache.get` (a `SharedPreferences` JSON parse per tile) on each move (`G/GridLauncherView.kt:123-157`,
`G/TileCache.kt:31-41`); a `CRITICAL` `LiveTileView` restarts `CriticalBlink` each time; the stashed grid
code adds a re-render on width change. `SurfaceHudView.renderNow` rebuilds rows on every update and is
instantiated twice (overlay, activity) (`G/SurfaceHudView.kt:409-444`).

**F-9 (B) Grid open transition is not cancelled and is re-entrant.** `TileExpansionAnimator.cancel`
has no caller from the launcher (`G/GridLauncherView.kt` has none; `hide()` does not cancel). If the
wearer presses BACK during the 320 ms tween, the overlay is removed but the animator still settles and
calls `completeOpen`, sending `/launcher/open` for an entry the wearer backed out of. Keys are not
blocked during the tween; a second open while running replaces the animator and leaks the first ghost
view (`G/GridLauncherView.kt:179-187`, `G/TileExpansionAnimator.kt:44-72`). **(unverified on device)**.

**F-8 (B) Grid geometry overflows the display.** Fixed 96 dp unit x 4 columns + gaps = 408 dp, on a
320 dp-wide display; columns run off the right edge (`G/GridLauncherView.kt:105`, `:130-152`).

**F-10 (B) Open grid ignores `TileCache` updates.** Nothing subscribes to `TileCache.put`; tiles
refresh only on a selection move or entry-list change (`G/TileController.kt:35-43`).
`LiveTileView` cannot show selection, so a tile with live data has no focus indicator
(`G/GridLauncherView.kt:143`).

**F-19 (B) Return-collapse animation is inert.** `show()` runs `beginCloseTransition` on a brand-new
grid instance whose `ghost` is null, so it animates a background-less view and then sets alpha
values that were never changed (`G/LauncherOverlayRenderer.kt:115-125`, `G/GridLauncherView.kt:190-214`).

**F-11 (R, unverified) Launcher observers run on the delivering thread.** `LauncherOverlayRenderer`'s
callback renders directly (`:106-110`); `MainActivity` wraps in `runOnUiThread` because "the hub
notifies listeners from the CXR receive thread" (`G/MainActivity.kt:66-73`). The CXR path posts to
the main looper (`G/CxrBusBridge.kt:100-105`) but the SPP path invokes `GlassesHub.onRemoteEnvelope`
from the server's executor (`G/SppServerManager.kt:34-45`); a failure would be swallowed by
`notifyLauncherEntries`'s `runCatching` (`G/GlassesHub.kt:1404-1409`), silently losing a list update.

**F-24 (D) Layout depends on reading the ROM.** `HudTopInset.autoDp` for every Nexus layer comes
from `StatusBadgeOverlayRenderer` reading `status_power_iv` bounds in the ROM launcher
(`G/StatusBadgeOverlayRenderer.kt:168-174`). If the ROM renames view ids the badge disappears and
the auto inset stops updating.

**F-30 (D) Selection is an index, not an identity.** `selectedIndex` survives reorder/removal by
position, so a `/glasses/tile-layout/config` push or a plugin list change can move the highlight to another
plugin (`G/LauncherOverlayRenderer.kt:106-109`).

**F-31 (D) `SurfaceHudView` is a 1 376-line monolith** holding every kind and the ink morph, the
notice-follow subscription, inline compose mirroring, and status row; ink/reader/media/image states
are held in visibility toggles rather than separate views.

### 6.4 The stashed WIP (`stash@{0}`), problem by problem

Read with `git stash show -p`; not applied. It touches 9 files. Each change and what it targeted:

| Change | Target problem (from the patch's own comments) |
|---|---|
| `LauncherOverlayRenderer.completeOpen`: keep the launcher **visible** after sending `/launcher/open`, add `surfaceArrivalTimeout` (10 s, calls `hide`), hide immediately only for camera; `hide()` cancels the timeout | F-1: hiding on tap "let the wearer see the raw home screen for a beat before the real content replaced it" |
| `SurfaceController.showOrUpdate` and `showOrUpdateImage`: `if (completesRingHandoff) LauncherOverlayRenderer.hide()` at surface arrival | Complements the above: the surface arrival now closes the launcher. Adds a new failure mode: the launcher stays interactive during the gap, keys still reach it, and only a matched `SURFACE_SHOW` or the 10 s timer closes it (F-2, F-3 still apply) |
| `MainActivity.renderScreen`: when COMPLETE and not finishing, call `LauncherOverlayRenderer.show()` and `finish()`, falling back to the activity list only if the service is down; `isFinishing` guard | F-6: "the app icon and the triple-tap gesture must open the same launcher, so grid mode (toggled from the phone) applies no matter how it was opened" |
| `RBAS`: `lastConsumedBackAtMs`, `BACK_SLEEP_GUARD_MS = 4000`, `guardAgainstRomSleepOnBack` applied to the generic BACK path and to the "no owner" ring path | F-12: a redundant BACK after Nexus already closed something falls through to the ROM launcher's `onKeyUp(BACK)`, which puts the display to sleep (confirmed by the author via logcat) |
| `GridLauncherView`: `expansionAnimator.enabled = false` | Kill switch "while the screen-off investigation has the floor": grid open/close motion suspected in display-off behavior (F-9) |
| `TileGridContainer`: unit = `min(96dp, (availableWidth - 3*gap)/4)`, re-render on `onSizeChanged` width change; `marginSpace()` corrects double-counted safe-area padding in the open/close rects | F-8 (408 dp vs 320 dp) and a padding double-count that pushed the expanded panel's right/bottom edge outside the safe area |
| `LauncherOverlayRenderer.show`: removed `lastOpenedEntryId` and the return-collapse tween; `openSelected` no longer runs `beginOpenTransition` | Consequence of disabling the animator (F-9, F-19); with them removed the grid launcher is instant-in/instant-out |
| `TileExpansionAnimator`: `PathInterpolator(0.4, 0, 0.2, 1)` | Cosmetic: fast-out-slow-in instead of linear tween |
| `RokidHudTokens.DURATION_STRUCTURAL_MS` 320 -> 220 and the two roadmap docs | Faster structural motion |

## 7. Parity checklist

Tags: **[JVM]** pure or Robolectric test; **[EMU]** needs the emulator (Cuttlefish at 480x352,
`tools/emulator/*`, service enabled, real windows and key injection); **[DEVICE]** needs the real
glasses (ROM launcher, R08 hardware, firmware gesture timing, `:camera`, display power).
Numbers in parentheses are `HEAD` citations. Where `HEAD` behavior is a bug (section 6) the item
states the behavior to **preserve** or, if noted **[decide]**, needs a product decision before it
becomes a test.

### 7.1 Window host, z-order, lifecycle

1. [EMU] The launcher window is `TYPE_ACCESSIBILITY_OVERLAY`, match-parent, `FLAG_LAYOUT_IN_SCREEN | FLAG_KEEP_SCREEN_ON`, translucent, focusable and touchable (`LOR:91-98`).
2. [EMU] The surface overlay window has the same type and flags as the launcher (`SOR:40-47`).
3. [EMU] Notice, activity windows are match-parent, `NOT_FOCUSABLE | NOT_TOUCHABLE`; the notice window additionally has `KEEP_SCREEN_ON` (`NOR:39-42`, `ActivityOverlayRenderer:257-264`).
4. [EMU] The pin window is wrap-content, `NOT_FOCUSABLE | NOT_TOUCHABLE`, gravity is the requested corner with a 12 dp margin plus the top inset for top corners (`PinOverlayRenderer:72-79`, `:113-136`).
5. [EMU] The pointer window is match-parent with `NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS` (`RemotePointerOverlayRenderer:297-306`).
6. [EMU] The status badge window is wrap-content by 20 dp, `NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_NO_LIMITS` (`StatusBadgeOverlayRenderer:215-234`).
7. [EMU] After opening the launcher over a visible notice, pin and activity, the stacking order bottom to top is launcher, pin, activity, notice, pointer (`HudOverlayStack:28-33`).
8. [EMU] After opening a surface overlay over a visible notice, the notice is still above the surface (`SOR:56`).
9. [EMU] The launcher is never left below an ambient layer that was on screen when it opened.
10. [EMU] (decision needed) A pin created while a notice is visible ends up below the notice (documented intent; `HEAD` violates it, F-17).
11. [EMU] Only one of {launcher window, surface overlay window} exists per role; `show()` when already shown re-renders and refocuses without adding a second window (`LOR:88`).
12. [EMU] Every `hide()` removes the window; a second `hide()` is a no-op.
13. [EMU] When the accessibility service is destroyed all Nexus windows are removed and input state is reset (`RBAS:277-309`).
14. [EMU] When the service reconnects with an active OVERLAY-path surface, the surface window is re-shown; the launcher is not (`SOR:18-25`).
15. [JVM] `SurfaceController.activeSurface()` survives a service destroy/connect cycle (state is not tied to the window).
16. [DEVICE] Opening any Nexus overlay never leaves a grey/white focus veil over a see-through card (`SurfaceHudView:222-225`).
17. [DEVICE] The 480x352 canvas is what is visible: launcher content sits inside `SAFE_X = 16dp`, `SAFE_Y = 12dp` (plus top inset).

### 7.2 Launcher: opening, closing, modes

18. [EMU] Three key-83 DOWN events with `repeatCount == 0` within 600 ms open the launcher and consume the third (`TouchpadGestureDetectors:19-51`, `RBAS:194-200`).
19. [JVM] Two contacts followed by 600 ms of silence never trigger; a non-83 DOWN between contacts clears the streak (`TouchpadGestureDetectors:19-30`).
20. [JVM] After a trigger, BACK and ENTER keys within 800 ms return `CONSUME` (`:60-62`).
21. [JVM] `consumeExpiredTapCount` returns at most 2 and only after the window elapsed (`:53-59`).
22. [EMU] Triple tap while the launcher is open consumes the key and does not toggle the launcher closed (`RBAS:196-199`).
23. [EMU] Triple tap is ignored while an editable card is the active surface (`RBAS:184-188`).
24. [EMU] `OPEN_LAUNCHER` toggles: shown -> hidden, hidden -> shown, service down -> no window and a log line (`OpenLauncherReceiver:9-16`).
25. [EMU] BACK on the open launcher closes it, and the matching UP is swallowed (`LOR:198-201`, `RBAS:220-222`, `:178-182`).
26. [EMU] Ring double tap (2 x key 85 within 350 ms, resolved at 351 ms) closes the launcher; 3 or more taps do nothing (`RingTapPolicy`, `LOR:206-214`).
27. [JVM] `RingTapPolicy`: 1 tap -> SINGLE, 2 -> DOUBLE, 3+ -> IGNORE, only after `> 350 ms` since the last tap (`RingTapPolicy:16-38`).
28. [EMU] The launcher consumes every key except keycode 186 while shown, including UP events and repeats (`LOR:172-204`).
29. [EMU] Launcher swipes: DPAD_RIGHT/DOWN move +1, DPAD_LEFT/UP move -1, the duplicate of a same-direction swipe within 150 ms is dropped but consumed (`DpadPairDedupe`, `LOR:179-189`).
30. [JVM] `DpadPairDedupe` returns FORWARD/BACKWARD once per direction per 150 ms, null for non-DPAD or repeats (`TouchpadGestureDetectors:86-120`).
31. [EMU] Ring 87 moves +1 and 88 moves -1 with wraparound in both list and grid (`LOR:158-170`, `:216-220`).
32. [JVM] Selection wraps: index n-1 + 1 -> 0 and 0 - 1 -> n-1; with no entries nothing moves (`LOR:216-220`).
33. [EMU] `selectedIndex` persists across close/open of the launcher and is clamped when the entry count shrinks (`LOR:52`, `:108`).
34. [JVM] (decision needed) Selection is by index, not identity (`HEAD` F-30).
35. [EMU] ENTER/DPAD_CENTER or a ring single tap sends `/launcher/open {pluginId: selected}` (`GlassesHub:596-601`).
36. [JVM] `openLauncherEntry("")` returns `launcherOpen=false reason=blank`; with no context returns `hub_not_started`; on a send error returns `code=<error>` (`GlassesHub:585-606`).
37. [EMU] On `launcherOpen=true` for a non-camera entry the launcher closes and a ring handoff begins (`LOR:235-242`); on `launcherOpen=false` it stays open.
38. [EMU] Selecting the `camera` entry starts `CameraActivity` in `:camera`, closes the launcher, and does not begin a ring handoff (`LOR:237-241`, `GlassesHub:587-595`, `:609`).
39. [DEVICE] The launcher hiding leaves the ROM home visible only for the handoff gap: measure the gap and record it as the accepted baseline or the new target.
40. [JVM] Empty entry list: list mode shows "Waiting for phone" and "No phone plugins synced"; grid mode shows "No phone plugins synced" and hides the scroll view (`LOR:340-347`, `GridLauncherView:56-60`).
41. [JVM] List row text: icon, name at 18sp, max 2 lines with ellipsis, selected = bold + 2 dp outline, count line `i/n` (`LOR:349-394`).
42. [JVM] `HudModeStore.isGridModeEnabled` defaults to list when nothing was stored; stored value round-trips (`HudModeStore:14-29`, `S/HudModeContract`).
43. [JVM] `gridModeFromConfig`: returns null for `version < 1`, unknown mode, or null payload; true for `grid`, false for `list` (`S/HudModeContract`).
44. [JVM] `/glasses/hud-mode/config` with an invalid payload leaves the stored mode unchanged (`GlassesHub:347-353`).
45. [EMU] The mode is read at each `show()`; flipping it while open changes nothing until the next open (`LOR:102-104`).
46. [JVM] Grid mode starts the tile subsystem, list mode stops it; in list mode `/tile/publish` does not touch `TileCache` (`GlassesHub:231-233`, `TileController:35-43`).
47. [JVM] The camera entry is first, only when `CAMERA_CONSUMER_READY` and a consumer name exist; others follow `TileLayoutStore.applyOrder` (unplaced appended in original order) in both modes (`GlassesHub:1388-1402`, `TileLayoutStore:54-61`).
48. [JVM] `/launcher/list` skips blank ids, defaults `displayName` to the id, drops blank `iconKey` (`GlassesHub:1196-1218`).
49. [JVM] A `/glasses/tile-layout/config` push re-notifies launcher observers (`GlassesHub:359-369`).
50. [EMU] The launcher renders on top of a notice that was already visible and the notice keeps ring priority (`HudOverlayStack` doc, `RBAS:250-252`).

### 7.3 Grid launcher

51. [JVM] `TileGridPacker.pack` is row-major first-fit with 4 columns; null or unknown size is SMALL; rejects columns narrower than the widest tile (`S/tile/TileGridPacker.kt`).
52. [JVM] For `n` entries `GridLauncherView` lays out `n` tiles, for n = 0, 1, 5 (`GridLauncherViewTest`).
53. [JVM] Exactly the selected tile reports focus, for every selection index (`GridLauncherViewTest`).
54. [JVM] Tile = `LiveTileView` iff the tile subsystem is active and a cached snapshot exists; else `FallbackTileView` (`GridLauncherView:134-141`).
55. [JVM] `FallbackTileView` shows the icon and the upper-cased name; selected fill `SURFACE_SELECTED`, focus stroke 2 dp `FOCUS` (`FallbackTileView:70-92`).
56. [JVM] (decision needed) A live-data tile must show selection and focus (`HEAD` gives it none, F-10).
57. [JVM] `LiveTileView` binding: numeric title -> data readout + unit; non-numeric -> title; subtitle only for non-SMALL; badge when non-empty; rows only for LARGE, max 4 (`LiveTileView:101-139`).
58. [JVM] Tone styling: OK and INFO solid 1 dp, WARN dashed, CRITICAL 2 dp with blink, OFF solid; stale snapshot alpha 0x60 (`LiveTileView:151-171`, `TileCache:53-64`).
59. [JVM] `TileCache.isStale` is true at 10 min and for a snapshot with `receivedAt > now` (reboot) (`TileCache:53-64`).
60. [JVM] `TileRateLimiter`: 5 per plugin per 60 s, plugins isolated, excess dropped silently (`TileRateLimiter:19-36`).
61. [EMU] At 480x352 the 4-column grid fits inside the safe area with no tile clipped off the right edge (`HEAD` fails: F-8).
62. [EMU] (decision needed) Open/close tile transition: 320 ms (or 220 ms) ghost tween, siblings dimmed to alpha 0.35, instant when reduced motion is on (`GridLauncherView:161-243`, `TileExpansionAnimator:44-56`).
63. [JVM] Animation kill switch and reduced motion (`animator_duration_scale == 0`) settle immediately with the end rect and call `onSettled` (`TileExpansionAnimator:44-56`).
64. [JVM] A cancelled tween snaps to the end rect and does not call `onSettled` (`TileExpansionAnimator:60-70`).
65. [EMU] BACK during an open transition must not send `/launcher/open` (`HEAD` may; F-9). **[decide]**

### 7.4 Surface hand-off and return

66. [JVM] `LauncherReturnCoordinator`: `onSurfaceShown` matches `id == pending` or `id.startsWith(pending + ":")`; returns false and keeps pending on mismatch; true clears pending and stores the claim (`LRC:24-36`).
67. [JVM] `consumeReturnOnHide(id)` is true once for the claimed id, then false; `recordLauncherOpen` clears any prior claim (`LRC:10-16`, `:39-45`).
68. [JVM] `clearPendingLauncherOpen` clears pending only (`LRC:19-21`); `LauncherOverlayRenderer.show` calls it.
69. [JVM] (decision needed) Pending has no expiry (`HEAD`); a later unsolicited matching show claims a return (F-3).
70. [EMU] Launcher -> plugin -> plugin's `/surface/hide` returns to the launcher; launcher -> plugin -> BACK (with `handlesBack == false`) returns to the launcher (`SC:719`, `:729-731`, `:965-975`).
71. [EMU] Plugin surface opened without the launcher (e.g. phone-initiated show) does not open the launcher when it hides (`LRC` pending null).
72. [JVM] Only a `/surface/show`, not `/surface/update`, can complete a launcher hand-off (`SC:180`).
73. [EMU] A surface opened from `MainActivity`'s list does not return to the launcher on hide (no `recordLauncherOpen`, `MA:527-531`). **[decide]** whether this is intended after unification.
74. [JVM] `RingFocusCoordinator`: published focus = `launcherShown || surfaceActive || handoffPending || noticeOwnsRing`; publishes only on edge (`RFC:65-70`).
75. [JVM] `beginSurfaceHandoff` only sets pending if the launcher is shown; a matching `setSurfaceActive(true, completesHandoff = true)` clears it atomically; `setLauncherShown(true)` clears it; `expireSurfaceHandoff` clears it (`RFC:25-59`).
76. [JVM] The handoff expires after 10 s, cancelled by surface arrival or launcher show (`RFC:111-135`).
77. [JVM] `reset()` clears all four flags and publishes `false` if it was true (`RFC:57-63`).
78. [EMU] `NEXUS_RING_FOCUS` is broadcast to `com.anezium.r08accessbridge` with `focused` and `ts` on each edge, and only when the service is connected (`RFC:104-149`).
79. [DEVICE] With the R08 bridge installed, ring keys are handled by the bridge whenever focus is false and by Nexus whenever it is true.
80. [EMU] An ACTIVITY-path handoff hides the launcher and finishes a stale `MainActivity`; an OVERLAY-path update to the surface already on screen does neither (`SC:478`, `:624-638`).
81. [EMU] An ink surface never uses the activity path; if the overlay cannot be shown the session closes with `renderer_error` (`SC:616-623`).
82. [EMU] An editable card always uses `SurfaceActivity`, even when the display path preference is OVERLAY (`InkCardPresentation:63-76`).
83. [JVM] `surfaceDisplayPath(surface, configured)`: ink -> OVERLAY; card with editable -> ACTIVITY; else configured (`InkCardPresentation:63-76`).
84. [EMU] `SurfaceActivity` is translucent, has its own task, is excluded from recents, keeps the screen on, and is removed with `finishAndRemoveTask` when the surface ends (`M:80-87`, `SurfaceActivity:14-38`).
85. [EMU] Overlay show failure falls back to `SurfaceActivity` for non-ink surfaces (`SC:645-653`).
86. [EMU] Phone link loss closes an ink surface with `closed(link_lost)` (`SC:335-346`).
87. [EMU] (decision needed) Phone link loss leaves non-ink surfaces on screen (`HEAD`, F-29).
88. [EMU] Native-app launch via `/core/native-apps/request` does not touch overlays (`NativeAppsController:102-111`). **[decide]**

### 7.5 Surface content and ordering

89. [JVM] `SurfaceOrderingCoordinator`: stale bases (`seq <=` latest base) dropped; an anchor-only update with no matching base is stashed once and applied when the base arrives; hide is ordered against bases; `deactivate` keeps watermarks (`SurfaceOrderingCoordinatorTest`).
90. [JVM] `NexusSurface.fromPayload` merges with the previous surface only when `surfaceId`, `kind` and `contentKey` match; otherwise fields do not carry over (`SurfaceModels:263-267`).
91. [JVM] Unknown `kind` is rejected; empty `kind` defaults to `card` (`SurfaceModels:252-258`).
92. [JVM] Reader limits: 240 segments, 4 096 chars per segment, 40 000 total, title 120, subtitle/footer 240 (`SurfaceModels:244-249`, `:428-450`).
93. [JVM] `resolveReaderScrollTarget` keeps position or bottom-pins per anchor (`ReaderSurfaceView:22-32`).
94. [JVM] Surface list windowing follows the row flagged `selected` (`SurfaceListViewportTest`).
95. [JVM] Bare card + visible notice with the same owner -> see-through; editable `inNotice` draws in the band only for the owner's own band (`NoticeComposeMirror:55-81`).
96. [JVM] `noticeComposeRender` positions the caret at end (extra space), in the middle (next code point), and in an empty field (placeholder after caret) (`NoticeComposeMirror:88-112`).
97. [EMU] Editable card: Enter (hardware) or IME send commits `text`, BACK cancels with `cancelled = true`, and only that path sends `/surface/text-committed` with `ownerPluginId` (`SurfaceHudView:128-147`, `SC:365-373`).
98. [EMU] While an editable card is active, key events other than BACK reach the field (the service passes them) (`SC:255-269`).
99. [JVM] Image surfaces: metadata and binary validated (1..512 px, <= 64 KiB, MIME jpeg/png, sha-256); an older decode never replaces a newer surface; the previous bitmap stays until the new one decodes (`SC:141-156`, `:514-607`, `ImageSurfaceStateTest`).
100. [JVM] Ink: exactly one of document/patch; a patch without a store requests resync; a stale document revision is dropped (`InkRendererLayer:44-56`, `:74-97`).
101. [EMU] Ink first frame: `ready` sent once after the first drawn frame with non-zero size and screen on; forced after 500 ms; ink surface replacement sends `closed(replaced)` to the prior owner (`SC:383-440`, `:740-754`).
102. [JVM] Ink event payloads carry `surfaceId` and `type` with the documented extra fields (`SC:794-867`).
103. [EMU] The surface status row shows `HP nn%[+]`, the date `EEE d MMM`, and glasses charge `nn%[+]`, refreshed every 30 s and on phone-battery change (`SurfaceHudView:366-397`).
104. [EMU] Timed lines and media tick while their anchor is playing, at 100 ms and 500 ms, and stop on hide or pause (`SurfaceHudView:1313-1317`).

### 7.6 Input routing (generic)

105. [JVM] Generic order for a DOWN that survives the triple-tap decision: notice, launcher, surface, activity, then pass (`RBAS:211-217`) (test the extracted router).
106. [JVM] `KEYCODE_PROG_BLUE` (186) is never consumed by the service or the launcher (`RBAS:158`, `LOR:174`).
107. [JVM] A consumed DOWN's UP is consumed even if the consumer is gone (`consumedDownKeys`) (`RBAS:28-32`, `:178-182`, `:220-222`).
108. [JVM] `NoticeKeyInputRouter`: BACK dismisses any visible notice and consumes its repeats and UP; while an editable card is active every non-BACK key passes; directions are consumed and moved only when the notice claims direction, deduped at 150 ms; ENTER/CENTER answer only when the notice expects input; a backdrop consumes unclaimed ENTER/CENTER/DPAD DOWN (`NoticeKeyInputRouterTest`, `NoticeKeyDispatcher:38-84`).
109. [JVM] A notice consumes a press keyed by `(deviceId, keyCode, downTime)`; a different press of the same key after the notice is gone is not consumed (`NoticeKeyDispatcher:50-59`).
110. [JVM] `NoticeTouchpadInputPolicy.consumesUnclaimedKey` is true only for backdrop notices, DOWN, and ENTER/CENTER/DPAD_* (`NoticeTouchpadInputPolicy`).
111. [EMU] Surface (non-reader, non-editable): keys in `{BACK, ENTER, DPAD_CENTER, DPAD_*, SPACE, MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS}` are forwarded as `/surface/input` on DOWN and UP and consumed; other keys pass (`SC:276-286`, `:1051-1063`).
112. [EMU] Surface BACK DOWN with `repeatCount == 0` is forwarded and then handled: `handlesBack` arms the 1.5 s failsafe; otherwise the surface hides locally (`SC:281-284`, `:965-975`).
113. [JVM] Surface D-pad duplicates within 150 ms are suppressed together with the UP of the suppressed DOWN (`SC:899-909`).
114. [JVM] Reader: directions and MEDIA_NEXT/PREVIOUS scroll a viewport and are not forwarded; ENTER, CENTER, BACK are forwarded (`SC:311-329`).
115. [JVM] Ink key handling: directions move the action selection or scroll only when there is a selection or a scrollable; CONFIRM emits the selected action only if it is in the viewport; the paired UP is consumed only when an action is selected (`InkHudView:182-209`).
116. [JVM] Activity input claim requires: presenting, not camera overlay, no active surface, no visible notice, launcher not shown (`ActivityController:501-506`); ring claims add "tap always, directions only with actions" (`:508-520`).
117. [JVM] Activity generic keys: 83 consumed both actions; directions move selection when actions exist; ENTER/CENTER fire the selected action or open the owner; BACK passes (`:526-543`).
118. [JVM] `flushPendingTaps` replays at most 2 contacts, to the surface as `/surface/input {83, DOWN}` if any surface is active, else to `handlePendingTempleTap`, and never to a notice (`RBAS:311-333`).
119. [JVM] (decision needed) With the launcher open over a surface, expired contacts still reach the surface (`HEAD`, F-14).

### 7.7 Input routing (ring)

120. [JVM] Device name containing `R08` (case-insensitive) selects the ring pipeline; nothing else does (`RBAS:155`).
121. [JVM] With no launcher, surface, notice ownership or activity claim, ring keys return false (`RBAS:237`).
122. [JVM] Ring owner precedence: claimed notice key > notice ownership no-op > launcher > surface > activity (`RBAS:240-261`).
123. [JVM] A ring UP with a consumed DOWN is consumed; any other UP that reaches the owners is consumed; repeats are consumed without action (`RBAS:229-231`, `:239`, `:262`).
124. [JVM] `RingSurfaceInputPolicy`: 87 -> DPAD_RIGHT DOWN+UP; 88 -> DPAD_LEFT DOWN+UP; taps resolve after the window to ENTER pair (1), Back (2), Ignore (3+) (`RingSurfaceInputPolicyTest`).
125. [EMU] Ring on a non-reader surface reaches the plugin as the same `/surface/input` events the touchpad produces (`SC:915-932`).
126. [EMU] Ring double tap on a surface forwards BACK DOWN to the plugin and applies `handleBackDown` (`SC:934-938`).
127. [EMU] Ring on a reader scrolls by viewport page, deduped at 150 ms (`SC:290-302`).
128. [JVM] Notice ring: a tap is claimed only if the notice expects input; directions only if it claims direction; single tap answers with ENTER, double dismisses with `USER`; an unclaimed ring key is a no-op while the notice owns the ring (`NoticeController:786-844`, `RBAS:250-252`).
129. [JVM] A notice that is replaced, updated to a new interaction identity, or closed during a pending ring tap resets the tap policy (`NoticeController:1052-1054`).
130. [JVM] Activity ring tap: the target activity (id, `startedOrder`, action) is captured at the first tap and the single-tap resolution fires only if it is still primary and the idle layer is still owned; double tap runs `GLOBAL_ACTION_BACK` (`ActivityPresentationPolicy:95-104`, `ActivityController:784-806`).
131. [DEVICE] Real R08 events arrive with a device name containing "R08" and keycodes 85/87/88.
132. [EMU] An emulated ring (`input keyevent 85/87/88`) has a defined route. **[decide]**: either the harness sends events with an R08 device name, or the generic pipeline maps 85/87/88 (`HEAD` treats them as no-ops in the launcher, F-15).
133. [DEVICE] A firmware touchpad tap yields contact 83 then ENTER within about 500 ms; a notice band is answered by the ENTER, never by the contact (`RBAS:314-319`).

### 7.8 Notice band

134. [JVM] `NoticeStateMachine`: stale `seq` dropped; a different surface id replaces and reports `replaced`; update ignored for non-owner, wrong instance, or empty result; `hide` seq-ordered; `close` and `expire` return TIMEOUT/USER (`NoticeStateMachineTest`).
135. [JVM] A band takes exactly one answer; the answered flag is set in the same call that reads the selection (`NoticeInteractionStateTest`).
136. [JVM] Expiry: `min(now + ttl, now + 90 s)`; an update restarts the clock unless engaged; the first page turn sets a 30 s inactivity expiry (`NoticeController:269-299`, `:339-350`, `:471-485`).
137. [JVM] Selection wraps in both directions; selection follows the action id across updates and falls back to the first (`NoticeController:104-124`).
138. [JVM] A band pages iff it has <= 1 actions and `pageCount > 1`; with 2+ actions directions select and the band never pages (`NoticeController:77-79`, `:444-485`).
139. [JVM] `noticeOwnsRingInput`: expects input, or claims direction, or backdrop; false while the camera overlay is active (`NoticeController:92-97`).
140. [JVM] Page capacity: 8..14 lines (image costs 5 lines, min 3) (`NoticeController:136-201`).
141. [JVM] A notice failing to send its answer is marked "Delivery not confirmed" and stays answered (`NoticeController:416-423`).
142. [EMU] Show animation: slide from `-bandHeight` plus fade over 280 ms; exit slide and fade 240 ms then the window is removed; a show during exit retargets without removing the window (`NoticeOverlayRenderer:239-269`).
143. [EMU] A `backdrop` notice draws an opaque black scrim across the display that fades with the band and blocks no input (`NoticeOverlayRenderer:277-291`).
144. [EMU] Band width is 92% of display width, top margin `12 dp + hudTopInset` (`InkCardPresentation:11-19`).
145. [JVM] Close reasons reported on `/notice/closed`: `replaced`, `user`, `timeout`, `owner`, `disconnect` (`NoticeController:970-974`, `:1115`).
146. [JVM] Notice wake: show with `wakeDisplay` requests a wake; a close may sleep the display unless launcher, surface, assistant episode, activity or camera overlay is up (`NoticeSleepPolicyTest`).
147. [EMU] Ink card morph: a matching owner band is faded and closed with `OWNER` when the ink card's first frame is drawn (`SurfaceHudView:666-737`).
148. [JVM] `noticeDismissMotion`, `noticeRenderMotion`, `noticeBackdropAlpha`, `noticeBandHeightCeiling` (`NoticeOverlayRendererTest`).

### 7.9 Pin

149. [JVM] `PinStateMachine`: stale `seq` dropped; hide `seq`-ordered; TTL expiry only for the matching `seq`; null TTL never expires (`PinController:27-65`, `PinControllerTest`).
150. [JVM] Pin content bounds: SMALL title 24, 2 lines of 28; MEDIUM 28, 3 lines of 32 (`S/PinSurfaceContract.kt:23-31`).
151. [EMU] A pin appears in the requested corner and its position changes without recreating the window (`PinOverlayRenderer:61-90`).
152. [EMU] Camera overlay hides the pin (`PinController:76-77`); see item 181.

### 7.10 Activity

153. [JVM] `ActivityStateMachine`: per-surface `seq` and a global clear watermark (a delayed start below the watermark is dropped); 3rd start evicts the least recently updated non-primary; `clearAll` removes only residents at or below its watermark (`ActivityStateMachineTest`).
154. [JVM] `selectActivityPresentation` truth table: camera -> HIDDEN; significant + budget -> FLARE; significant, no budget -> PULSE; non-significant in active surface or launcher -> PULSE; idle -> CHIP after 10 s or if never collapsing is off, else PANEL (`ActivityPresentationPolicyTest`).
155. [JVM] Flare budget 10 s per activity; urgent flare budget 60 s per activity, independent (`ActivityPresentationPolicy:36-48`, `ActivityController:264-282`).
156. [JVM] Primary selection: latest significant else oldest; non-primary presents CHIP/PULSE (`:113-127`, `ActivityController:304-318`).
157. [JVM] Corner allocation: pin corner reserved first, valid residents keep corners, newcomers take TL, TR, BL, BR (`:137-172`).
158. [JVM] `identity`: `surfaceId` must equal `<ownerPluginId>:local`; otherwise the message is rejected (`ActivityController:815-821`).
159. [JVM] `maxDurationMs` expires an activity and reports `/activity/closed` `max-duration`; eviction reports `replaced` (`ActivityController:594-596`, `:715-726`).
160. [EMU] Context changes (surface shown, launcher shown or hidden, camera) republish and change presentation without replaying a flare already processed (`ActivityOverlayRenderer:96-100`, `:132-158`).
161. [JVM] `alwaysExpanded` setting persists and is applied from `/system/hub/capabilities` (`ActivityController:400-414`, `GlassesHub:1368-1370`).
162. [EMU] Activity window exists only while at least one item is visible; the camera hides all items and removes the window immediately (`ActivityOverlayRenderer:102-106`).
163. [JVM] Activity key/ring claims per items 116 and 117.

### 7.11 Status badge, pointer, insets

164. [JVM] `StatusBadgeReserve`: keyed by signature (weather floor 64 dp, clock-only floor 30 dp), grows only, ignores a null or out-of-range observation, persists (`StatusBadgeReserveTest`).
165. [JVM] `StatusBadgeGeometry.originFor`: `leftInset = reserve`, `y = rowCentre - height/2` (`StatusBadgeGeometry`).
166. [DEVICE] Badge shows only while the ROM launcher is the top window, and hides when a Nexus overlay or another app is on top (`StatusBadgeOverlayRenderer:159-246`).
167. [DEVICE] Badge sits beside the weather without colliding with R08 Access Bridge's ring chip (`StatusBadgeOverlayRenderer` KDoc).
168. [JVM] `PhoneBatteryController`: stale `seq` dropped; equal reading not re-notified; no expiry (`PhoneBatteryController:44-58`).
169. [JVM] `HudTopInset`: sanitize to 0..120; `effective = auto ? autoDp : manualDp`; `autoDp` updates only past 2 dp hysteresis from `round((rowCentre - 364)/density)` (`HudTopInsetTest`).
170. [EMU] A top-inset change re-positions launcher content, surface host chrome, ink card, notice band, pin and activity top corners without recreating windows (`applyHudTopInset` in each renderer).
171. [JVM] `RemotePointerGeometry.toPixels` clamps a normalized position to `[radius, size - radius]`, rejects non-finite input (`RemotePointerGeometryTest`).
172. [EMU] Pointer: `show`/`move`/`move_end` draw the cursor; `click` dispatches a 40 ms tap gesture, `long_press` a 550 ms one; `hide`, 8 s idle, and link loss remove it (`RemotePointerController:83-187`).
173. [JVM] One pending pointer gesture at a time; a second is rejected `ACTION_UNAVAILABLE`; cancel on service destroy reports `SERVICE_UNAVAILABLE` (`RemotePointerGestureCompletionGateTest`).
174. [JVM] `/core/native-apps`: list excludes the hub, dedupes by package, sorts by label then package, caps 64, trims labels to 96; launch rejects the hub package `NOT_ALLOWED` and unknown packages `NOT_LAUNCHABLE` (`NativeAppsControllerTest`).
175. [JVM] `/core/navigation` maps previous/next to focus backward/forward, select to click of the focused node or ancestor, back to `GLOBAL_ACTION_BACK`, and directions fall back to sequential focus (`RemoteNavigationPolicyTest`).

### 7.12 MainActivity, onboarding, camera, misc

176. [EMU] Setup incomplete: `MainActivity` shows the onboarding screen for the evaluated stage (`ENABLE_ACCESSIBILITY`, `READY_FOR_WIRELESS`, `RUNNING`, `WAITING_FOR_WIFI`, `MANUAL_REQUIRED`, `FAILED`, `UNSUPPORTED`); D-pad is swallowed, ENTER/CENTER perform the stage action (`MA:296-382`, `:128-150`).
177. [JVM] `SelfArmOnboardingStateMachine.evaluate` maps snapshots to stages/actions (`SelfArmOnboardingStateMachineTest`).
178. [DEVICE] `SetupEntryActivity` draws nothing, opens the Accessibility settings or `MainActivity`, and finishes immediately (`SetupEntryActivity:16-52`).
179. [EMU] Setup completion: a 1.6 s confirmation ("done") shows once per session id, then the launcher (`MA:385-405`).
180. [EMU] Camera activity keeps the screen on, is portrait, and BACK finishes it; ENTER toggles freeze, swipe zooms, with 400 ms debounce (`CameraActivity:76-122`, `CameraInputRouterTest`).
181. [DEVICE] While the camera is up, the main-process notice and pin honour the camera-overlay flag (currently only activities do, F-16). **[decide]**
182. [DEVICE] The `NexusRemoteInputMethodService` draws nothing and only keeps an `InputConnection`; remote input sessions open and close with focus (`NexusRemoteInputMethodService:16-59`).
183. [EMU] A native assistant dismiss request arms a 3 s back burst that only fires when the active window is a native assistant package (`RBAS:335-370`).
184. [DEVICE] With no Nexus owner for a key, BACK is passed to the ROM launcher; the ROM sleeps the display on it (F-12). **[decided 2026-09-30]** the 4 s stash guard is not part of parity: keys in `Hidden` belong to the ROM and Nexus never swallows them.
185. [DEVICE] The five-second display timeout: the launcher, surface overlay and notice hold the screen with `FLAG_KEEP_SCREEN_ON`; SurfaceActivity and CameraActivity too (`LOR:95`, `SOR:45`, `NOR:39-42`, `SurfaceActivity:20`, `CameraActivity:78`).
186. [JVM] `DisplayStandbyWatchdog` inputs: surface, notice, activity, camera session, launcher overlay shown, `MainActivity` interactive flow, top window, setup flows, TTS, media sync are all read from UI state; the new host must expose equivalents (`DisplayStandbyWatchdog:135-155`).

Item count: 186 (104 [JVM], 69 [EMU], 13 [DEVICE]). Items marked "(decision needed)" or **[decide]**
record a `HEAD` behavior that is a bug or an open product question; they need a decision before
they become tests.

## Appendix A: timing and threshold constants

| Constant | Value | Source |
|---|---|---|
| Triple-tap window / suppression | 600 ms / 800 ms | `G/TouchpadGestureDetectors.kt:70-71` |
| Tap-expiry runnable | 601 ms | `G/RokidBusAccessibilityService.kt:209` |
| Ring tap window | 350 ms (+1) | `G/RingTapPolicy.kt:38` |
| D-pad pair dedupe | 150 ms | `G/TouchpadGestureDetectors.kt:120` |
| Ring handoff timeout | 10 000 ms | `G/RingFocusCoordinator.kt:77` |
| Surface BACK failsafe | 1 500 ms | `G/SurfaceController.kt:28` |
| Ink first-frame timeout | 500 ms | `G/SurfaceHudView.kt:663`, `:802` |
| Inline (see-through) fallback | 1 500 ms | `G/SurfaceHudView.kt:1330` |
| Status row ticker / timed / media tick | 30 s / 100 ms / 500 ms | `G/SurfaceHudView.kt:1326-1328` |
| Setup confirmation | 1 600 ms | `G/MainActivity.kt:661` |
| Notice TTL default / min / max / hard | 8 / 2 / 45 / 90 s | `S/NoticeSurfaceContract.kt:200-214` |
| Notice engaged inactivity | 30 s | `G/NoticeController.kt:544` |
| Notice wake settle retry / timeout | 75 ms / 2 s | `G/NoticeController.kt:1320-1321` |
| Activity collapse / flare interval / urgent interval | 10 s / 10 s / 60 s | `G/ActivityController.kt:395-396`, `G/ActivityPresentationPolicy.kt:36` |
| Max active activities | 2 | `S/ActivitySurfaceContract.kt:145` |
| Motion: micro / standard / exit / hold | 180 / 280 / 240 / 3 500 ms | `G/HudMotion.kt:28-37` |
| Structural token (tile tween) | 320 ms (stash 220) | `B/RokidHudTokens.kt:86` |
| Grid columns / unit / gap / dim alpha | 4 / 96 dp / 8 dp / 0.35 | `G/GridLauncherView.kt:104-105`, `:248` |
| Tile stale threshold | 10 min | `G/TileCache.kt:64` |
| Tile publish rate | 5 per plugin per 60 s | `G/TileRateLimiter.kt:35-36` |
| Pointer idle / tap / long press | 8 s / 40 ms / 550 ms | `G/RemotePointerController.kt:185-187` |
| Status badge settle | 150 ms | `G/StatusBadgeOverlayRenderer.kt:98` |
| Standby watchdog cadence | 45 s | `G/DisplayStandbyWatchdog.kt:213` |
| Native assistant dismiss arm / debounce | 3 s / 120 ms (bursts at 0,120,280,600,1000,1800 ms) | `G/RokidBusAccessibilityService.kt:1195-1198` |
| HUD top inset range | 0..120 dp, hysteresis 2 dp, top-mode row centre 364 px | `S/PhoneHubCapabilitiesContract.kt:17-19`, `G/HudTopInset.kt:18-19` |
| Camera input debounce | 400 ms | `G/CameraInputRouter.kt:114-115` |
| Stash: BACK sleep guard | 4 000 ms | stash `RokidBusAccessibilityService.kt` |

## Appendix B: existing test anchors

Existing tests under `glasses-hub/src/test/java/com/anezium/rokidbus/glasses/` that already pin part of
this behavior and should be kept or replaced 1:1: `RingFocusCoordinatorTest`, `LauncherReturnCoordinatorTest`,
`RingTapPolicyTest`, `RingSurfaceInputPolicyTest`, `TouchpadGestureDetectorsTest`,
`NoticeKeyInputRouterTest`, `NoticeTouchpadInputPolicyTest`, `NoticeStateMachineTest`,
`NoticeInteractionStateTest`, `NoticeOverlayRendererTest`, `NoticeSleepPolicyTest`,
`NoticeComposeMirrorTest`, `NoticeImageLifecycleTest`, `PinControllerTest`, `ActivityStateMachineTest`,
`ActivityPresentationPolicyTest`, `ActivityPresentationSettingsTest`, `ActivityPrimaryFitTest`,
`GridLauncherViewTest`, `GridLauncherScreenshotTest`, `GridColorLiteralLintTest`, `HudModeStoreTest`,
`TileCacheTest`, `TileControllerTest`, `TileLayoutStoreTest`, `TileLayoutIntegrationTest`,
`TileRateLimiterTest`, `TileExpansionAnimatorTest`, `LiveTileViewTest`, `DownscaleBlurTest`,
`ReducedMotionTest`, `SurfaceOrderingCoordinatorTest`, `SurfaceListViewportTest`,
`SurfaceHudSeeThroughTest`, `ReaderSurfaceModelsTest`, `ImageSurfaceStateTest`, `InkCardPresentationTest`,
`InkCardMorphStateTest`, `InkPresentationGateTest`, `StatusBadgeGeometryTest`, `StatusBadgeReserveTest`,
`HudTopInsetTest`, `HudSpringTest`, `RemotePointer*Test`, `RemoteNavigationPolicyTest`,
`NativeAppsControllerTest`, `CameraInputRouterTest`, `CameraOverlayVisibilityBridgeTest`,
`DisplayStandbyPolicyTest`, `DisplayWakePolicyTest`, `AccessibilityWindowRootSelectionPolicyTest`.
There is no test for `LauncherOverlayRenderer`, `SurfaceController`, `RokidBusAccessibilityService.onKeyEvent`,
`SurfaceOverlayRenderer`, `HudOverlayStack`, or `MainActivity`; the owner chain and z-order in sections
1.3 and 3 are currently untested.

## Appendix C: open questions and unverified claims

1. F-11: which thread `LAUNCHER_LIST` notifications run on for the SPP transport, and whether
   `runCatching` swallows a `CalledFromWrongThreadException` in practice.
2. F-15: the input device name reported for `adb shell input keyevent` on the Cuttlefish build used
   by `tools/emulator`.
3. F-16: whether notices and pins really draw over `CameraActivity` on device.
4. F-9: whether BACK during the 320 ms tile tween sends `/launcher/open` on device (animator
   lifecycle after view removal).
5. Display size: 480x352 (task, tokens, emulator) versus 480x400 (roadmap "physical") versus the
   480x640 ROM-launcher measurements in `StatusBadgeGeometry`; the grid overflow figure (408 dp vs 320 dp)
   assumes density 1.5 as configured for the emulator.
6. Whether the app-icon path should open the overlay launcher (stash) or keep `MainActivity`'s own list;
   this decides item 73.
7. Whether the launcher should stay visible during hand-off (stash) or close on tap (`HEAD`); this
   decides items 37 and 39.
