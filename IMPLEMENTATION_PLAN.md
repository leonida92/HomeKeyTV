# HomeKey TV - Project Overview & Future Implementation Plan

This document provides a comprehensive description of HomeKey TV, its underlying architecture, current project status (v1.7.1), and an actionable implementation plan for next-generation architectural hardening and feature enhancements.

---

## 1. Project Description & How It Works

**HomeKey TV** is an open-source, low-latency Home Assistant companion overlay and remote button remapper built natively for Android TV (Google TV, Fire OS, AOSP TV). Built using Jetpack Compose (Compose for TV, TV Foundation, and Material 3) with Kotlin Coroutines, it addresses two core Android TV limitations:
1. Eliminates disruptive app-switching that interrupts 4K/HDR video playback or game streaming when controlling smart home devices.
2. Eliminates typing long Home Assistant access tokens (180+ characters) or URLs using a D-pad remote through an embedded phone web setup server.

### Subsystem Architecture

```
[Hardware TV Remote]
        │ (Physical key events)
        ▼
[RemoteButtonRemapService] (Accessibility Engine)
   ├── Press Classifier (Single, Double, Long Press)
   ├── Fast-Path 0ms Bypass (ACTION_DOWN / ACTION_UP)
   ├── Native Xiaomi/AOSP Volume Pass-Through Filter
   ├── Intent Dispatcher ──► [External Broadcasts & Services (e.g. WLED TV)]
   └── Overlay Trigger
        │
        ▼
[DockOverlayManager] (WindowManager Overlay)
   ├── Translucent Pre-Warmed Surface (TYPE_APPLICATION_OVERLAY)
   ├── Lifecycle Controller (ON_START -> ON_RESUME / ON_STOP)
   └── [DockOverlayScreen / MinimalEntityPopups] (D-Pad Navigation Grid)
        │
        ▼
[HAWebSocketClient]
   ├── Long-Lived Token Handshake
   ├── Reactive Entity State Cache (StateFlow)
   ├── Optimistic UI State Transitions
   └── Bi-Directional Service Calls (call_service)
        ▲
        │
        ▼
[Home Assistant Server]
```

### Key Components

1. **Overlay & Window Management**:
   - [DockOverlayManager.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/service/DockOverlayManager.kt): Maintains a system alert window using `TYPE_APPLICATION_OVERLAY`. The overlay remains pre-warmed for 0ms visual pop-in without destroying background video decoders or interrupting audio playback.
   - [DockToggleReceiver.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/receiver/DockToggleReceiver.kt): Exposes inbound broadcast actions (`com.homekey.tv.ACTION_TOGGLE_OVERLAY`, `ACTION_SHOW_OVERLAY`, `ACTION_HIDE_OVERLAY`), allowing external apps (e.g. Button Mapper, Tasker, WLED TV) or ADB scripts to toggle the dock overlay.
   - [DockOverlayScreen.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/ui/panel/DockOverlayScreen.kt): Renders pinned smart entities, recent app satellites, and D-pad navigation grids.
   - [MinimalEntityPopups.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/ui/panel/MinimalEntityPopups.kt) & [CameraDialog.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/ui/panel/CameraDialog.kt): Contextual popups for sliders (brightness, volume, temperature), color pickers, and live camera streams.

2. **Hardware Remote Interception & Accessibility Engine**:
   - [RemoteButtonRemapService.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/service/RemoteButtonRemapService.kt): Extends `AccessibilityService` with `FLAG_REQUEST_FILTER_KEY_EVENTS`. Intercepts remote keycodes (Volume, Color buttons, Guide, Star/Custom buttons, Live TV).
   - Press classifier handles single-press, double-press, and long-press patterns.
   - Dynamic key filtering: Automatically detaches filtering when no buttons are mapped to ensure 0ms added latency.
   - Xiaomi / AOSP Volume Fix: Prevents volume repeat dropping on Android 9/PatchWall devices by temporarily suspending key filtering on unmapped volume clicks to let native `PhoneWindowManager` ramp volume smoothly.
   - Action Dispatcher: Triggers dock display, Home Assistant entity toggles, Home Assistant service calls (`script.*`, `scene.*`), app launches, HDMI source switching (`media_player/select_source`), and explicit broadcast / service intents.

3. **Home Assistant Real-Time Communication**:
   - [HAWebSocketClient.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/api/HAWebSocketClient.kt): Maintains an authenticated OkHttp WebSocket connection with exponential backoff and message ordering channel.
   - Supports optimistic UI state flipping for zero perceived latency.
   - Emits reactive updates via Kotlin `StateFlow`.

4. **Zero-Friction Phone Setup & Secure Storage**:
   - [WebSetupServer.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/server/WebSetupServer.kt): Embedded NanoHTTPD server running on port 8124. Generates an on-screen QR code pointing to `http://<tv-ip>:8124`. Users enter their Home Assistant URL and token on their phone browser with pairing PIN verification.
   - [PreferencesManager.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/local/PreferencesManager.kt): Manages settings, layouts, theme palettes, and Keystore-backed `EncryptedSharedPreferences` for access token security.
   - [LocalAdbManager.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/service/LocalAdbManager.kt): Pure Kotlin ADB client (`dadb`) over `127.0.0.1:5555` to self-grant `ACCESS_RESTRICTED_SETTINGS`, `SYSTEM_ALERT_WINDOW`, and `WRITE_SECURE_SETTINGS` on initial setup without requiring a PC.

