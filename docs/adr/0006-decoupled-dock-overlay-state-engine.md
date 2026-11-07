# ADR 0006: Decouple Dock Overlay State Engine and Window Presentation Host

## Context & Decision
`DefaultDockOverlayController` tightly tangled Android WindowManager mechanics (`TYPE_ACCESSIBILITY_OVERLAY`), ComposeView composition, lifecycle pre-warming, rapid-toggle debouncing (`350ms`), prewarm delays (`500ms`), modal dialog/reorder cleanup, and back navigation in a monolithic class. Because it depended directly on Android framework classes (`WindowManager`, `Looper`, `SystemClock`, `ComposeView`), overlay state transitions could not be tested on a headless JVM without mocking Android framework internals.

We decided to decouple dock overlay policy and state management from WindowManager presentation:
1. **Pure Kotlin `DockOverlayStateEngine`**: Isolates all state transitions (`Unattached`, `Hidden`, `Prewarmed`, `Showing`), rapid-toggle debouncing (`350ms`), delayed prewarm scheduling (`500ms`), and authoritative back navigation hierarchy into a pure Kotlin engine with zero Android SDK dependencies.
2. **Pluggable `TimeScheduler`**: Reuses the `TimeScheduler` abstraction established in ADR 0005, enabling 100% deterministic JVM unit testing via `VirtualTimeScheduler` without thread sleeps or Handler flakiness.
3. **Presentation Seam (`WindowOverlayHost`)**: Abstract interface isolating physical Android window attachment, ComposeView creation, lifecycle synchronization, and UI modal dismissal (`dismissActiveModal()`).
4. **Resilient State Synchronization**: If host window operations fail, the state engine rolls back state to prevent zombie `isShowing=true` conditions. UI cleanup (`closeEntityDialog()`, `exitReorderMode()`, `notifyOverlayShown()`) is encapsulated within the presentation host.
5. **Backwards-Compatible Android Adapter**: `DefaultDockOverlayController` implements `WindowOverlayHost` and implements `DockOverlayController` by delegating to `DockOverlayStateEngine`, preserving existing caller APIs across the application.

## Considered Options
- **Robolectric Component Tests**: Rejected because Robolectric brings high execution overhead (~10–15s test startup) and does not cleanly simulate timing races between prewarming, rapid toggling, and accessibility service detachment.
- **Keeping State Logic in `DefaultDockOverlayController`**: Rejected because `CODING_STANDARDS.md` § 3 requires pure policy engines with pluggable time abstractions for fast, offline JVM testability.
- **Exposing Compose ViewModel to State Engine**: Rejected because `PanelViewModel` is an `AndroidViewModel` requiring `Application`; abstracting modal dismissal via `WindowOverlayHost.dismissActiveModal()` keeps the engine 100% portable.
