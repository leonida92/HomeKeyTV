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

- **Interface Segregation**: ViewModels, services, and receivers must depend on `PreferencesRepository`, never directly on concrete `DataStorePreferencesRepository` or raw SharedPreferences.
- **Background Orchestration**: Subscriptions and WebSocket entity reconciliation belong in `HAWebSocketClient`, not in `HATVApplication` or UI lifecycle observers.
- **Test Doubles**: Every method added to `PreferencesRepository` must be mirrored in `FakePreferencesRepository` to preserve offline unit testability.

## 4. Verification & Testing

- **Fast, Deterministic Unit Tests**: Unit tests must execute offline without Android device or emulator dependencies.
- **Coroutines Testing**: Use `runTest` from `kotlinx-coroutines-test` for coroutines unit tests.
- **Build Cleanliness**: `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` must pass with zero errors before merging.
