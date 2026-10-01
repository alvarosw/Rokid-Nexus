# Lyrics

Lyrics shows live, time-synced lyrics on the glasses for whatever is playing
on the phone. Track detection uses the notification-listener grant to read
active media sessions; lyrics come from Spotify's own synced lyrics (when
signed in), Musixmatch (optional), Netease, and LrcLib, tried in that order by
the provider chain.

## How it works

- `MediaNotificationListenerService` is an empty `NotificationListenerService`
  that exists only to hold the notification-access grant; the actual media
  session monitoring starts in `onNexusOpen` or with the tile lease, and stops
  once neither the surface nor the lease needs it — the plugin stays dormant
  otherwise.
- `LyricsRuntime` drives the HUD through the SDK's timed-lines surface:
  the full line set is sent once per track, then small playback anchors on
  state, seek, and active-line changes keep the glasses in sync.
- `LyricsPluginService` is the thin `NexusPluginService` adapter;
  `LyricsSettingsActivity` (NexusUi kit) handles notification access, the
  Spotify sign-in (`SpotifyLoginActivity`, cookie stored encrypted on the
  phone), Musixmatch credentials, and uninstall.

## Grid tile

With the optional `widget_tile` grant, Lyrics publishes a `Lines` tile for the
glasses grid in every size from `1x1` to `3x3`. `LyricsTileRuntime` follows the
same runtime graph as the surface, so lyrics come through the same provider
chain, but only between `onNexusTileActive(true)` and `onNexusTileActive(false)`,
the hub's tile lease.

- Synced lyrics: a window of up to nine lines around the current one, each with
  its start time, plus the playback position, duration and playing flag. The
  glasses move the current line on their own; the tile is published again on a
  track change, a seek, play/pause, and when playback nears the end of the
  window, never per line.
- Unsynced lyrics: the first nine lines, without start times, from the top.
- No lyrics: a generic tile with the track title. Nothing playing: "Nothing
  playing".

## Requirements

- Notification access for "Nexus Lyrics" (prompted from the settings screen).
- Optional: a Spotify account for Spotify's own synced lyrics.
