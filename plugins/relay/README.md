# Relay

Relay forwards directly repliable Android notifications to a Nexus notice band,
keeps a menu-launched in-memory inbox, and sends an explicitly confirmed reply
through the source notification's `RemoteInput` action — dictated, or typed.
A *Type* chip appears about a second into dictation (or *Reply by typing* skips
dictation altogether), and the reply is typed inside the notice band itself on
a glasses hub from 1.4.13, from a bonded keyboard or the phone's Keyboard &
remote screen, which comes forward on its own; an older hub opens a separate
editable card instead. Typing needs a glasses hub that announces the
editable-surface bit; without it Relay falls back to dictation. A notification arriving while the wearer is dictating, typing, or
reviewing a transcript is held and shown once that exchange resolves, and the
band's own timeout is kept alive for as long as the input lasts.

Inbox conversations open as native reader documents. When the source
notification adds a message to the conversation already being read, Relay
updates that document in place without interrupting an active reply flow.

Notification content, sender names, images, and speech text are process-memory
only. The settings screen stores only feature flags and the thread message
limit.

## Grid tile

With the optional `widget_tile` grant, Relay publishes a list tile for the
glasses grid in every size from `1x1` to `3x3`: the inbox's conversations,
newest first, each with the sender, the app, the newest message (prefixed with
its speaker in a group) and its age, with the sender's initials beside it,
under an `N new` summary. Up to six are sent; the rest count as "+N more". With
nothing in the inbox the tile reads "No new messages".

The tile reads the inbox only between `onNexusTileActive(true)` and
`onNexusTileActive(false)`, the hub's tile lease. Inside it, Relay republishes
on a capture, a removal, a sent reply, a cleared inbox, a hide switch and each
hub refresh, at most once every 12 seconds so the glasses' tile rate limit
never drops the newest state.

Conversations whose text Android redacted never reach the tile, not even as a
sender. While *Hide message text on the glasses* or *Hide previews in the
inbox* is on, every message on the tile reads "New message".

## One bus registration

The notice band does not open a client of its own: it binds `RelayPluginService`
for as long as a band is up and talks through that service's client, which
forwards the band's notice, typed-reply, link and registration callbacks. The
hub holds the same service for the inbox and the tile lease, so Relay has a
single registration on the hub however those overlap, and the hub's lifecycle
deliveries (open, close, tile lease, refresh) always find it.
