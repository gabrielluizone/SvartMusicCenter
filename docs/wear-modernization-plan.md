# Wear OS Modernization Plan

A phased roadmap for bringing the `wear/` module up to current Wear OS standards —
design, navigation, system integration, and native platform surfaces (Tiles,
Complications, system media) — without discarding recent work or breaking the
app's carefully-tuned input model.

> Status: planning. Nothing here is built yet. Each phase is independently
> shippable.
>
> **Decisions confirmed (2026-06-23):** target SDK **34**; Compose UI rewrite
> (Phase 3) is an **optional long-term goal, deferred** — the priority is
> architecture, system integration, navigation, and native features, *not*
> rewriting the UI for its own sake. Phase ordering reflects the user's
> preferred roadmap: Foundation → MediaSession/system integration → Tiles &
> Complications → in-UI navigation/UX → reassess Compose.

---

## 1. Current state (grounded assessment)

**Toolchain — already modern, no upgrade needed to start:**
- Kotlin `2.2.21`, AGP `8.13.1`, Java 21, `compileSdk 36`. Compose for Wear OS,
  Tiles, and Complications all drop in without toolchain changes.

**What makes the app feel like a single-screen relic:**
- **View-based UI, no Compose.** The entire watch experience is one
  `MainActivity` (`launchMode="singleInstance"`) inflating a single ~19 KB
  [activity_main.xml](../wear/src/main/res/layout/activity_main.xml), with
  fragments hosted in a **deprecated** `WearableDrawerLayout`.
- **`targetSdkVersion 30`** (Wear OS 2 era) — `wear/build.gradle:13`. The
  `ExpiredTargetSdkVersion` lint is explicitly disabled. Running in compat mode
  forfeits modern platform behaviors and the system media surfaces.
- **Deprecated APIs in active use:** `WearableDrawerLayout`,
  `AmbientModeSupport` (→ `AmbientLifecycleObserver`), and the legacy
  `com.google.android.support:wearable` / `android.support.wearable.*` stack
  (`ConfirmationActivity`, etc.) sitting alongside `androidx.wear`.
- **No glanceable surfaces.** No Tiles, no Complications, no watch-face
  integration. The launcher icon → the one activity is the *only* entry point.
- **`standalone = false`** — inherently a phone companion (correct for this app),
  but the connection/onboarding UX is from the same era.

**What is genuinely good and must be preserved:**
- The **bespoke input layer**: `StemButtonsManager` (physical stem buttons, with
  the ambient phantom-click workaround), `FourWayTouchLayout` quadrant gestures,
  rotary-crown handling, center-tap / double-tap interactions.
- The **recent v1.11 "glass" redesign**: smart shrink-to-fit text sizing,
  circular drag-to-seek bar, ambient blurred album art, like/shuffle/repeat
  quick-actions panel. This is fresh work — do not throw it away casually.
- The **phone⟷watch comm + config core** (`PhoneConnection`, `MusicViewModel`,
  `WatchActionConfigProvider`, the protobuf action/button config system).

**Strategic implication:** the app's interaction model is custom by design and
does not map cleanly onto stock Wear Compose patterns (`ScalingLazyColumn`,
`SwipeDismissableNavHost`). The highest-leverage modernization is therefore
*additive platform integration*, not a UI rewrite. The rewrite is deferred and
optional.

---

## 2. Guiding principles

1. **Preserve the input/comm core.** Modernize rendering and add platform
   surfaces *around* `StemButtonsManager` / `FourWayTouchLayout` / crown /
   `PhoneConnection`, not by replacing them.
2. **Additive before destructive.** New entry points (Tiles, Complications,
   system media) ship without touching the existing UI.
3. **Each phase is independently shippable** and independently revertable.
4. **Don't discard the v1.11 work.** Any UI migration is screen-by-screen behind
   interop, never a big-bang rewrite.
5. **Lean on Google's libraries** (Horologist, Tiles/ProtoLayout, Complications)
   rather than hand-rolling.

---

## 3. Architectural keystone: a watch-side `MediaSession` mirror

Today the watch is purely a *remote control* — it renders custom UI and forwards
transport commands to the phone over the Data Layer. It has **no
`MediaSession`** of its own. That's the root reason none of the native surfaces
work: Tiles, Complications, the system media template, and Assistant all read a
local `MediaSession`.

**Proposal:** add a watch-side `MediaSession` (likely a `MediaLibraryService` /
Media3 session) that:
- **mirrors** the phone's now-playing state (title/artist/art/position/playing)
  that `PhoneConnection.musicState` already receives, and
- **forwards** its transport controls (`play`/`pause`/`skip`/`seek`) back to the
  phone via the existing `CommPaths` messages.

This single piece is the multiplier: once it exists, the system media UI, a
media Tile, and a now-playing Complication all "just work" against one source of
truth instead of each re-implementing state plumbing. It is introduced in
**Phase 1**, and is precisely *why* Tiles & Complications come after it (Phase 2)
— they both read this one source of truth rather than re-plumbing state.

> Open question: confirm Media3 `MediaSession` interop with the current
> `androidx.media` (`MediaSessionCompat`) usage on the mobile side and the
> minSdk 25 floor.

