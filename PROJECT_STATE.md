# HomeKey TV - Project State & Architecture Reference

## 1. Project Overview & Current Status

HomeKey TV is a low-latency Home Assistant TV overlay and smart home dock designed for Android TV (Google TV / Fire OS). Built using Jetpack Compose for TV and Kotlin coroutines, it provides a persistent, instant overlay on top of any running TV application or video stream without interrupting playback.

- **Current Version**: `1.6.7` (`versionCode = 26`)
- **Current Git Commit**: Tracking `main` (Release `v1.6.7`)
- **GitHub Release**: `v1.6.7` (Release APK `HomeKeyTV-v1.6.7.apk` attached)
- **Target Device**: Android TV connected at `192.168.1.50:5555`
- **Build Status**: All unit tests passing (`./gradlew.bat testDebugUnitTest`), zero vital lint errors (`./gradlew.bat lintVitalRelease`)

---

## 2. Progress Summary & Features Implemented

### Dock & Satellite System
- **0ms Instant Overlay**: WindowManager overlay (`TYPE_APPLICATION_OVERLAY`) pre-warmed by `DockOverlayManager`.
- **Layout Adaptability**: Bottom Dock, Left Sidebar, and Right Sidebar options with automatic geometry calculations.
- **Satellite Recent Apps Row**: Tracks and displays up to 5 recently launched apps in an adjacent dock satellite, supporting smooth bidirectional D-pad focus transitions between dock items and apps.
- **Smart Reorder Mode**: Hold OK / Long press on tiles triggers reorder mode with visual elevation, amber borders, and automatic hiding of reordering controls when fewer than 2 entities are pinned.
- **Floating Contextual Label**: `FloatingLabelBadge` displays the focused entity friendly name, uppercase state, and humidity sensor readings extracted automatically from climate or linked sensor entities.
- **Icon Gallery & Resolution**: 78 custom smart home Material symbols across 11 categories resolved 1:1 into Compose ImageVectors.

### Theme Engine & Domain Colors
- **Built-in Presets**:
  - `HomeKey Classic`: Original smart home color palette.
  - `Cupertino Glow`: Apple HomeKit warm luminous design.
  - `Cyberpunk Neon`: High-contrast electric neon palette.
  - `Nordic Calm`: Minimalist Scandinavian earth tones.
  - `Monochrome Luxe`: High-contrast OLED white and platinum styling.
- **Custom Palette Mode**: Full customization of individual domain hex colors:
  - Lights, Switches, Climate, Camera Feeds, Media Players, Covers, Fans, Vacuums, Scenes, Sensors.
  - Off-State Background color.
  - Active Icon Color with luminance-based contrast detection (`isColorDark`) ensuring icons remain sharp on toggled backgrounds.
- **Camera Feed Domain Styling**: Cameras reporting `idle`, `streaming`, or `recording` in Home Assistant illuminate with the camera theme color.
- **Borderless Idle Styling**: Dock tiles, reorder tiles, settings tiles, and badges maintain a `0.dp` / `Color.Transparent` border when unfocused, displaying crisp highlight borders only on focus (`Color.White`) or reorder (`HA_Yellow_On`).

### Popups & Controls
- **Theme-Adaptive Containers**: `BrightnessDialog`, `SwitchDialog`, `ClimateDialog`, `MediaPlayerDialog`, `CameraDialog`, and `MinimalEntityPopups` dynamically inherit the active theme background and domain accent colors.
- **Outer Borders Removed**: Popups blend seamlessly without thick outer frames.
- **Debounced Controls**: Sliders (brightness, volume, temperature) throttle WebSocket service calls by 300ms while maintaining 60/120fps on-screen feedback.
- **Live Camera Streaming**: OkHttp MJPEG streaming with automated snapshot fallback, dynamic aspect ratio preservation, and connection lifecycle management.
- **D-Pad Isolation & 1-Press Dismissal**:
  - Boundary navigation interceptors prevent D-pad UP from escaping modal dialogs (such as `TVColorPickerDialog`) into parent tab bars.
  - Intermediate focus stops removed by eliminating `.clickable` wrappers on modal backdrops.
  - Dedicated `FocusRequester` mappings restore focus directly to the invoked domain card upon dialog exit.

