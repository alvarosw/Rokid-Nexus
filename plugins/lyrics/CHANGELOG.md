# Changelog — Lyrics

## 1.1.0

- Live grid tile (optional `widget_tile` capability, all seven tile sizes):
  synced lyrics as a window of timed lines that the glasses advance with the
  playback position, unsynced lyrics from the top, and the track title when no
  lyrics are found. Published per track, seek, play/pause, or when playback
  nears the end of the published window, never per line.
- The media session is followed for the tile only while the hub's tile lease
  is active; closing the surface no longer stops it while the lease holds.
- The layout editor previews the tile with sample lyrics until a real one is
  published.

## 1.0.3

- Refresh the lightweight playback anchor at each timed-lyrics line boundary
  so transport latency cannot leave the glasses highlighting one line behind.

## 1.0.2

- Android 11 support: the plugin now installs on Android 11 (API 30) phones.

## 1.0.1

- The app icon in Android settings and installer dialogs is now the plugin's own glyph on the Nexus dark background (adaptive icon), instead of a washed-out or generic mark.

## 1.0.0 — unreleased

- First release as a headless plugin APK (previously an in-hub built-in).
- Time-synced lyrics on the HUD via the SDK timed-lines surface.
- Providers: Spotify synced lyrics (in-app sign-in), Musixmatch (optional),
  LrcLib, Netease.
- Auto-open on playback changes, toggleable in settings.
- Own notification listener ("Nexus Lyrics") and kit settings screen with
  notification-access onboarding and uninstall.