---

## 2. Current Status (v1.7.1)

- **Version**: `1.7.1` (`versionCode = 30`)
- **Status on TV (`192.168.1.50:5555`)**: Installed and running.
- **Git Status**: Working tree contains uncommitted changes tested and ready for user approval:
  - Broadcast & Service Intent Remapping support (`SEND_BROADCAST`, `START_SERVICE`).
  - [DockToggleReceiver.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/receiver/DockToggleReceiver.kt) inbound broadcast handler.
  - Text cutoff fix in [SettingsScreen.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/ui/settings/SettingsScreen.kt) for Intent Action and Target Package inputs.
  - Unit tests updated and passing in [HAModelTest.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/test/java/com/homekey/tv/HAModelTest.kt).

---

## 3. Implementation Plan for Next Session

### Phase 1: Architectural Hardening & Memory Hygiene (Completed in v1.7.1)

#### Task 1.1: WindowManager Overlay Lifecycle & Composition Hygiene [COMPLETED]
* **Problem**: When the dock is hidden, leaving the Compose tree attached in `STARTED`/`PAUSED` state keeps Compose frame clocks active, preventing GPU textures and memory from being fully released.
* **Solution**:
  - In [DockOverlayManager.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/service/DockOverlayManager.kt), updated `hide()` and `OverlayLifecycleOwner` to transition to `Lifecycle.Event.ON_STOP` (`Lifecycle.State.CREATED`).
  - When shown, transitions `Lifecycle.Event.ON_START` followed by `Lifecycle.Event.ON_RESUME`.
  - Configured `ComposeView` composition strategy to `ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool`.
  - Added `DisposableEffect` in [CameraDialog.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/ui/panel/CameraDialog.kt) to drop and dereference camera bitmaps and close connections on dialog dismiss.

#### Task 1.2: WebSocket Entity Subscription Optimization (`subscribe_entities`) [COMPLETED]
* **Problem**: Subscribing to raw `state_changed` floods the TV with events for 700+ entities (power meters, sensor ticks, uptime), triggering unnecessary JSON deserialization and GC thrashing.
* **Solution**:
  - In [PreferencesManager.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/local/PreferencesManager.kt) and [PanelViewModel.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/viewmodel/PanelViewModel.kt), automatically track active monitored entities (pinned dock entities, linked humidity sensors, button remap targets, and open dialog entities).
  - In [HAWebSocketClient.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/api/HAWebSocketClient.kt), debounced reactive updates (300ms) issue Home Assistant `subscribe_entities` targeting monitored entities.
  - Implemented pure differential updater [HAEntityDiffProcessor](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/models/HAModels.kt) supporting snapshot additions (`a`), property diffs (`c` with `+` state/attrs and `-` deletions), and removals (`r`).
  - Added graceful fallback to `subscribe_events(state_changed)` if `subscribe_entities` is rejected by older HA cores.
  - Comprehensive unit tests added in [HAModelTest.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/test/java/com/homekey/tv/HAModelTest.kt).

#### Task 1.3: Remote Button Fast-Path Key Cache [COMPLETED]
* **Problem**: Iterating or scanning configs on every physical keypress adds micro-latency.
* **Solution**:
  - In [RemoteButtonRemapService.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/service/RemoteButtonRemapService.kt), indexed configured keycodes into an unboxed primitive `SparseArray<ButtonRemapConfig>` for 0ms sub-millisecond lookup in `onKeyEvent`.
  - Maintained adaptive single-action optimization: fires immediately on `ACTION_DOWN` if single-only is configured, and fires immediately on `ACTION_UP` if long-press is present but double-press is null.

---

### Phase 2: Modern Android TV Platform Foundations (Completed in v1.7.1)

#### Task 2.1: DataStore + Google Tink Encrypted Storage Migration [COMPLETED]
* **Problem**: `EncryptedSharedPreferences` (`androidx.security:security-crypto`) is deprecated and prone to synchronous main-thread stalls and flash storage corruption on budget TV hardware.
* **Solution**:
  - In [PreferencesManager.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/local/PreferencesManager.kt), migrated general configuration to Jetpack Preferences DataStore (`Context.haDataStore`) with automated `SharedPreferencesMigration`.
  - In [TinkKeystoreCrypto.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/local/TinkKeystoreCrypto.kt), implemented Google Tink AEAD (`AES256_GCM`) backed by Android Keystore with un-mastered fallback and Base64 encryption routines.
  - Implemented seamless one-time token migration from legacy `EncryptedSharedPreferences` and plaintext stores, encrypting with Tink AEAD and scrubbing legacy files without logging out the user.
  - Retained in-memory `MutableStateFlow`s seeded synchronously at cold startup on `Dispatchers.IO` for 0ms access latency across ViewModels, Services, and Overlays.
  - Refined base monitored entities filter to only include Home Assistant actions (`TOGGLE_ENTITY`, `SELECT_SOURCE`), preventing invalid app/intent package targets from triggering core `subscribe_entities` rejections.

