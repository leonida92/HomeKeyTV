# ADR 0005: Decoupled Remote Input & Gesture Engine

## Context & Decision
`RemoteButtonRemapService` originally handled Android AccessibilityService lifecycle, WindowManager overlays, audio volume repeat loops, and complex physical remote gesture recognition in a single monolithic 550-line class.
The event loop in `onKeyEvent()` tangled:
- Contact bounce filtering (`SINGLE_PRESS_DEBOUNCE_MS = 250L`, `MULTI_PRESS_DEBOUNCE_MS = 80L`).
- Multi-action timing state machines (`DOUBLE_PRESS_TIMEOUT_MS = 280L`, `LONG_PRESS_TIMEOUT_MS = 450L`).
- Star button aliasing (keycode 313 mapped to `KEYCODE_PAIRING`).
- App-scoped button prioritization over global mappings.
- Fast-path bypasses for system and navigation keys.
- Xiaomi / Android 9 synthetic volume repeat loops via `AudioManager`.

Because this logic was coupled to Android's `AccessibilityService`, `Handler(Looper.getMainLooper())`, and `SystemClock.uptimeMillis()`, timing-critical remote input behavior could not be verified in fast, deterministic JVM unit tests.

We decided to decouple input interpretation from the Android service:
1. **Pure Kotlin `RemoteInputEngine`**: All key classification, contact bounce filtering, gesture timing, Star button aliasing, and app-scoping live in a pure Kotlin engine with zero Android framework dependencies.
2. **Discrete `InputDecision` Outcomes**: The engine returns a sealed `InputDecision` (`PassThrough`, `Consume`, `Execute(action)`, `HandleBack`, `DismissDock`), completely separating policy from execution.
3. **Pluggable `TimeScheduler` Seam**: Timers and clocks are abstracted behind `TimeScheduler`, allowing deterministic, sub-millisecond unit testing on the JVM using `VirtualTimeScheduler` without real-time thread sleeping or flakes.
4. **Thin Android Service Adapter**: `RemoteButtonRemapService` becomes a thin Android adapter that routes `onKeyEvent()` into the engine, executes actions via `RemapActionDispatcher`, and supplies an `onVolumeAdjust` callback invoked on synthetic timer ticks. Initial unmapped volume clicks return `PassThrough` to retain 0ms native OS volume latency.

## Considered Options
- **Testing via Robolectric**: Rejected because Robolectric introduces heavy test startup overhead (~10–15s per test suite) and does not reliably simulate Android TV remote multi-action timing.
- **Keeping Gesture Logic in `RemoteButtonRemapService`**: Rejected because `CODING_STANDARDS.md` § 3 requires architecture seams that preserve fast, offline JVM unit testability.
- **Emitting Actions via Coroutine Flows Only**: Rejected for synchronous down events because remote control navigation requires 0ms synchronous latency for unmapped navigation keys to prevent input lag in third-party TV apps.
