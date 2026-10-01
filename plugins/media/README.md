# Media Deck

Media Deck is the Rokid Nexus universal now-playing plugin. Playback remains owned by
the active Android media app; the plugin reads its `MediaSession`, sends a declarative
HUD surface, and forwards only play/pause/previous/next transport commands.

## Module boundary

- No CXR, Bluetooth, glasses SDK, phone-hub implementation, microphone, or network
  dependency.
- The plugin owns its notification-listener component and its `MediaDeckSettingsActivity`
  on the shared NexusUi kit.
- `MediaDeckPluginService` registers the headless APK through the external Nexus plugin
  SDK and adapts the isolated `MediaDeckRuntime` onto typed surface sessions.
- The glasses hub owns rendering through the versioned `media` surface documented in
  `../BUSSPEC.md`; the plugin never installs or launches glasses-side code.

## HUD contract

- Swipe back/forward: previous/next media item.
- Tap/center: play or pause.
- Back: close Media Deck and return to the underlying glasses app.
- Position advances locally from an anchor; the phone does not poll or stream
  progress updates.
- With the image renderer capability, artwork is aspect-fit to a maximum 256 px edge,
  JPEG-encoded under 64 KiB, and sent in the media envelope's SPP binary body.
- Without that capability, artwork keeps the original center-cropped,
  contrast-normalized, Floyd-Steinberg-dithered 96 x 96 `mono1` payload.

## Grid tile

With the optional `widget_tile` grant, Media Deck publishes a `Music` tile for the
glasses grid in every size from `1x1` to `3x3`: title, artist, album, source app,
the playback position anchor with the duration, and the cover (the same 256 px JPEG
as the image surface, sent once per `artworkKey`, which changes with the track and
the image). It publishes on a track change, play/pause, a seek of more than 1.25 s,
or a cover that arrives late, never per second; the glasses advance the position
themselves. With no active media session the tile reads "Nothing playing", and
without notification access it asks for it.

The media session is watched for the tile only between `onNexusTileActive(true)` and
`onNexusTileActive(false)`, the hub's tile lease. Outside the lease and an open
surface the plugin is dormant. The open surface is unchanged and independent of the
tile.

Media titles and artwork are user data. They may be rendered on the requested HUD but
must not be included in production logs.
