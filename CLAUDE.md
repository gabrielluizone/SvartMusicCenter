# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Music Center for Wear — an Android app that lets a paired Wear OS watch control music playback on the phone (and on the watch itself), with customizable physical buttons / digital crown / gestures. Distributed as a sideloaded APK (not on Play Store).

## Module layout

This is a multi-module Gradle (Groovy DSL) Android project, configured in `settings.gradle` / `build.gradle` / `libs.toml` (version catalog):

- `mobile/` — the phone app. Reads the currently playing media session, executes actions (play/pause/skip/volume/Tasker tasks/open other apps), and syncs config/state to the watch over the Wearable Data Layer API. Uses **Dagger 2** (`di/AppComponent.kt`, manual `dagger-android` injection) for DI.
- `wear/` — the watch app. Renders the on-watch UI (now-playing screen, action menu, volume bar), receives button/crown/gesture input, and talks to the phone. Uses **Hilt** for DI (different DI framework than `mobile/` — don't assume Dagger conventions carry over).
- `common/` — shared code linked into both `mobile` and `wear`: communication path constants (`CommPaths.kt`), action/button-config models, protobuf schemas (`src/main/proto/*.proto` — actions, music, watch, notifications, custom lists), shared views/drawables.
- `wearutils/` — a **git submodule** (https://github.com/matejdro/WearUtils) with its own `libs.toml`. If this directory is empty, run `git submodule update --init` before building — Android Studio/Gradle sync will fail otherwise.

### Phone ⟷ watch communication

All communication goes through the Google Play Services **Wearable Data Layer API** (`MessageClient`/`DataClient`), with paths centralized in `common/.../CommPaths.kt`. Key entry points:
- Phone side: `WatchListenerService` (`WearableListenerService`) receives messages and forwards to `MusicService`; `WatchInfoProvider`/`ButtonConfigTransmitter`/`ActionListTransmitter` push config to the watch.
- Watch side: `PhoneConnection`, `WatchMusicService`, `PreferencesReceiver`, `IdleMessageListener` handle the corresponding receiving/sending logic.
- Payloads for structured data (action lists, button configs, watch info) are serialized with **protobuf** using the schemas in `common/src/main/proto/`.

### Watch-side data flow

`PhoneConnection` (Hilt `@Singleton`) listens to `DataClient` updates and exposes `LiveData` properties (`musicState`, `rawPlaybackConfig`, `rawStoppedConfig`, `customList`, etc.). The two separate configs — playing vs. stopped — let users assign different button actions depending on whether music is currently playing.

`MusicViewModel` (`@HiltViewModel`) consumes `PhoneConnection` LiveData and drives `MainActivity`. It owns `WatchActionConfigProvider` (one for each config) which decodes protobuf action configs from the phone into `ButtonAction` objects keyed by `ButtonInfo`.

`WatchMusicService` is a foreground `LifecycleService` that stays alive while music is playing or the UI is open, keeping `PhoneConnection` active. It shuts down after an idle timeout when neither condition holds.

`WatchMediaSession` (`watch/communication/WatchMediaSession.kt`) is a `MediaSessionCompat` proxy: it mirrors phone playback state (metadata, playback state, album art, volume) received from `PhoneConnection`, and forwards transport controls (play/pause toggle, skip, seek, volume) back to the phone over the Data Layer. It is owned and lifecycle-managed by `WatchMusicService`. This is the architectural keystone that enables Wear OS system media surfaces, Tiles, and Complications to see and control the phone's music.

### Watch input handling

`MainActivity` handles three input categories:

1. **Screen gestures** — `FourWayTouchLayout` (from `common/`) divides the screen into quadrants; taps in each quadrant are reported as a `ScreenQuadrant` gesture mapped through button config.
2. **Physical stem buttons** — `StemButtonsManager` (`wear/.../view/StemButtonsManager.kt`) translates raw `KeyEvent` sequences into single-tap, double-tap, or long-press gestures per configured button. Includes an ambient-mode phantom-click workaround.
3. **Rotary encoder / digital crown** — handled via `RotaryEncoderHelper` directly in `MainActivity`; scroll events are sent as volume change messages to the phone.

### Actions system (mobile)

Button presses/gestures map to `PhoneAction` subtypes (`mobile/.../actions/`), each with a corresponding `ActionHandler<T>` implementation (playback, volume, app-launch, Tasker, open menu/playlist, etc.). New actions typically need: an action class, a handler, a binding in `di/ActionHandlersModule.kt`, and an entry in the relevant action list (`RootActionList`, `PlaybackActionList`, `VolumeActionList`).

## Build & test commands

Standard Gradle/Android workflow (run from repo root):

```
./gradlew assembleDebug              # build phone app debug APK
./gradlew :mobile:assembleDebug      # build only the mobile module
./gradlew :wear:assembleDebug        # build only the wear module
./gradlew test                       # run all JVM unit tests
./gradlew :mobile:testDebugUnitTest --tests "*.StreamIntegerTest"   # single test class
./gradlew :wear:testDebugUnitTest --tests "*.StemButtonsManagerTest"
./gradlew lint                       # Android lint across modules
```

Unit tests live under `mobile/src/test` and `wear/src/test` (JUnit 4). `wear/build.gradle` uses the `unmock` plugin to run certain Android-framework-dependent tests on the JVM instead of mocking — see the `unMock { ... }` block when adding tests that touch `android.*` classes in `wear`.

There is no instrumented/UI test setup in this repo currently.

## Signing

Release signing config is pulled from an optional `keystore.properties` file at the repo root (not checked in) — see the `afterEvaluate` block in the root `build.gradle`. Builds without it fall back to the debug signing config.

## Toolchain

- Kotlin (see `libs.toml` for the pinned version), Java/Kotlin target 21.
- `compileSdk 36`, `minSdkVersion` 23 (mobile) / 25 (wear), `targetSdkVersion 30`.
- Dependency versions are centralized in `libs.toml` (root) and `wearutils/libs.toml` (submodule), referenced via Gradle version catalogs (`libs`, `wearUtilsLibs`).

## Package naming gotcha

Despite living in `wear/`, the watch-side code is under the package `com.matejdro.wearmusiccenter.watch.*` (not `.wear.*`). All watch-side classes (`PhoneConnection`, `WatchMusicService`, `MainActivity`, etc.) are in subdirectories named `watch/communication/`, `watch/view/`, `watch/model/`, `watch/config/`, `watch/util/`.

## Modernization roadmap

`docs/wear-modernization-plan.md` contains the full phased modernization strategy: Foundation (targetSdk 30→34, deprecated API cleanup) → MediaSession mirror (watch-side `MediaSession` that forwards controls to the phone) → Tiles & Complications → in-UI navigation. Consult this doc before making architectural decisions in `wear/`.

**Current state (branch `wear-phase-1-mediasession`):** `WatchMediaSession` is implemented and active. Jetpack Compose for Wear OS (`wear.compose.material3`) is enabled in `wear/build.gradle` and used for `QueueActivity`/`QueueScreen` (`watch/view/queue/`). New watch UI work should prefer Compose; the legacy View-based `MainActivity` and `ActionsMenuFragment` remain for existing screens.