---

## 4. Phased roadmap

### Phase 0 — Foundation (low risk, no UI/UX change)
**Goal:** stand on a modern, non-deprecated base.
- Bump `targetSdkVersion` 30 → **34** (Wear OS 5 / Android 14) — *decided*.
  Re-enable the `ExpiredTargetSdkVersion` lint and fix fallout.
- Replace `AmbientModeSupport` with `AmbientLifecycleObserver`.
- Remove the legacy `com.google.android.support:wearable` dependency; migrate the
  remaining `android.support.wearable.*` usages (`ConfirmationActivity`, etc.) to
  `androidx.wear`.
- Audit runtime permissions / foreground-service-type requirements introduced
  between API 30 and 34 (notably `foregroundServiceType` for the media service).
- **Risk:** low. **Touches core:** no. **Unlocks:** everything below.

### Phase 1 — MediaSession + system integration (medium risk)
**Goal:** integrate with the OS instead of living in a silo. This is the
keystone everything glanceable depends on (Section 3).
- Introduce the **watch-side `MediaSession` mirror** — mirrors phone now-playing
  state and forwards transport controls back over the Data Layer.
- Make the system media template / media controls reflect playback.
- Modernize the existing `OngoingActivity` (`WatchMusicService`) to ride on the
  MediaSession; add `foregroundServiceType="mediaPlayback"`.
- Add app shortcuts / launcher entries where useful.
- **Risk:** medium (new service-layer component, interop to verify).
- **Touches core:** comm layer extended, not replaced.

### Phase 2 — Tiles & Complications (low–medium risk, purely additive)
**Goal:** make the app reachable from the watch face — the biggest "feels
native" win. Built *after* Phase 1 so both surfaces read the one `MediaSession`
source of truth instead of re-plumbing state.
- **2a. Tile** (`androidx.wear.tiles` + `androidx.wear.protolayout`, optionally
  Horologist tiles helpers): a now-playing tile with artwork + play/pause/skip,
  plus quick-action buttons. New `TileService`; self-contained.
- **2b. Complication** (`ComplicationDataSourceService`): show now-playing /
  app state on any watch face; tap opens the app.
- **Risk:** low–medium (first Compose-for-Tiles / ProtoLayout in the module, in
  an isolated way). **Touches core:** no.

### Phase 2.x — Navigation & UX within the current UI (medium risk, View-based)
**Goal:** modernize navigation and UX *inside the existing View UI*, without
adopting Compose.
- Replace / refresh the deprecated `WearableDrawerLayout` menu pattern with a
  modern, still-View-based structure.
- Tighten the back / edge-swipe handling the current UI works around, the menu
  structure, and the onboarding / phone-connection UX.
- **Honest constraint:** "modern Wear navigation" (`SwipeDismissableNavHost`) is
  a Compose construct — staying on Views caps how far navigation can go. This
  phase is pragmatic in-UI improvement; whatever friction remains here is the
  primary input to the Phase 3 decision.
- **Risk:** medium. **Touches core:** View/presentation layer only —
  input/comm preserved.

### Phase 3 — Reassess Compose migration (DECISION GATE — deferred, optional)
**Not committed.** After Phases 0–2.x ship, decide whether the View UI has
become a limiting factor for further UX/architecture work.
- **If yes:** gradual, **screen-by-screen** migration behind `ComposeView`
  interop + Horologist `media-ui`, preserving the custom quadrant/stem/crown
  input layer as the gesture source feeding Compose state.
- **If no:** stop here — the modernization goal is already met without a rewrite.

---

## 5. Suggested sequencing & effort (T-shirt)

| Phase | Effort | Risk | User-visible payoff |
|-------|--------|------|---------------------|
| 0 Foundation | S–M | Low | None directly (enables rest) |
| 1 MediaSession + system media | M–L | Med | High — native feel |
| 2a Tile | M | Low–Med | High — glanceable control |
| 2b Complication | S–M | Low | Medium — watch-face presence |
| 2.x In-UI nav/UX | M | Med | Medium — cleaner navigation |
| 3 Compose UI (gate) | L–XL | High | Medium (UI already polished) |

Order (confirmed): **0 → 1 → 2 (a, b) → 2.x → 3 (gate, only if warranted).**
Phases 0–2 deliver ~80% of the "modern, native" feeling for a fraction of
Phase 3's cost and risk.

---

## 6. Decisions

**Resolved (2026-06-23):**
- ✅ **Target SDK: 34.** Prioritize modernization over old-platform compat;
  testing focuses on modern Wear OS hardware.
- ✅ **Phase 3 (Compose): deferred / optional.** Not for its own sake —
  reassessed only after Phases 0–2.x, if the View UI becomes a limiting factor.

**Still open — decide before the relevant phase:**
- **Media3 vs `MediaSessionCompat`** for the watch-side session (Phase 1), and
  interop with the mobile side's `androidx.media` usage at minSdk 25.
- **Standalone mode:** stays `false` (phone-dependent) unless on-watch playback
  is meant to be first-class.
