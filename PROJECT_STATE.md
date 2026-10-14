# HomeKey TV - Project State & Architecture Reference

## 1. Project Overview & Current Status

HomeKey TV is a low-latency Home Assistant TV overlay and smart home dock designed for Android TV (Google TV / Fire OS). Built using Jetpack Compose for TV and Kotlin coroutines, it provides a persistent, instant overlay on top of any running TV application or video stream without interrupting playback.

- **Current Version**: `1.7.1` (`versionCode = 30`)
- **Current Git Commit**: Tracking `main` (Uncommitted: v1.7.1 Phases 1, 2, and 3 fully implemented, tested, and active on TV)
- **Target Device**: Android TV connected at `192.168.1.50:5555` (HomeKey TV v1.7.1 active)
- **Build Status**: All unit tests passing (`./gradlew.bat testDebugUnitTest`), release APK assembled and installed (`HomeKeyTV-v1.7.1.apk`)
- **Future Roadmap**: See [IMPLEMENTATION_PLAN.md](file:///c:/Users/user/Desktop/git/ha-androidtv/IMPLEMENTATION_PLAN.md) for detailed architecture hardening and feature roadmap.

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
- **Button Remap Accessibility Service & Navigation Fixes**:
  - `RemoteButtonRemapService` intercepts remote key codes, supporting single-press, double-press, and long-press triggers for overlay display, entity toggles, and app launches.
  - Symmetrical D-pad navigation across Button Remap view: entering Tab 3 directs focus immediately to the left column (`learnButtonFocusRequester` or accessibility action buttons); D-pad UP steps into Volume Fix and Tab 3; D-pad RIGHT jumps directly into the configured remaps column; D-pad LEFT from any configured remap card returns focus back to Learn Remote Button; D-pad UP from top remap returns to Tab 3.
  - Compact inline `VolumeFixCompactToggle` placed directly in the Accessibility Status Card header, reclaiming vertical screen height and preventing the Remote Buttons card from being cut off.
- **Compact Recent Apps Toggle & Dock Pinning**:
  - Squeezed `RecentAppsToggleCard` into a slim single-row card (height reduced from ~160dp down to ~46dp), saving over 110dp of vertical space to ensure an entire extra row of installed apps is visible in the grid without scrolling.
  - Sized limit chips to 34x28dp with 12sp font and horizontal expand/collapse animation.
  - Fluid D-pad flow: Tab 4 DOWN -> Toggle -> DOWN -> Apps Grid; RIGHT -> Limit chips (1..5) -> LEFT -> Toggle; UP from top row of apps returns cleanly to the Toggle.
- **Dynamic Key Filtering & Latency Reduction**: When no button remaps are configured and Learn Mode is inactive, `FLAG_REQUEST_FILTER_KEY_EVENTS` is detached from the Android framework, achieving 0ms added input latency and preventing OS accessibility key interception across the TV UI.
- **Volume Buttons Fix (Xiaomi / Android 9 TV Compatibility)**: Resolves the AOSP / PatchWall hardware volume repeat bug on Android 9 devices (such as Xiaomi Mi TV). Unmapped volume presses temporarily suspend key filtering for 3.5 seconds, allowing the TV's native `PhoneWindowManager` to execute continuous hardware repeat ramping, HDMI-CEC soundbar volume adjustments, and standard audio stream targeting without dropped repeats.
- **Embedded Web Setup Server**: Lightweight HTTP server on port 8124 with QR pairing, mobile-friendly collapsible sections, entity search/filter, and native color pickers.

### Architectural Hardening & Memory Hygiene (Phase 1)
- **Overlay Lifecycle & Composition Hygiene**:
  - `DockOverlayManager` transitions `OverlayLifecycleOwner` to `Lifecycle.Event.ON_STOP` (`Lifecycle.State.CREATED`) on hide, pausing Compose frame clocks and releasing GPU textures.
  - On show, steps through `Lifecycle.Event.ON_START` and `Lifecycle.Event.ON_RESUME`.
  - ComposeView configured with `ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool`.
  - Camera streaming and active popup state cleanly dismissed and dereferenced on hide, with explicit `DisposableEffect` bitmap clearing.
- **Targeted Entity Subscriptions (`subscribe_entities`)**:
  - Replaced indiscriminate global `state_changed` listening with targeted `subscribe_entities` covering the active monitored set (pinned entities + button remap targets + active dialog entities).
  - Pure differential state merger (`HAEntityDiffProcessor`) processes snapshot additions (`a`), property diffs (`c` with `+` state/attrs and `-` deletions), and removals (`r`).
  - Seamless fallback to `state_changed` if `subscribe_entities` is unsupported by older Home Assistant instances.
  - Background streaming confined to monitored entities, preventing hundreds of background sensor updates from causing JSON deserialization GC churn.
- **Remote Button Fast-Path Key Cache**:
  - `RemoteButtonRemapService` caches configured keycodes in an unboxed `SparseArray<ButtonRemapConfig>`.
  - Sub-millisecond lookup in `onKeyEvent` with 0 object allocations and 0 list scanning.
  - Adaptive single-action optimization: fires immediately on `ACTION_DOWN` if single-only configured; fires on `ACTION_UP` if long-press configured without double-press.

### Modern Android TV Platform Foundations (Phase 2)
- **DataStore & Tink Keystore Hardware Encryption**:
  - `PreferencesManager` migrated to Jetpack Preferences DataStore (`Context.haDataStore`) with automated `SharedPreferencesMigration` from `ha_tv_prefs`.
  - `TinkKeystoreCrypto` wraps Google Tink AEAD (`AES256_GCM`) backed by the hardware Android Keystore with un-mastered fallback and Base64 encryption routines.
  - Transparent, seamless one-time migration for existing users: reads credentials from legacy `EncryptedSharedPreferences` / plaintext, stores encrypted ciphertext in DataStore, and scrubs legacy files without requiring re-authentication.
  - Synchronous cold-start loading on `Dispatchers.IO` seeds reactive `MutableStateFlow`s, guaranteeing zero cold-boot latency across all overlay layers and background services.
  - Restricts base monitored entities to Home Assistant actions (`TOGGLE_ENTITY`, `SELECT_SOURCE`), eliminating invalid app/intent package targets and preventing core `subscribe_entities` rejections.
- **Android 11+ Wireless Debugging & Dynamic Ports**:
  - `LocalAdbManager` implements `NsdManager` service discovery for `_adb-tls-connect._tcp`, `_adb._tcp`, and `_adb-tls-pairing._tcp`.
  - Automatic fallback to dynamic port discovery upon connection refusal at default port 5555.
  - `CHANGE_WIFI_MULTICAST_STATE` declared in `AndroidManifest.xml` to prevent Wi-Fi chipsets from discarding local mDNS queries.
  - Interactive `AdbCustomPortDialog` in TV Settings allows users on Android 11–14 to enter dynamic Wireless Debugging ports directly using the remote D-pad.
  - Real-time connection feedback surfaced in `SettingsViewModel` via `onStatusUpdate`.

### High-Value Feature Enhancements (Phase 3)
- **Context-Aware Button Profiles (Per-App Remapping) & PASS_THROUGH Action**:
  - `RemoteButtonRemapService` tracks active foreground application via `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED`.
  - Two-tier key caching: unboxed keycode index prioritized by active foreground package (`scopedRemapCache`), falling back to global rules (`globalRemapCache`) with zero allocations in `onKeyEvent`.
  - `PASS_THROUGH` action returns `false` synchronously on `ACTION_DOWN` and `ACTION_UP` without consuming physical remote key events, ideal for Moonlight, GeForce NOW, and Netflix.
  - App Scope selection dropdown and PASS_THROUGH action selector in TV Settings UI.
- **Full Web Configurator & JSON Backup/Restore**:
  - `PreferencesManager.exportBackupJson()` and `restoreFromBackupJson()` serialize the complete application configuration state (`HomeKeyBackup`).
  - `GET /api/backup` with PIN authentication and attachment header for one-click JSON backup export.
  - `POST /api/restore` supporting wrapped JSON payloads and raw configuration uploads.
  - Section 5 Backup & Restore collapsible card with file picker and download trigger in mobile web interface (`getWebHtml()`).
- **Event-Driven PiP Camera Alerts with Decoder Hygiene**:
  - `HAWebSocketClient` listens for `homekey_tv_notify` WebSocket events and exposes `pipAlert: StateFlow<CameraPipAlert?>`.
  - `CameraPipCard` composable renders a top-right corner card (360x220dp) with software-decoded MJPEG (`RGB_565`), pulsing alert indicator, and countdown timer.
  - Decoder hygiene: Pure software stream parsing with zero `MediaCodec` allocation, preventing starvation of hardware decoders during 4K HDR playback.
  - `DockOverlayManager` hosts the alert using `FLAG_NOT_FOCUSABLE` and `FLAG_NOT_TOUCHABLE`, displaying alerts without stealing D-pad focus from active TV apps.

### Code Audit & Hygiene Refinements
- **Socket & Stream Leak Prevention & Non-Blocking Async Cancellation**:
  - All OkHttp HTTP response instances and input streams (`byteStream()`) wrapped in `.use { ... }` blocks across `CameraDialog.kt`, `CameraPipCard.kt`, and `WebSetupServer.kt`.
  - Registered coroutine `Job.invokeOnCompletion { call.cancel() }` on blocking OkHttp calls in `CameraDialog.kt` and `CameraPipCard.kt`, instantly interrupting and aborting background socket operations when dialogs or PiP cards are dismissed.
- **High-Performance Chunk-Based Block-Writing MJPEG Parser**:
  - Refactored `parseMjpegStream` to scan 16KB network chunks in memory and write contiguous slices via `System.arraycopy` (`frameStream.write(buffer, cursor, len)`), replacing millions of individual byte writes per second.
  - Implemented boundary split marker detection (`trailing0xFF`) to stitch SOI (`0xFF 0xD8`) and EOI (`0xFF 0xD9`) markers spanning across consecutive TCP packets.
  - OOM Guard with Heap Reclamation: If a corrupted stream exceeds `MAX_FRAME_SIZE` (4MB), `frameStream` is discarded and reallocated with a fresh 64KB buffer, releasing the multi-megabyte heap allocation immediately rather than retaining it via `reset()`.
  - Comprehensive unit test coverage added in `HAModelTest.kt` verifying single-chunk, multi-chunk, split-marker, and sequential frame extraction.
- **Android 11+ (API 30+) Strict WindowContext Compliance**:
  - In `DockOverlayManager.showPipAlert()`, on API 30+, views are instantiated using a dedicated `context.createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)` with fallback to the accessibility service context on API 28–29, preserving display density and theme tokens without triggering strict-mode violations (`IncorrectContextUseViolation`).
  - `pipRootLayout`, `pipComposeView`, and lifecycle owners are set to `null` and views immediately detached in `dismissPip()`, preventing memory leaks when PiP is dismissed.
- **Service & Companion Object State Reset**:
  - `RemoteButtonRemapService.onDestroy()` resets `currentForegroundPackage = null`.
  - Removed obsolete API 26 runtime checks (`startForegroundService` called directly).
- **NSD & ADB Cleanup**:
  - In `LocalAdbManager.kt`, converted discovery listener to `val`, resolved nested `this` references, and eliminated redundant nullable initializers for `dadbInstance`.
- **Manifest Hygiene**:
  - Declared `tools:ignore="ExportedReceiver"` on `DockToggleReceiver` in `AndroidManifest.xml` to document intentional public broadcast API for external automation tools.

---

## 3. Key Architectural Decisions

1. **WindowManager Overlay vs. Leanback Activity**:
   - `DockOverlayManager` renders Compose UI directly onto a WindowManager view rather than launching an Activity. This guarantees instant 0ms invocation and allows TV video content to keep playing uninterrupted beneath the overlay.
2. **Unidirectional Data Flow (UDF)**:
   - `HAWebSocketClient` maintains a singleton persistent WebSocket connection.
   - Raw states are deserialized into immutable models and published via Kotlin `StateFlow`.
   - `PreferencesManager` orchestrates persistent configuration (Jetpack Preferences DataStore / Google Tink Keystore AEAD).
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

1. **User Review & Git Commit**:
   - The user will review all changes across Phases 1, 2, and 3, and commit them together to GitHub.
   - On the first build following the official commit, version will bump to `1.7.2` (`versionCode = 31`).
2. **Feature Backlog**:
   - **Extended Domain Popups**: Add dedicated dialog controls for Covers/Blinds (open/close/stop/tilt slider), Fans (oscillate/speed presets), and Locks (PIN prompt, lock/unlock toggle).
   - **ExoPlayer / HLS Stream Integration**: Provide an optional RTSP/HLS stream viewer for cameras supporting WebRTC/HLS when MJPEG latency is high.
   - **Dock Pin Reorder Enhancements**: Add vertical reordering indicators for sidebar layouts.
