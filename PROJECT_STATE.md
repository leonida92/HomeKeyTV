# HomeKey TV - Project State & Architecture Reference

## 1. Project Overview & Current Status

HomeKey TV is a low-latency Home Assistant TV overlay and smart home dock designed for Android TV (Google TV / Fire OS). Built using Jetpack Compose for TV and Kotlin coroutines, it provides a persistent, instant overlay on top of any running TV application or video stream without interrupting playback.

- **Current Version**: `1.8.1` (`versionCode = 32`)
- **Current Git Commit**: Tracking `main` (Dual-Endpoint Network Failover, MJPEG Pipeline Protection, and Modular Dock UI)
- **Target Device**: Android TV connected at `192.168.1.50:5555` (HomeKey TV v1.8.1 active)
- **Build Status**: All unit tests passing (`./gradlew testDebugUnitTest`), release APK assembled and installed (`HomeKeyTV-v1.8.1.apk`)
- **Architecture Documentation**: See `docs/adr/` (`0001`, `0002`, `0003`, `0007`, `0008`) and `GLOSSARY.md`.

---

## 2. Architecture & Seams Overview

### Isolated Home Assistant Network ACL (ADR 0002 & ADR 0008)
- **`HAMessageCodec`**: Pure stateless Kotlin serialization and translation boundary between Home Assistant WebSocket protocol frames and immutable domain state models.
- **`HATransport` & `OkHttpHATransport`**: Lean transport abstraction for network socket I/O without protocol or reconnect logic.
- **`HomeAssistantRepository` & `DefaultHomeAssistantRepository`**: Reactive session coordinator with exponential backoff, atomic updates, and domain entity reconciliation.
- **Dual-Endpoint Failover & Opportunistic Failback (ADR 0008)**:
  - Supports primary local URL (`serverUrl`) and cloud/Nabu Casa URL (`fallbackServerUrl`).
  - Fast 3-second timeout and 2 consecutive failure threshold to initiate failover to the fallback endpoint.
  - Opportunistic local LAN failback probing triggered on dock open (`notifyOverlayShown()`) and on a 60-second background cadence (`probeLocalFailback()`).
  - Injectable `endpointProbe: suspend (String) -> Boolean` for 100% deterministic JVM unit testing without network sockets.
  - Camera routes dynamically synchronized via `activeServerUrl: StateFlow<String>` to prevent camera stream blackout during failover.

### Modularized Dock UI Architecture
- Extracted monolithic `DockOverlayScreen.kt` (originally 2,424 lines) into dedicated, focused composables in package `com.homekey.tv.ui.panel`:
  - **`FloatingLabelBadge.kt`**: `FocusBadgeState` and floating focused entity/app badge with enter/exit animations.
  - **`DockIcons.kt`**: Domain icon mapping (`resolveDockIcon`), domain color extraction (`getActiveColor`), and `rememberAppIconBitmap` with concurrent caching.
  - **`MinimalEntityPopupContent.kt`**: Minimal popup router for compact camera snapshots, brightness, switches, media player, covers, and climate cards.
  - **`RecentAppsSatellite.kt`**: Satellite bubble layout for recently used applications in horizontal and vertical configurations.
  - **`DockTile.kt`**: Standard interactive entity dock tile, reorder mode tile, and settings gear tile.
  - **`DockLists.kt`**: `DockListHorizontal` and `DockListVertical` orchestrating lazy row and lazy column dock layouts.
  - **`DockOverlayScreen.kt`**: Trimmed to ~700 lines serving exclusively as the root orchestrator and modal dialog container.

### Software-Downsampled MJPEG Camera Pipeline (ADR 0007)
- Pure software HTTP streaming via `cameraHttpClient` decoding downsampled `RGB_565` bitmaps.
- Completely avoids hardware `MediaCodec` instances to eliminate decoder contention with active 4K/HDR foreground streaming applications (Netflix, YouTube, Plex).

### Decoupled Overlay Presentation & Remote Input Seams (ADR 0003)
- **`DockOverlayController` & `DefaultDockOverlayController`**: Deep interface exposing reactive visibility (`isShowing: StateFlow<Boolean>`) and lifecycle methods (`show()`, `hide()`, `toggle()`, `handleBack(): Boolean`). Context lifecycle managed via explicit service attachment (`attachService`/`detachService`), completely eliminating static `RemoteButtonRemapService.instance` memory leaks.
- **`RemapActionDispatcher`**: Pure domain action executor taking `PreferencesRepository`, `HomeAssistantRepository`, `DockOverlayController`, and an injectable `globalActionExecutor`. Decoupled from Android accessibility service mocks for pure offline JVM unit testing.
- **`CameraPipOverlayController` & `DefaultCameraPipOverlayController`**: Dedicated manager for transient picture-in-picture camera alerts with isolated non-focusable window parameters, auto-dismiss countdown timer, and decoder hygiene.
- **Authoritative Back Navigation**: `PanelViewModel.handleBack()` and `DockOverlayController.handleBack()` establish a single strict source of truth for back navigation:
  `Active Dialog Dismissal -> Reorder Mode Exit -> Dock Overlay Dismissal`.

---

## 3. Key Architectural Decisions

1. **WindowManager Overlay vs. Leanback Activity**:
   - `DefaultDockOverlayController` renders Compose UI directly onto a WindowManager view rather than launching an Activity. This guarantees instant 0ms invocation and allows TV video content to keep playing uninterrupted beneath the overlay.
2. **Unidirectional Data Flow (UDF)**:
   - `HomeAssistantRepository` maintains a persistent reactive WebSocket connection through `HATransport`.
   - Raw states are deserialized into immutable models via `HAMessageCodec` and published via Kotlin `StateFlow`.
   - `PreferencesRepository` orchestrates persistent configuration (Jetpack Preferences DataStore / Google Tink Keystore AEAD).
   - ViewModels (`PanelViewModel`, `SettingsViewModel`) expose filtered StateFlows to composables.
3. **Global Theme CompositionLocal**:
   - `LocalThemePalette` is defined in `ThemeModels.kt` and injected at the root of `DockOverlayScreen` via `CompositionLocalProvider(LocalThemePalette provides activePalette)`.
   - All child dialogs, floating labels, and tiles consume `LocalThemePalette.current`, eliminating prop-drilling.
4. **Modal Focus Trapping Pattern**:
   - Modal dialogs avoid using Compose `clickable` modifiers on outer backdrop containers to prevent phantom D-pad focus stops.
   - `onPreviewKeyEvent` intercepts `KEYCODE_BACK` and `KEYCODE_ESCAPE` on KeyDown (consumed) and KeyUp (dismisses), guaranteeing single-press dismissal.
   - Grid boundary guards trap directional navigation within modal boundaries.

---

## 4. Ongoing Blockers, Constraints & Environment Rules

- **Zero-Emoji Rule**: No emojis in source code, commit messages, documentation, or chat output.
- **Remote Key Simulation Prohibited**: Do NOT execute `adb shell input keyevent` on the live TV (`192.168.1.50:5555`) to avoid interrupting user viewing sessions.
- **Hardware Deployment Rule**: Always chain `adb connect 192.168.1.50:5555; adb ...` and install release APK (`./gradlew assembleRelease`).
