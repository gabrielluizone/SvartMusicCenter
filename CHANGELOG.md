# Changelog

## 1.12

Wear OS modernization initiative — bringing the watch app up to current
platform standards (foundation, system integration, native surfaces) and
starting the move to Jetpack Compose. Full roadmap in
`docs/wear-modernization-plan.md`.

### Phase 0 — Foundation (wear)

- Target SDK bumped 30 → 34, with the platform changes that become enforced
  at that target handled so behavior is unchanged on current devices:
  explicit `android:exported` on the launcher activity + Data Layer listener
  services (Android 12), and a `mediaPlayback` `foregroundServiceType` +
  permission on `WatchMusicService` (Android 14).
- Re-enabled the `ExpiredTargetSdkVersion` lint (its suppression is now
  obsolete).
- Migrated the deprecated `AmbientModeSupport` to `AmbientLifecycleObserver`.
- Removed the dead legacy `support.wearable` `ConfirmationActivity` manifest
  entry (the androidx one was already in use).
- Declares + requests `POST_NOTIFICATIONS` (Android 13) so the foreground
  notification keeps showing.

### Phase 1 — System media integration (wear)

- New watch-side **MediaSession proxy** (`WatchMediaSession`): mirrors the
  phone's now-playing state (title/artist/art/position/playback + remote
  volume) and forwards transport controls back to the phone. The phone's
  playback now appears in and is controllable from the system **Media
  Controls** app and the Wear OS media surfaces — no app UI rewrite required.
- MediaSession flags and `setSessionActivity` now set so the Wear OS recents
  screen shows the currently playing track name under the app name.
- New watch→phone skip-next / skip-previous command channel (previously only
  toggle/seek/volume/quick-action existed).
- `WatchMusicService`'s foreground notification is now a MediaStyle
  notification bound to the session.

### Performance (wear)

- Cut control latency: every watch→phone command was re-resolving the phone
  node via a `getConnectedNodes()` round-trip on each press. The node id is
  now cached and reused, so button presses reach the phone noticeably faster.

### Queue redesign (wear)

- Introduced **Jetpack Compose for Wear OS** into the module (first Compose
  here; pilot for the broader UI modernization).
- Fully replaced the legacy `WearableDrawerLayout` queue with a new
  **`QueueActivity`** hosting a Compose `QueueScreen`:
  - `ScalingLazyColumn` of dark glass pills; the now-playing entry is
    highlighted with the album's lightened (pastel) accent colour.
  - Animated three-bar **equalizer** next to the playing track.
  - **Marquee** scrolling for long titles inside the pill.
  - Clock rendered as **`CurvedText`** along the top bezel, matching the Wear
    OS style; it fades out as the user scrolls down.
  - Thin curved **scroll indicator** on the right bezel — fixed thumb size
    (no erratic resize with the rotary crown), auto-hides 1.2 s after
    scrolling stops.
  - **Swipe-to-dismiss** closes only the queue (reveals the now-playing
    screen underneath); the system window animation is suppressed so the
    Compose transition plays cleanly without a double-close flash.
  - Google Sans used throughout to match the rest of the watch UI.
- Artist name on the now-playing screen and in the quick-actions panel now
  uses a HSL-lightened version of the album accent (dark colours, e.g. deep
  purple, become a readable pastel; black text always used in the queue).

### Bug fixes (mobile + wear)

- **Shuffle button always appeared active**: apps that never set their shuffle
  mode report `SHUFFLE_MODE_INVALID (-1)` which is not `SHUFFLE_MODE_NONE
  (0)`, so the comparison wrongly treated them as "shuffling". Fixed by
  checking for the explicitly-ON states (`ALL` / `GROUP`) instead.
- **Repeat button skipped "repeat one" on some apps** (e.g. Retro Music):
  `REPEAT_MODE_GROUP` is semantically "repeat all" but was falling through to
  the `else → NONE` branch in `RepeatAction`, bypassing repeat-one. Fixed.
