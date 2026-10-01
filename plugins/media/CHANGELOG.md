# Changelog — Media Deck

## 1.1.0

- Live grid tile (optional `widget_tile` capability, all seven tile sizes): the
  playing track's title, artist, album, source app, position and cover, published
  on a track change, play/pause, a seek or a late cover, and "Nothing playing"
  when no media session is active. The media session is watched only while the
  hub's tile lease is active; the plugin stays dormant otherwise.
- The layout editor previews the tile with a sample track until a real one is
  published.

## 1.0.2

- Android 11 support: the plugin now installs on Android 11 (API 30) phones.

## 1.0.1

- The app icon in Android settings and installer dialogs is now the plugin's own glyph on the Nexus dark background (adaptive icon), instead of a washed-out or generic mark.

## 1.0.0 — unreleased

- First release as a headless plugin APK (previously an in-hub built-in).
- Typed media surface with playback anchor and 96×96 one-bit artwork.
- Transport controls from the glasses: tap play/pause, swipe previous/next.
- Own notification listener ("Nexus Media Deck") and kit settings screen with
  notification-access onboarding and uninstall.
