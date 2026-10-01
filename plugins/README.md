# Nexus plugins

Every Rokid Nexus plugin is a **headless phone APK**: it installs like an app,
has no launcher icon, renders its settings with the shared NexusUi kit, and
lives entirely inside Nexus — launched from the glasses launcher, configured
from the phone hub, removable from its own settings screen. How to build one
is documented in [docs/PLUGINS.md](../docs/PLUGINS.md) (structure + design
kit) and [docs/PLUGIN_SDK.md](../docs/PLUGIN_SDK.md) (SDK reference).

Each plugin owns one folder here with its module sources, a `README.md`
explaining what it does and how it is built, and a `CHANGELOG.md` tracking its
releases. The Gradle project names keep the historical `:plugin-<id>` form
(mapped in `settings.gradle.kts`).

## Catalogue

| Plugin | Id | What it does |
|---|---|---|
| [Assistant](assistant/) | `assistant` | Voice questions answered on the band, aloud, or as generated native Ink pages, with camera vision, reminders, notes, and phone-calendar create/list/delete tools |
| [Relay](relay/) | `relay` | Phone notifications on the HUD, answered by voice, with an inbox for the rest |
| [Navigation](nav/) | `nav` | Google Maps and Citymapper guidance kept on the glasses as one live activity |
| [Feeds](../plugin-feeds/) | `feeds` | Bluesky and X timelines on the HUD |
| [Lens](lens/) | `lens` | Live and frozen camera OCR with phone-side translation |
| [Transit](transit/) | `transit` | Nearby stops, departures, and favourites |
| [Lyrics](lyrics/) | `lyrics` | Live synced lyrics for whatever is playing |
| [Media Deck](media/) | `media` | Universal now-playing surface with transport controls |
| [Photos Sync](photosync/) | `photosync` | Copies glasses captures into the phone gallery on their own |
| [Wireless ADB](wireless-adb/) | `wirelessadb` | Enables and pairs the glasses' real ADB-over-Wi-Fi transport under an explicit high-risk grant |
| [Tasker](tasker/) | `tasker` | Lists named Tasker tasks on the HUD and runs the selected automation on the phone |
| [Sample](sample/) | `hello` | Minimal copyable reference plugin |

Feeds lives at the repository root as `plugin-feeds/`; everything else about
it follows the same layout and rules.

## Releases

Plugins release from this repository as GitHub releases with **namespaced
tags**, one stream per plugin, separate from the app's `v*` releases:

- Tag `lyrics-v1.1.0` → release `Lyrics 1.1.0` carrying
  `lyrics-phone-release.apk`, created with `--latest=false` so the app's
  `releases/latest` pointer is never disturbed.
- The release notes are the matching section of the plugin's `CHANGELOG.md`.
- The RokidBrew registry ingests the release (`--kind nexus-plugin`) and the
  Nexus Store serves it from `dist/nexus-plugins.v1.json`.
- Before installing, the phone verifies the downloaded APK against the
  registry entry's `sha256` and pins the signing certificate
  (`signerSha256`); a mismatch aborts the install.
- The phone home screen checks the registry for newer versions of installed
  plugins (throttled) and shows an `UPDATE` badge per plugin plus an update
  count on the Store row.

Before pushing a plugin tag, set that module's `versionName` and add the
matching `## <version>` changelog section; release CI rejects either mismatch.

### Fork registry

This fork publishes its own builds of some plugins, signed with the fork key
(signer SHA-256
`78a1be1045ba3b2614baa46e306a353a8a6bc3e5d07ba5d587490931d5f56d60`), through
its own registry at `dist/nexus-plugins.v1.json` on `main`. The phone hub reads
it before the upstream RokidBrew registry (`FORK_REGISTRY_URL` and
`UPSTREAM_REGISTRY_URL` in `phone-hub/build.gradle.kts`):

- A plugin listed by the fork comes only from the fork's entry; every other
  plugin comes from upstream. Matching is by plugin id and package name.
- Each registry has its own cache. If the fork registry cannot be fetched, its
  last cached copy is used; if it has never loaded, the Store shows upstream
  alone. An unreachable upstream never hides the fork's plugins.
- Android cannot update an app across signing keys, so an update is offered
  only when the registry entry's `signerSha256` matches the installed copy. A
  plugin installed from upstream shows a one-time *Switch* in the Store: the
  user confirms, the system uninstaller removes the old copy (with its data),
  the fork build is installed, and the plugin's access is approved again.

Publishing a plugin build to the fork registry:

1. Push the plugin tag (`media-v1.0.3`); the release workflow above builds and
   signs the APK with the fork key and creates the GitHub release.
2. The workflow's `update-registry` job then runs
   `tools/registry/upsert-plugin.py` on `main` and opens a pull request with
   the updated `dist/nexus-plugins.v1.json`. GitHub only lets the job open it
   when *Settings → Actions → General → Allow GitHub Actions to create and
   approve pull requests* is enabled; otherwise run the generator by hand:

   ```
   ANDROID_HOME=/path/to/sdk tools/registry/upsert-plugin.py --tag media-v1.0.3
   ```

3. Review and merge the pull request. The Store picks the entry up on its next
   registry refresh.

The generator downloads the release APK, records its `sha256` and size, reads
the package, version and single signer with `aapt2`/`apksigner` from the SDK
build-tools, and the Nexus meta-data (`ID`, `API_VERSION`, `CAPABILITIES`,
`LAUNCHABLE`, `SETTINGS_ACTIVITY`). It rejects an APK whose version or plugin id
does not match the tag. Listing text, icon and screenshots are copied from the
upstream registry's entry for the same id, or taken from the plugin's README
when upstream does not list it; `author` is the repository owner and
`minHostVersionCode` is carried over unless `--min-host-version-code` is
passed. Release notes are the GitHub release body. Re-running it for an older
tag adds that release's notes without moving the served artifact back. The
file is written sorted by plugin id with a stable field order.