### Settings & Web Setup
- **TV Settings Interface**: Full-screen TV settings with 6 tabs: Phone Setup, Layout & Popups, Themes, Button Remap, Installed Apps, and Updates.
- **Button Remap Accessibility Service**: `RemoteButtonRemapService` intercepts remote key codes, supporting single-press, double-press, and long-press triggers for overlay display, entity toggles, and app launches.
- **Dynamic Key Filtering & Latency Reduction**: When no button remaps are configured and Learn Mode is inactive, `FLAG_REQUEST_FILTER_KEY_EVENTS` is detached from the Android framework, achieving 0ms added input latency and preventing OS accessibility key interception across the TV UI.
- **Volume Buttons Fix (Xiaomi / Android 9 TV Compatibility)**: Resolves the AOSP / PatchWall hardware volume repeat bug on Android 9 devices (such as Xiaomi Mi TV). Unmapped volume presses temporarily suspend key filtering for 3.5 seconds, allowing the TV's native `PhoneWindowManager` to execute continuous hardware repeat ramping, HDMI-CEC soundbar volume adjustments, and standard audio stream targeting without dropped repeats.
- **Embedded Web Setup Server**: Lightweight HTTP server on port 8124 with QR pairing, mobile-friendly collapsible sections, entity search/filter, and native color pickers.

---

## 3. Key Architectural Decisions

1. **WindowManager Overlay vs. Leanback Activity**:
   - `DockOverlayManager` renders Compose UI directly onto a WindowManager view rather than launching an Activity. This guarantees instant 0ms invocation and allows TV video content to keep playing uninterrupted beneath the overlay.
2. **Unidirectional Data Flow (UDF)**:
   - `HAWebSocketClient` maintains a singleton persistent WebSocket connection.
   - Raw states are deserialized into immutable models and published via Kotlin `StateFlow`.
   - `PreferencesManager` orchestrates persistent configuration (Jetpack Security / EncryptedSharedPreferences).
   - ViewModels (`PanelViewModel`, `SettingsViewModel`) expose filtered StateFlows to composables.
3. **Global Theme CompositionLocal**:
   - `LocalThemePalette` is defined in `ThemeModels.kt` and injected at the root of `DockOverlayScreen` via `CompositionLocalProvider(LocalThemePalette provides activePalette)`.
   - All child dialogs, floating labels, and tiles consume `LocalThemePalette.current`, eliminating prop-drilling.
4. **Modal Focus Trapping Pattern**:
   - Modal dialogs avoid using Compose `clickable` modifiers on outer backdrop containers to prevent phantom D-pad focus stops.
   - `onPreviewKeyEvent` intercepts `KEYCODE_BACK` and `KEYCODE_ESCAPE` on KeyDown (consumed) and KeyUp (dismisses), guaranteeing single-press dismissal.
   - Grid boundary guards trap directional navigation within modal boundaries.
5. **Strict Release Versioning Policy**:
   - Version numbers (`versionCode`, `versionName`) remain constant across builds during an active development/testing cycle.
   - Version increments occur strictly on the first build following an official commit and release to GitHub.

---

## 4. Ongoing Blockers, Constraints & Environment Rules

- **Zero-Emoji Rule**: No emojis in source code, commit messages, documentation, or chat output.
- **Remote Key Simulation Prohibited**: Do NOT execute `adb shell input keyevent` on the live TV (`192.168.1.50:5555`) to avoid interrupting user viewing sessions.
- **Version Increment Constraint**: Do NOT bump `versionCode` or `versionName` until the current version has been committed to GitHub.
- **Network / Protocol Fallbacks**: Camera feeds depend on Home Assistant proxy streaming; networks blocking raw MJPEG streams rely on 1500ms still snapshot polling.

---

## 5. Next Implementation Steps & Roadmap

1. **Post-1.6.7 Iteration (v1.6.8 Target)**:
   - On the first build following the v1.6.7 release commit, bump version to `1.6.8` (`versionCode = 27`).
2. **Feature Backlog**:
   - **Extended Domain Popups**: Add dedicated dialog controls for Covers/Blinds (open/close/stop/tilt slider), Fans (oscillate/speed presets), and Locks (PIN prompt, lock/unlock toggle).
   - **ExoPlayer / HLS Stream Integration**: Provide an optional RTSP/HLS stream viewer for cameras supporting WebRTC/HLS when MJPEG latency is high.
   - **Configuration Backup / Restore**: Enable one-click export and import of button remap configurations and custom theme palettes via the local web setup interface.
   - **Dock Pin Reorder Enhancements**: Add vertical reordering indicators for sidebar layouts.