#### Task 2.2: Android 11+ Wireless Debugging (mDNS & Pairing Protocol) [COMPLETED]
* **Problem**: Modern Google TV devices (Android 11–14) disable plain TCP port 5555 by default in favor of dynamic ports and TLS pairing.
* **Solution**:
  - In [LocalAdbManager.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/service/LocalAdbManager.kt), implemented `NsdManager` service discovery for `_adb-tls-connect._tcp`, `_adb._tcp`, and `_adb-tls-pairing._tcp`.
  - Added automatic fallback to dynamic port discovery upon connection refusal at default port 5555.
  - Added `CHANGE_WIFI_MULTICAST_STATE` in [AndroidManifest.xml](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/AndroidManifest.xml) to preserve mDNS packet reception on Wi-Fi interfaces.
  - Added [AdbCustomPortDialog](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/ui/settings/SettingsScreen.kt) in Settings for entering dynamic Wireless Debugging ports directly on TV with D-pad navigation.
  - Updated [SettingsViewModel.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/viewmodel/SettingsViewModel.kt) to surface live dynamic connection progress via `onStatusUpdate`.
  - Added unit test coverage for NSD constants and ADB results in [LocalAdbTest.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/test/java/com/homekey/tv/LocalAdbTest.kt).

---

### Phase 3: High-Value Feature Enhancements [COMPLETED in v1.7.1]

#### Task 3.1: Context-Aware Button Profiles (Per-App Remapping) [COMPLETED]
* **Objective**: Allow remote buttons to perform different actions depending on what app is running.
* **Solution**:
  - In [RemoteButtonRemapService.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/service/RemoteButtonRemapService.kt), tracked `currentForegroundPackage` via `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED`.
  - In [ButtonRemapModels.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/models/ButtonRemapModels.kt), added `appScope: String?` to remap rules.
  - Supported `PASS_THROUGH` action type to explicitly bypass remapping inside streaming or gaming apps (Moonlight, GeForce NOW, Netflix), immediately returning `false` on `onKeyEvent` with zero delay or interception.
  - Implemented two-tier key caching: foreground-scoped remap queries `scopedRemapCache[foregroundPkg]?.get(keyCode)` first, falling back to `globalRemapCache.get(keyCode)` with zero allocations.
  - Added app package picker and PASS_THROUGH action selector in [SettingsScreen.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/ui/settings/SettingsScreen.kt).

#### Task 3.2: Full Web Configurator & JSON Backup/Restore [COMPLETED]
* **Objective**: Expand the embedded phone web portal for complete setup and management.
* **Solution**:
  - In [PreferencesManager.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/local/PreferencesManager.kt), added `HomeKeyBackup` data model, `exportBackupJson()`, and `restoreFromBackupJson()`.
  - In [WebSetupServer.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/server/WebSetupServer.kt), added authenticated endpoints:
    - `GET /api/backup` exports timestamped backup JSON file.
    - `POST /api/restore` parses and applies configuration restore.
  - Added Backup & Restore card with file picker and download trigger in mobile web interface (`getWebHtml()`).

#### Task 3.3: Event-Driven PiP Camera Alerts with Decoder Hygiene [COMPLETED]
* **Objective**: Display non-focus-stealing live doorbell and motion alerts without dropping frames in background 4K HDR playback.
* **Solution**:
  - In [HAModels.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/models/HAModels.kt), defined `@Serializable data class CameraPipAlert(...)`.
  - In [HAWebSocketClient.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/data/api/HAWebSocketClient.kt), subscribed to `homekey_tv_notify` WebSocket events and exposed `pipAlert: StateFlow<CameraPipAlert?>`.
  - Created [CameraPipCard.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/ui/panel/CameraPipCard.kt) with pure software MJPEG parsing (`RGB_565` downsampled bitmaps) guaranteeing zero `MediaCodec` hardware decoder contention with 4K HDR playback.
  - In [DockOverlayManager.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/service/DockOverlayManager.kt), implemented top-right non-focusable, non-touchable alert window overlay with auto-dismiss countdown.
  - Wired reactive collection in [RemoteButtonRemapService.kt](file:///c:/Users/user/Desktop/git/ha-androidtv/app/src/main/java/com/homekey/tv/service/RemoteButtonRemapService.kt).

---

## 4. Quick Verification Reference for New Sessions

```powershell
# Check TV connection
& "C:\Users\user\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices

# Run all unit tests
.\gradlew.bat testDebugUnitTest

# Assemble Release APK
.\gradlew.bat assembleRelease

# Install updated APK on TV
& "C:\Users\user\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 192.168.1.50:5555 install -r app\build\outputs\apk\release\HomeKeyTV-v1.7.1.apk

# Check TV installed package version
& "C:\Users\user\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 192.168.1.50:5555 shell dumpsys package com.homekey.tv | Select-String "versionCode|versionName"
```