- **Album art missing on Retro Music and other apps**: many apps on Android
  10+ provide art as a `content://` URI rather than a raw `Bitmap` to reduce
  memory usage. Added URI fallback (`ALBUM_ART_URI` / `ART_URI` /
  `DISPLAY_ICON_URI`) with synchronous `ContentResolver` loading (network
  URIs are skipped to avoid blocking the main thread).
- **Like / favourite button not reflecting state on watch after toggling**:
  some apps don't immediately re-publish their playback state after handling a
  custom like action. A forced re-read of the state is now scheduled 500 ms
  after every like action so the watch button updates even in that case.

## 1.11

Dark "glass/acrylic" redesign of the watch UI, plus new playback features.

### Visual redesign (wear)

- New typeface (Google Sans) applied across the watch UI.
- Dark, minimalist "acrylic/glass" visual style replacing the old flat
  Material look (new `glass_card_background`, `glass_circle_background`,
  `queue_pill_background` drawables, shared glass color tokens).
- Redesigned circular volume control: left-edge vertical arc matching the
  stock Wear OS look, thicker stroke to match the new outline icons.
- New outline icons for volume up/down and the like button, redrawn as
  simple stroked shapes instead of outlining the old filled icon paths
  (which produced a messy/illegible result).
- Ambient (always-on display) mode improvements: blurred album art behind
  the clock instead of a flat dim, no black vignette, artist name shown in
  plain bold (no outline effect) while the title keeps its outlined look.
- Smart shrink-to-fit text sizing for long titles/artists (word-aware,
  falls back to marquee only when a title genuinely can't fit).
- Notification popup and queue/history list restyled to the new glass look.

### Seek bar

- New circular drag-to-seek progress bar around the now-playing screen,
  with a live time-remaining overlay while dragging.
- Position is interpolated locally between updates so the ring moves
  smoothly without spamming the phone connection.

### Like / shuffle / repeat

- New "Like" action: looks for a like/favorite custom action exposed by
  the currently playing app's media session (works with apps like YouTube
  Music and Retro Music that expose one).
- New "Shuffle" and "Repeat" actions, reading/writing real shuffle and
  repeat-mode state through the AndroidX media-compat layer (the bare
  framework `MediaController` API has no concept of either).
- Real shuffle/repeat state is now synced from the phone to the watch and
  reflected live on the quick-actions panel below.

### Quick-actions panel

- Double-tapping the center play/pause button opens a new panel with
  Like / Shuffle / Repeat buttons plus an "Up Next" shortcut into the
  queue - matching the stock Wear OS player's quick panel. Single-tap
  still toggles play/pause as before.
- Shows the current track's title/artist above the buttons.
- Shuffle/repeat buttons highlight with a color pulled from the album
  art when active; all three buttons flash that color on press.
- "Up Next" opens the real queue (regardless of the swipe-up preference)
  and previews the next track's name when a real queue is available.

### Queue / playback history

- New local play-history fallback: when the playing app doesn't expose a
  real skippable queue (common on Android 10+), the watch now shows a
  list of recently played tracks instead of an unhelpful error.
- Queue/history rows redesigned to match the stock Wear OS queue look:
  no album art thumbnails, title + artist on separate single (non-
  wrapping) lines, pill-shaped rows.
- Removed the old per-item dimming and circular curving effect from this
  list - it was designed for the old single-line icon rows and looked
  dated and "bent" against the new taller pill rows.

### Other fixes found along the way

- Fixed `OpenPlaylistAction` being mis-bound to the wrong (no-op) handler.
- Fixed the seek bar freezing/snapping back during a drag (a leftover
  position animator kept running underneath the touch).
- Fixed the seek bar losing an in-progress drag whenever the finger
  passed near a quadrant icon.
- Fixed cross-device position drift by converting the phone's
  `elapsedRealtime`-based playback position to a wall-clock timestamp
  before sending it to the watch.
- Fixed center-tap play/pause toggling losing touch events to the
  quadrant layer underneath it once double-tap detection was added.

## Earlier versions

See the GitHub release history for changes before 1.11.
