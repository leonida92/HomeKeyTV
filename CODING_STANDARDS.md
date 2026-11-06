# Coding Standards

Enforced during code reviews and automated verification.

## 1. Vocabulary & Domain Language

- Adhere strictly to terms defined in `GLOSSARY.md`.
- **Dock vs Panel**: Use `Dock` (`dockLayout`, `DockConfig`) for the launcher UI. When reading or writing serializable backups or settings, support legacy `panelLayout` as a fallback alias.
- **Pinned Entities vs Items**: Use `pinnedEntity` and `movePinnedEntity`, not generic `item` or `moveItem`.
- **Domain Colors**: Palette colors must map to Home Assistant entity domains (light, switch, media_player, camera, cover, fan, lock, etc.). When introducing support for a new entity domain in repository/UI, add the corresponding domain color field and preset overrides to `DomainColorPalette` in `ThemeModels.kt`. Do not hardcode static domain colors in UI composables.
- **Hardware Key Handling**: Inside Compose UI handling remote D-pad input, qualify key codes with `android.view.KeyEvent.KEYCODE_*` to prevent collisions with `androidx.compose.ui.input.key.KeyEvent`.

## 2. Concurrency & DataStore Persistence

- **Genuine Suspending Calls**: Repository mutation methods marked `suspend` must genuinely suspend on IO via `withContext(Dispatchers.IO) { haDataStore.edit { ... } }`.
- **No Fire-and-Forget Mutations**: Do not launch detached, un-awaited coroutines (`scope.launch`) inside repository write methods. The caller must know when persistence completes.
- **Atomic State Updates**: Mutate in-memory `MutableStateFlow` instances using atomic `.update { ... }` rather than direct assignment (`.value = ...`).
- **Guard Rails**: Write operations with feature flags (e.g. `recordRecentApp`) must check enablement flags (`enabled == true`) before performing mutations or disk writes.

## 3. Architecture Seams & Decoupling

- **Interface Segregation**: ViewModels, services, and receivers must depend on `PreferencesRepository` and `HomeAssistantRepository`, never directly on concrete DataStore implementations or network sockets.
- **Network Decoupling & ACL**: All Home Assistant wire protocol translation is isolated in `HAMessageCodec`. Network socket I/O is abstracted behind `HATransport`. Orchestration, reconnection backoff, and entity reconciliation belong in `HomeAssistantRepository`.
- **No Speculative APIs**: Repository interfaces and wire message encoders must strictly reflect genuine caller intents and reactive domain state, omitting synthetic test hooks or speculative parameters (e.g. unbuilt PIN codes, unused flags). Expose only operations invoked by active UI callers.
- **Test Doubles**: Every public repository and transport interface must have an in-memory fake (`FakePreferencesRepository`, `FakeHomeAssistantRepository`, `FakeHATransport`) to preserve fast, offline unit testability.

## 4. Verification & Testing

- **Fast, Deterministic Unit Tests**: Unit tests must execute offline without Android device or emulator dependencies.
- **Deterministic Test Fixtures**: Model serialization and equality tests must supply explicit, static timestamps (e.g. `1_700_000_000_000L`) rather than relying on dynamic clock defaults (`System.currentTimeMillis()`). Serialization tests must configure `encodeDefaults = true`.
- **Coroutines Testing**: Use `runTest` from `kotlinx-coroutines-test`. ViewModels using `viewModelScope` require `Dispatchers.setMain(testDispatcher)` in `@Before` and `resetMain()` in `@After`. Long-running collectors must run in `backgroundScope` or cancel their test `Job` in `@After` to prevent `UncompletedCoroutinesError`.
- **Framework Null Safety**: Framework services accessed inside ViewModels or background tasks must be null-safe (`?.packageManager ?: return`) for headless JVM unit testability.
- **Build Cleanliness**: `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` must pass with zero errors before merging.
