# ADR 0003: Decouple Overlay Presentation and Remote Input Seams

## Context & Decision
`DockOverlayManager` and `RemoteButtonRemapService` were tightly coupled in a circular dependency:
- `RemoteButtonRemapService` held a static `var instance: RemoteButtonRemapService? = null` memory leak so external components (`MainActivity`, `DockToggleReceiver`) could pass the service context to `DockOverlayManager.show(service)` or `toggle(service)`.
- `RemoteButtonRemapService` mixed low-level Android hardware key event capture (timing, repeat debouncing, and volume fix) with a 150-line procedural switch executing disparate domain actions (`OPEN_DOCK`, `TOGGLE_ENTITY`, `CALL_SERVICE`, `LAUNCH_APP`, etc.).
- `DockOverlayManager` conflated the full-screen interactive TV dock overlay with the passive, non-focusable Camera PiP notification overlay.
- Back-key handling was duplicated between `OverlayFrameLayout.dispatchKeyEvent()` and `RemoteButtonRemapService.onKeyEvent()`.

We decided to decouple the overlay presentation and input system into focused, deep modules:
1. **`DockOverlayController`**: Clean interface exposing reactive visibility (`isShowing: StateFlow<Boolean>`) and lifecycle methods (`show()`, `hide()`, `toggle()`, `prewarm()`, `teardown()`, `handleBack(): Boolean`). Context lifecycle is managed via explicit service attachment (`attachService`/`detachService`), eliminating the static `instance` leak.
2. **`RemapActionDispatcher`**: Pure domain action executor taking `PreferencesRepository`, `HomeAssistantRepository`, and `DockOverlayController`. Fully unit-testable without Android Accessibility service mocks.
3. **`CameraPipOverlayController`**: Dedicated manager for transient picture-in-picture camera previews with independent window layout parameters and auto-dismiss timer cancellation.
4. **Authoritative Back Navigation**: `DockOverlayController.handleBack(): Boolean` serves as the single source of truth for back navigation (dialogs -> reorder mode -> dock dismissal), with both frame layouts and services delegating directly to it.

## Considered Options
- **Retaining `DockOverlayManager` as an object and keeping static `service.instance`**: Rejected because static Android service references cause memory leaks across service lifecycle restarts and make unit testing impossible.
- **Inlining action execution inside ViewModels**: Rejected because remote button remapping operates globally across background video playback and third-party apps even when no ViewModel or UI screen is currently mounted.
- **Combining Camera PiP and Dock under one controller**: Rejected because the two overlay windows have conflicting window flags (`FLAG_NOT_FOCUSABLE` vs focusable interactive) and divergent lifecycles (automated delay timer vs user D-pad navigation).
