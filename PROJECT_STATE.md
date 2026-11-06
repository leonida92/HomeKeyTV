# HomeKey TV - Project State & Architecture Reference

## 1. Project Overview & Current Status

HomeKey TV is a low-latency Home Assistant TV overlay and smart home dock designed for Android TV (Google TV / Fire OS). Built using Jetpack Compose for TV and Kotlin coroutines, it provides a persistent, instant overlay on top of any running TV application or video stream without interrupting playback.

- **Current Version**: `1.8.0` (`versionCode = 31`)
- **Current Git Commit**: Tracking `main` (Overlay & Remote Input Seams decoupled and verified)
- **Target Device**: Android TV connected at `192.168.1.50:5555` (HomeKey TV v1.8.0 active)
- **Build Status**: All unit tests passing (`./gradlew testDebugUnitTest`), release APK assembled and installed (`HomeKeyTV-v1.8.0.apk`)
- **Architecture Documentation**: See `docs/adr/` (`0001`, `0002`, `0003`) and `GLOSSARY.md`.

---

## 2. Architecture & Seams Overview

### Isolated Home Assistant Network ACL (ADR 0002)
- **`HAMessageCodec`**: Pure stateless Kotlin serialization and translation boundary between Home Assistant WebSocket protocol frames and immutable domain state models.
- **`HATransport` & `OkHttpHATransport`**: Lean transport abstraction for network socket I/O without protocol or reconnect logic.
- **`HomeAssistantRepository` & `DefaultHomeAssistantRepository`**: Reactive session coordinator with exponential backoff, atomic updates, and domain entity reconciliation.

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
   - `LocalThemePalette` is defined in `ThemeModels.kt` and injected at the root of `PanelOverlayScreen` via `CompositionLocalProvider(LocalThemePalette provides activePalette)`.
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
