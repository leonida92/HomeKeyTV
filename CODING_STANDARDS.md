# Coding Standards

Enforced during code reviews and automated verification.

## 1. Vocabulary & Domain Language

- Adhere strictly to terms defined in `GLOSSARY.md`.
- **Dock vs Panel**: Use `Dock` (`dockLayout`, `DockConfig`) for the launcher UI. When reading or writing serializable backups or settings, support legacy `panelLayout` as a fallback alias.
- **Pinned Entities vs Items**: Use `pinnedEntity` and `movePinnedEntity`, not generic `item` or `moveItem`.
- **Domain Colors**: Palette colors map to Home Assistant entity domains (`light`, `switch`, `climate`, `camera`, `media_player`, `cover`, `fan`, `lock`, etc.). Derive both Android UI domain lists (`ThemesTab.kt`) and web portal catalogs (`ThemeCatalog.toPortalCatalogJson()`) from `ThemeCatalog` in `ThemeModels.kt`; define all preset/custom color fields there rather than hardcoding domain colors or duplicate domain lists in UI composables or HTML.
- **Hardware Key Handling**: Inside Compose UI handling remote D-pad input, qualify key codes with `android.view.KeyEvent.KEYCODE_*` to prevent collisions with `androidx.compose.ui.input.key.KeyEvent`.

## 2. Concurrency & DataStore Persistence

- **Genuine Suspending Calls**: Repository mutation methods marked `suspend` must genuinely suspend on IO via `withContext(Dispatchers.IO) { haDataStore.edit { ... } }`.
- **No Fire-and-Forget Mutations**: Do not launch detached, un-awaited coroutines (`scope.launch`) for repository writes (including missing-default initialization in `init`). Guard read-modify-write DataStore transactions and in-memory `StateFlow` updates together with a `Mutex` so callers see completed persistence and concurrent edits cannot race.
- **Atomic State Updates**: Mutate in-memory `MutableStateFlow` instances using atomic `.update { ... }` rather than direct assignment (`.value = ...`).
- **StateFlow Adapter Synchronization**: When a service companion or adapter wraps a domain engine's `StateFlow`, UI reset operations must propagate back to the underlying engine to prevent equality-based event conflation on re-entry.
- **Derived StateFlow Projections**: When a secondary `StateFlow` (e.g. `isShowing: StateFlow<Boolean>`) is a direct projection of a primary domain state machine (`DockOverlayState`), mutate through a single authoritative transition function or project it directly (`state.map { ... }`). Never maintain disconnected, independent state updates that risk dual-source drift.
- **Guard Rails**: Write operations with feature flags (e.g. `recordRecentApp`) must check enablement flags (`enabled == true`) before performing mutations or disk writes.

## 3. Architecture Seams & Decoupling

- **Interface Segregation**: ViewModels, services, and receivers must depend on `PreferencesRepository` and `HomeAssistantRepository`, never directly on concrete DataStore implementations or network sockets.
- **Network Decoupling & ACL**: All Home Assistant wire protocol translation is isolated in `HAMessageCodec`. Network socket I/O is abstracted behind `HATransport`. Orchestration, reconnection backoff, and entity reconciliation belong in `HomeAssistantRepository`.
- **No Speculative APIs**: Repository interfaces and wire message encoders must strictly reflect genuine caller intents and reactive domain state, omitting synthetic test hooks or speculative parameters (e.g. unbuilt PIN codes, unused flags). Expose only operations invoked by active UI callers.
- **Pure Input & Policy Engines**: Timing-sensitive gesture recognition and key classification (debouncing, multi-press, long-press, app-scoping) must be isolated into pure Kotlin engines with pluggable time abstractions (`TimeScheduler`) to enable deterministic JVM unit testing without Android SDK or Looper dependencies.
- **Overlay Back-Navigation Precedence**: Overlay back handlers (`handleBack(): Boolean`) must guard on active overlay visibility before evaluating sub-modal or dialog dismissal (`if (!isShowing) return false`) to prevent background or pre-warmed view hierarchies from intercepting OS back presses from third-party foreground applications.
- **Test Doubles**: Every public repository and transport interface must have an in-memory fake (`FakePreferencesRepository`, `FakeHomeAssistantRepository`, `FakeHATransport`) to preserve fast, offline unit testability.
- **Dynamic Media Route Synchronization**: Media decoders, camera snapshots, and asset fetchers must consume `activeServerUrl: StateFlow<String>`, never static `serverConfig.serverUrl`, ensuring active route parity during dual-endpoint failover (ADR 0008).
- **Pluggable Endpoint Probing**: Connection failover coordinators and opportunistic failback routines must accept an injectable probe lambda (`suspend (String) -> Boolean`) to allow 100% deterministic, offline JVM testing of network transitions.

## 4. Verification & Testing

- **Fast, Deterministic Unit Tests**: Unit tests must execute offline without Android device or emulator dependencies.
- **Deterministic Test Fixtures**: Model serialization and equality tests must supply explicit, static timestamps (e.g. `1_700_000_000_000L`) rather than relying on dynamic clock defaults (`System.currentTimeMillis()`). Serialization tests must configure `encodeDefaults = true`.
- **Coroutines Testing**: Use `runTest` from `kotlinx-coroutines-test`. ViewModels using `viewModelScope` require `Dispatchers.setMain(testDispatcher)` in `@Before` and `resetMain()` in `@After`; construct `UnconfinedTestDispatcher(TestCoroutineScheduler())` rather than bare `UnconfinedTestDispatcher()` in field initializers before `setMain`, and inject fake IO providers (`FakeInstalledAppsProvider`, `FakeSetupQrProvider`, etc.) so un-awaited `Dispatchers.IO` work never outlives `@After resetMain()`. Run long-running collectors in `backgroundScope` or cancel their test `Job` in `@After`.
- **Framework Null Safety**: Framework services accessed inside ViewModels or background tasks must be null-safe (`?.packageManager ?: return`) for headless JVM unit testability.
- **Build Cleanliness**: `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, and `./gradlew assembleRelease` must pass with zero errors before merging. Release builds enforce Proguard/R8 and `lintVitalRelease` checks that debug builds skip.
