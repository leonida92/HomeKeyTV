# Coding Standards

Enforced during code reviews and automated verification.

## 1. Vocabulary & Domain Language

- Adhere strictly to terms defined in `GLOSSARY.md`.
- **Dock vs Panel**: Use `Dock` (`dockLayout`, `DockConfig`) for the launcher UI. When reading or writing serializable backups or settings, support legacy `panelLayout` as a fallback alias.
- **Pinned Entities vs Items**: Use `pinnedEntity` and `movePinnedEntity`, not generic `item` or `moveItem`.
- **Domain Colors**: Palette colors must map to Home Assistant entity domains (light, switch, media_player, camera, etc.).

## 2. Concurrency & DataStore Persistence

- **Genuine Suspending Calls**: Repository mutation methods marked `suspend` must genuinely suspend on IO via `withContext(Dispatchers.IO) { haDataStore.edit { ... } }`.
- **No Fire-and-Forget Mutations**: Do not launch detached, un-awaited coroutines (`scope.launch`) inside repository write methods. The caller must know when persistence completes.
- **Atomic State Updates**: Mutate in-memory `MutableStateFlow` instances using atomic `.update { ... }` rather than direct assignment (`.value = ...`).
- **Guard Rails**: Write operations with feature flags (e.g. `recordRecentApp`) must check enablement flags (`enabled == true`) before performing mutations or disk writes.

## 3. Architecture Seams & Decoupling

- **Interface Segregation**: ViewModels, services, and receivers must depend on `PreferencesRepository` and `HomeAssistantRepository`, never directly on concrete DataStore implementations or network sockets.
- **Network Decoupling & ACL**: All Home Assistant wire protocol translation is isolated in `HAMessageCodec`. Network socket I/O is abstracted behind `HATransport`. Orchestration, reconnection backoff, and entity reconciliation belong in `HomeAssistantRepository`.
- **No Speculative APIs**: Repository interfaces must strictly reflect genuine caller intents and reactive domain state, omitting synthetic test-injection hooks.
- **Test Doubles**: Every public repository and transport interface must have an in-memory fake (`FakePreferencesRepository`, `FakeHomeAssistantRepository`, `FakeHATransport`) to preserve fast, offline unit testability.

## 4. Verification & Testing

- **Fast, Deterministic Unit Tests**: Unit tests must execute offline without Android device or emulator dependencies.
- **Coroutines Testing**: Use `runTest` from `kotlinx-coroutines-test`. ViewModels using `viewModelScope` require `Dispatchers.setMain(testDispatcher)` in `@Before` and `resetMain()` in `@After`. Long-running collectors must run in `backgroundScope` or cancel their test `Job` in `@After` to prevent `UncompletedCoroutinesError`.
- **Framework Null Safety**: Framework services accessed inside ViewModels or background tasks must be null-safe (`?.packageManager ?: return`) for headless JVM unit testability.
- **Build Cleanliness**: `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` must pass with zero errors before merging.
