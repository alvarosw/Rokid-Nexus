# Changelog — Feeds

## 1.1.0

- Live grid tile (optional `widget_tile` capability, all seven tile sizes): up
  to six posts of the default timeline with author, handle, text, media marker
  and age, and a "N new" summary. Fetched once when the hub's tile lease starts
  and once per hub refresh, never on a timer of the plugin's own. X (WebView)
  is never used for the tile; with that default the tile reads Bluesky.
- The layout editor previews the tile with sample posts until a real one is
  published.

## 1.0.2

- Android 11 support: the plugin now installs on Android 11 (API 30) phones.

## 1.0.1

- The app icon in Android settings and installer dialogs is now the plugin's own glyph on the Nexus dark background (adaptive icon), instead of a washed-out or generic mark.

## 1.0.0 — unreleased

- First release as a headless plugin APK.
- Bluesky and X timelines on the HUD (X via account session or WebView
  capture).
- In-plugin X sign-in; targeted cookie expiry on disconnect.
- Kit settings screen with per-source configuration and uninstall.
